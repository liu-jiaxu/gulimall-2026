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
 * Gitee 授权登录
 *
 * <p>和 {@link GitHubOAuthController} 完全对称：<b>发起授权 → 接收回调 → 写会话</b>。
 * 换 token / 拉用户信息在 third-party，查会员 / 自动注册在 member。
 *
 * <p><b>为什么要多这一套？</b>
 * GitHub 在国内要挂梯子，third-party 那边经常连不上。
 * Gitee 是国内直连的，接口结构又几乎和 GitHub 一样，是最省事的替代品。
 * 两套<b>并存</b>：登录页上两个入口都在，用户想用哪个点哪个。
 *
 * <p><b>完整流程</b>（{@code →} 为请求方向）：
 * <pre>
 * ① 浏览器      GET  auth.gulimall.vip/oauth2/gitee
 *
 * ② auth-server  gitee()
 *      ├─ 生成随机 state，写入 Redis（TTL 10 分钟）
 *      ├─ 拼授权 URL：client_id + redirect_uri + response_type + state
 *      └─ 302 →  gitee.com/oauth/authorize?...
 *
 * ③ 用户在 Gitee 页面登录并点"同意授权"
 *
 * ④ Gitee       302 →  auth.gulimall.vip/oauth2/gitee/success?code=&amp;state=
 *
 * ⑤ auth-server  success() 校验 state（从 Redis 删除，delete 返回 true 才算通过）
 *      然后 Feign →  member  POST /member/member/oauth2/login?code=&amp;source=gitee
 *
 * ⑥ member       oauthLogin(code, "gitee")
 *      └─ Feign →  third-party  GET /oauth2/gitee/token?code=
 *
 * ⑦ third-party  GiteeOAuthController.token(code)
 *      ├─ WebClient →  POST gitee.com/oauth/token      换 access_token
 *      ├─ WebClient →  GET  gitee.com/api/v5/user      拉用户名、昵称、头像
 *      └─ 组装成 SocialUser（uid=id、login、nickname、header、email）
 *
 * ⑧ member       按 social_uid 查 ums_member（命中刷新 token / 未命中自动注册）
 *
 * ⑨ auth-server  session.setAttribute("loginUser", member) → 302 → gulimall.vip
 * </pre>
 *
 * <p><b>失败分支</b>：⑤ ⑥ ⑦ ⑧ 任意一步失败，一律 302 回
 * {@code auth.gulimall.vip/login.html}。
 *
 * <p>⚠️ <b>和 GitHub 版的两个不同点</b>：
 * <ol>
 *   <li>Gitee <b>不返回 {@code iss} 参数</b>，所以没有那项 RFC 9207 校验
 *       （GitHub 版有，用来防 mix-up 攻击）</li>
 *   <li>Gitee 在普通浏览器里就能用，<b>不需要</b>像微信那样搞环境检测和跨浏览器轮询</li>
 * </ol>
 */
@Controller
@Slf4j
public class GiteeOAuthController {

    /** state 在 Redis 里的 key 前缀 */
    private static final String STATE_CACHE_PREFIX = "oauth:gitee:state:";

    /** state 有效期（分钟） */
    private static final long STATE_TTL_MINUTES = 10;

    @Value("${gulimall.auth-url}")
    private String authUrl;

    @Value("${gulimall.shop-home-url}")
    private String shopHomeUrl;

    @Value("${oauth.gitee.client-id}")
    private String clientId;

    @Value("${oauth.gitee.redirect-uri}")
    private String redirectUri;

    /** Gitee 的授权页地址（配在 common.yaml，方便以后换服务商） */
    @Value("${oauth.gitee.authorize-url}")
    private String authorizeUrl;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private MemberFeignClient memberFeignClient;

    @Autowired
    private LoginRedirectService loginRedirectService;

    @Autowired
    private AgentJwtService agentJwtService;

    /**
     * 发起 Gitee 授权
     *
     * <p>登录页上的 "Gitee" 图标指向这里，浏览器会被 302 到 Gitee 的授权页。
     */
    @GetMapping("/oauth2/gitee")
    public String gitee() {

        // state：防 CSRF。随机生成 → 存 Redis → 回调时比对，且只能用一次
        String state = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(
                STATE_CACHE_PREFIX + state, "1", STATE_TTL_MINUTES, TimeUnit.MINUTES);

        String url = UriComponentsBuilder
                .fromUriString(authorizeUrl)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();

        return "redirect:" + url;
    }

    /**
     * Gitee 授权回调
     *
     * @param code  Gitee 发的一次性授权码
     * @param state 上面发起时生成的随机串，用于校验请求来源
     * @param session 当前用户 Session
     * @param response 当前 HTTP 响应，用于写 agent JWT Cookie
     */
    @GetMapping("/oauth2/gitee/success")
    public String success(@RequestParam("code") String code,
                          @RequestParam(value = "state", required = false) String state,
                          HttpSession session,
                          HttpServletResponse response) {

        // 1、state 必须存在，且只能消费一次（delete 返回 true 表示确实存在过、现已删除）
        //
        //    注意这里【没有】iss 校验 —— Gitee 不返回那个参数。
        //    iss（RFC 9207）是 GitHub 后来加的，用来防 mix-up 攻击；
        //    以后如果登录页上接了多个 Gitee 兼容的服务商，要记得补上等价校验
        if (!StringUtils.hasText(state)
                || !stringRedisTemplate.delete(STATE_CACHE_PREFIX + state)) {
            log.warn("Gitee 回调 state 校验失败（可能被 CSRF 或 state 已过期）：{}", state);
            return "redirect:" + authUrl + "/login.html";
        }

        // 2、把 code 交给 member。source=gitee 会让它去调 third-party 的 Gitee 接口，
        //    后面「查会员 / 自动注册」的流程和 GitHub、微信完全一样
        R r = memberFeignClient.oauthLogin(code, "gitee");
        if (r.getCode() != 0) {
            log.warn("Gitee 登录失败：{}", r.get("msg"));
            return "redirect:" + authUrl + "/login.html";
        }
        MemberResponseVo member = r.getData("data", new TypeReference<MemberResponseVo>() {
        });

        // 3、写会话。走的还是同一份 session（Spring Session → Redis），
        //    前台 gulimall.vip 不关心用户是从哪个渠道登进来的
        session.setAttribute(AuthServerConstant.LOGIN_USER, member);
        agentJwtService.issue(member, response);

        return loginRedirectService.afterLogin(member, shopHomeUrl);
    }

}
