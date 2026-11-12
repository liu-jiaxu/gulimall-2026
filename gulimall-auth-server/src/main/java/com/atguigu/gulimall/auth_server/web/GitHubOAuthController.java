package com.atguigu.gulimall.auth_server.web;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.auth_server.agent.AgentJwtService;
import com.atguigu.gulimall.auth_server.feign.MemberFeignClient;
import com.atguigu.gulimall.auth_server.service.LoginRedirectService;
import com.atguigu.gulimall.common.constant.AuthServerConstant;
import com.atguigu.gulimall.common.utils.R;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * GitHub 社交登录
 *
 * <p>本控制器只做三件事：<b>发起授权、接收回调、写会话</b>。
 * 换 token / 取用户信息在 third-party，查会员 / 自动注册在 member ——
 * auth-server 不碰这些细节。
 *
 * <p><b>完整流程</b>（{@code →} 为请求方向）：
 * <pre>
 * ① 浏览器       GET  auth.gulimall.com/oauth2/github
 *
 * ② auth-server  github()
 *      ├─ 生成随机 state，写入 Redis（key: oauth:github:state:{state}，TTL 10 分钟）
 *      ├─ 拼出授权 URL：client_id + redirect_uri + scope + state
 *      └─ 302 →  github.com/login/oauth/authorize?...
 *
 * ③ 用户在 GitHub 页面登录，并点击同意授权
 *
 * ④ GitHub       302 →  auth.gulimall.com/oauth2/github/success?code=&amp;state=&amp;iss=
 *      ★ 回调发生在任何 member / third-party 调用【之前】。
 *        code 是 GitHub 生成的，auth-server 只生成 state。
 *
 * ⑤ auth-server  success() 先做两项校验
 *      ├─ state：从 Redis 删除该 key，delete 返回 true 才算通过
 *      │         （删除即消费，天然一次性，重放第二次必然失败）
 *      └─ iss  ：必须等于 https://github.com/login/oauth（RFC 9207，防 mix-up）
 *      校验通过后 Feign →  member  POST /member/member/oauth2/login?code=
 *
 * ⑥ member       oauthLogin(code)
 *      └─ Feign →  third-party  GET /oauth2/github/token?code=
 *
 * ⑦ third-party  GitHubOAuthController.token(code)
 *      ├─ WebClient →  POST github.com/login/oauth/access_token   换取 access_token
 *      │               ⚠ 该接口成功/失败都返回 HTTP 200，
 *      │                 必须看 body 里有没有 access_token，不能看状态码
 *      ├─ WebClient →  GET  api.github.com/user                    取用户信息
 *      ├─ 组装成 SocialUser（uid=id、access_token、login、nickname、header、email）
 *      └─ 返回 R{ data: SocialUser }
 *
 * ⑧ member       按 social_uid 查 ums_member
 *      ├─ 命中   → 刷新 access_token、expires_in，直接登录
 *      ├─ 未命中 → 自动注册：username 取 GitHub 的 login（撞名加 6 位随机后缀），
 *      │            不设密码（该账号只能用社交方式登录）
 *      └─ 返回 R{ data: MemberEntity }
 *
 * ⑨ auth-server  success() 收尾
 *      ├─ session.setAttribute("loginUser", member)   ← Spring Session 落 Redis
 *      └─ 302 →  gulimall.com
 *
 * ⑩ 浏览器       携带 cookie GULIMALLSESSION（domain=gulimall.com）访问首页
 *
 * ⑪ product      IndexController 从 session 读 loginUser → 模板显示登录态
 * </pre>
 *
 * <p><b>失败分支</b>：⑤ ⑥ ⑦ ⑧ 任意一步失败，一律 302 回
 * {@code auth.gulimall.com/login.html}，具体包括：
 * <ul>
 *   <li>state 缺失 / 已过期 / 已被消费（重放攻击）</li>
 *   <li>iss 存在但不等于 GitHub 的 issuer</li>
 *   <li>member 返回 code != 0 —— 覆盖 third-party 换 token 失败、连不上 GitHub、
 *       用户信息不完整等情况</li>
 * </ul>
 */
@Controller
@Slf4j
public class GitHubOAuthController {

    /** RFC 9207 规定的 GitHub issuer 固定值 */
    private static final String GITHUB_ISSUER = "https://github.com/login/oauth";

    /** state 在 Redis 里的 key 前缀 */
    private static final String STATE_CACHE_PREFIX = "oauth:github:state:";

    /** state 有效期（分钟） */
    private static final long STATE_TTL_MINUTES = 10;

    @Value("${gulimall.auth-url}")
    private String authUrl;

    @Value("${gulimall.shop-home-url}")
    private String shopHomeUrl;

    @Value("${oauth.github.client-id}")
    private String clientId;

    @Value("${oauth.github.redirect-uri}")
    private String redirectUri;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private MemberFeignClient memberFeignClient;

    @Autowired
    private LoginRedirectService loginRedirectService;

    @Autowired
    private AgentJwtService agentJwtService;

    /**
     * 发起 GitHub 授权
     *
     * <p>登录页上的 "GitHub" 图标指向这里，浏览器会被 302 到 GitHub 的授权页。
     */
    @GetMapping("/oauth2/github")
    public String github() {

        // state：防 CSRF。随机生成 → 存 Redis → 回调时比对，且只能用一次。
        /*
            1. 攻击者在自己浏览器里，用【自己的 GitHub 账号】走一遍授权
               → 拿到一个合法的 code（但先不消费，因为 code 5 分钟后才过期）

            2. 攻击者把这个 code 拼成链接，发给受害者：
               http://auth.gulimall.com/oauth2/github/success?code=<攻击者的code>

            3. 受害者点开 → auth-server 拿这个 code 去 GitHub 换 token
               → 换到的是【攻击者的】GitHub 用户信息
               → 在【受害者的浏览器】里建了 session

            4. 结果：受害者现在"登录成了攻击者的账号"
         */
        String state = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(
                STATE_CACHE_PREFIX + state, "1", STATE_TTL_MINUTES, TimeUnit.MINUTES);

        // UriComponentsBuilder 负责把 redirect_uri 里的 :// 正确编码成 %3A%2F%2F，
        // 手写字符串拼接很容易漏（漏了就报 redirect_uri 参数错误）
        String authorizeUrl = UriComponentsBuilder
                .fromUriString("https://github.com/login/oauth/authorize")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", "read:user user:email")
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();

        return "redirect:" + authorizeUrl;
    }

    /**
     * GitHub 授权回调
     *
     * @param code  GitHub 发的一次性授权码
     * @param state 上面发起时生成的随机串，用于校验请求来源
     * @param iss   GitHub 新加的 issuer 标识（RFC 9207），防 mix-up 攻击
     * @param session 当前用户 Session
     * @param response 当前 HTTP 响应，用于写 agent JWT Cookie
     */
    @GetMapping("/oauth2/github/success")
    public String success(@RequestParam("code") String code,
                          @RequestParam(value = "state", required = false) String state,
                          @RequestParam(value = "iss", required = false) String iss,
                          HttpSession session,
                          HttpServletResponse response) {

        // 1、state 必须存在，且只能消费一次。
        //    delete 返回 true 表示"这个 key 确实存在，且刚刚被我删掉" ——
        //    既完成了比对，又天然保证了一次性（重放第二次时已经不存在了）
        if (!StringUtils.hasText(state)
                || !stringRedisTemplate.delete(STATE_CACHE_PREFIX + state)) {
            log.warn("GitHub 回调 state 校验失败（可能被 CSRF 或 state 已过期）：{}", state);
            return "redirect:" + authUrl + "/login.html";
        }

        // 2、iss 校验（RFC 9207）：确认这个 code 确实由 GitHub 发出。
        //    以后登录页上同时有 GitHub / 微信 / QQ 时，这一步是防止
        //    "把 A 平台的 code 发到 B 平台的 token 接口" 的关键
        if (StringUtils.hasText(iss) && !GITHUB_ISSUER.equals(iss)) {
            log.warn("GitHub 回调 iss 不是预期值，拒绝处理：{}", iss);
            return "redirect:" + authUrl + "/login.html";
        }

        // 3、把 code 交给 member：由它调 third-party 换用户、查/建会员
        //    source=github 决定 third-party 那边走 GitHub 的接口
        R r = memberFeignClient.oauthLogin(code, "github");
        if (r.getCode() != 0) {
            log.warn("社交登录失败：{}", r.get("msg"));
            return "redirect:" + authUrl + "/login.html";
        }
        MemberResponseVo member = r.getData("data", new TypeReference<>() {
        });

        // 4、写入会话。这里是 Spring Session 的 session —— 底层落 Redis，
        // 见document/SpringSession/Spring Session + Redis 接入.md
        session.setAttribute(AuthServerConstant.LOGIN_USER, member);
        agentJwtService.issue(member, response);

        return loginRedirectService.afterLogin(member, shopHomeUrl);
    }

}
