package com.atguigu.gulimall.auth_server.web;

import com.alibaba.fastjson.JSON;
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
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 微信网页授权登录（微信内 H5）
 *
 * <p>结构和 {@link GitHubOAuthController}（GitHub 版）对称：<b>发起授权 → 接收回调 → 写会话</b>。
 * 换 token / 拉用户信息在 third-party，查会员 / 自动注册在 member。
 *
 * <p><b>为什么要多一套"轮询"接口？</b>
 * 微信的网页授权<b>只能在微信内置浏览器里</b>跑。用户在 Edge 里点"微信"是没用的，
 * 微信会直接回一句"请在微信客户端打开链接"。
 *
 * <p>可是——<b>微信内置浏览器和 Edge 的 cookie 是分开存的</b>。
 * 用户在微信里登录成功，那份 session 属于微信内置浏览器，
 * Edge 这边刷新一百遍也读不到。所以需要这样一套流程把登录态"搬"过去：
 *
 * <pre>
 * ① Edge 点"微信" → GET /oauth2/wechat/prepare
 *      后端生成一次性 pollToken，Redis 里记成 PENDING
 *      返回一个带 pollToken 的链接，页面上弹层让用户复制
 *
 * ② 用户把链接粘到微信里打开 → GET /oauth2/wechat?poll=xxx
 *      走下面那套标准 H5 授权
 *
 * ③ 微信回调授权成功 → success() 里发现 state 带着 pollToken
 *      把会员信息写进 Redis（覆盖掉 PENDING）
 *
 * ④ Edge 每 2 秒轮询 → GET /oauth2/wechat/poll?token=xxx
 *      拿到会员信息 → 【在 Edge 这次请求的 session 里】写进去 → 跳首页
 *      ★ 关键就在这一步：会话必须在 Edge 自己的请求上下文里建立
 * </pre>
 *
 * <p><b>标准 H5 授权流程</b>（{@code →} 为请求方向）：
 * <pre>
 * ① 用户在微信内置浏览器打开 login.html，点"微信"
 *
 * ② auth-server  wechat()
 *      ├─ 生成随机 state，写入 Redis（值里带上 pollToken，没有就是空串）
 *      └─ 302 →  open.weixin.qq.com/connect/oauth2/authorize?...＃wechat_redirect
 *
 * ③ 微信弹授权页，用户点"同意"
 *
 * ④ 微信  302 →  auth.gulimall.com/oauth2/wechat/success?code=&amp;state=
 *      ★ 微信【不返回 iss 参数】—— 那是 GitHub 特有的（RFC 9207）
 *
 * ⑤ auth-server  success() 取出并删除 state（顺便拿到 pollToken）
 *      → Feign →  member  POST /member/member/oauth2/login?code=&amp;source=wechat
 *
 * ⑥ member       oauthLogin(code, "wechat")
 *      └─ Feign →  third-party  GET /oauth2/wechat/token?code=
 *
 * ⑦ third-party  WechatOAuthController.token(code)
 *      ├─ WebClient →  GET api.weixin.qq.com/sns/oauth2/access_token  换 access_token + openid
 *      ├─ WebClient →  GET api.weixin.qq.com/sns/userinfo             拉昵称、头像
 *      └─ 组装成 SocialUser（uid=openid、nickname、header）
 *
 * ⑧ member       按 social_uid 查 ums_member（命中刷新 token / 未命中自动注册）
 *
 * ⑨ auth-server  写 session → 302 → gulimall.com
 * </pre>
 *
 * <p>⚠️ <b>和 GitHub 版的三个不同点</b>：
 * <ol>
 *   <li>授权 URL 末尾必须带 {@code #wechat_redirect}，而且要拼在 URL 编码<b>之后</b></li>
 *   <li>微信没有 {@code iss} 参数，不做那项校验</li>
 *   <li>微信的 {@code openid} <b>只对本公众号有效</b>；跨小程序 / App 要靠 unionid</li>
 * </ol>
 *
 * <p>⚠️ <b>本控制器服务于微信内 H5 授权，测试号即可跑通</b>。
 * PC 扫码登录走的是 {@code connect/qrconnect}，需要开放平台「网站应用」资质。
 */
@Controller
@Slf4j
public class WechatOAuthController {

    /** state 在 Redis 里的 key 前缀。值存的是 pollToken（没有则为空串） */
    private static final String STATE_CACHE_PREFIX = "oauth:wechat:state:";

    /** 轮询 token 在 Redis 里的 key 前缀 */
    private static final String POLL_CACHE_PREFIX = "oauth:wechat:poll:";

    /** 还没完成登录时的占位值 */
    private static final String POLL_PENDING = "PENDING";

    /** state / pollToken 的有效期（分钟） */
    private static final long TTL_MINUTES = 10;

    @Value("${gulimall.auth-url}")
    private String authUrl;

    @Value("${gulimall.shop-home-url}")
    private String shopHomeUrl;

    @Value("${oauth.wechat.app-id}")
    private String appId;

    @Value("${oauth.wechat.redirect-uri}")
    private String redirectUri;

    @Value("${oauth.wechat.scope}")
    private String scope;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private MemberFeignClient memberFeignClient;

    @Autowired
    private LoginRedirectService loginRedirectService;

    @Autowired
    private AgentJwtService agentJwtService;

    // ==================================================================
    // 一、非微信环境用：准备链接 + 轮询结果
    // ==================================================================

    /**
     * 为非微信浏览器准备一个"拿去微信里打开"的链接
     *
     * <p>登录页发现当前不是微信环境时会调这个接口，拿到链接后弹层给用户复制。
     *
     * @return {@code url} 给用户在微信里打开的链接，{@code token} 给前端轮询用
     */
    @ResponseBody
    @GetMapping("/oauth2/wechat/prepare")
    public R prepare() {

        String pollToken = UUID.randomUUID().toString().replace("-", "");

        // 先占个位，表示"这个 token 还没登录"。微信那边成功后会覆盖成会员信息
        stringRedisTemplate.opsForValue().set(
                POLL_CACHE_PREFIX + pollToken, POLL_PENDING, TTL_MINUTES, TimeUnit.MINUTES);

        // 注意这里带的是 poll 参数，不是 state ——
        // state 是给"这一次授权"防 CSRF 用的，会由 wechat() 现生成
        String url = authUrl + "/oauth2/wechat?poll=" + pollToken;

        return R.ok().put("token", pollToken).put("url", url);
    }

    /**
     * 轮询"微信那边登录好了没"
     *
     * <p>★ 这个接口是整个跨浏览器方案的关键：当它发现微信那边已经登录成功时，
     * <b>会把会员信息写进当前这次请求的 session</b> —— 也就是发起轮询的那个浏览器。
     * 微信内置浏览器里那份 session 属于微信，没法直接拿过来，只能这样重建一份。
     *
     * @param pollToken prepare 时发的那个 token
     * @param session 当前用户 Session
     * @param response 当前 HTTP 响应，用于写 agent JWT Cookie
     * @return {@code status}：PENDING 还没好 / SUCCESS 已登录（此时 session 已建立）
     */
    @ResponseBody
    @GetMapping("/oauth2/wechat/poll")
    public R poll(@RequestParam("token") String pollToken, HttpSession session,
                  HttpServletResponse response) {

        String json = stringRedisTemplate.opsForValue().get(POLL_CACHE_PREFIX + pollToken);

        // 还没登录，或者 token 已过期 —— 前端继续轮询即可
        if (!StringUtils.hasText(json) || POLL_PENDING.equals(json)) {
            return R.ok().put("status", "PENDING");
        }

        // 微信那边登录好了。把会员信息写进【当前浏览器】的 session ——
        // 这一步之后，当前浏览器的 cookie 就绑定了一份有效会话
        MemberResponseVo member = JSON.parseObject(json, MemberResponseVo.class);
        session.setAttribute(AuthServerConstant.LOGIN_USER, member);
        agentJwtService.issue(member, response);

        // 用完即删：同一个 token 只能被消费一次，防止别人拿到链接后重复换登录态
        stringRedisTemplate.delete(POLL_CACHE_PREFIX + pollToken);

        log.info("微信跨浏览器登录完成，已为轮询方建立会话：memberId={}", member.getId());

        return R.ok().put("status", "SUCCESS");
    }

    // ==================================================================
    // 二、微信内用：标准 H5 授权
    // ==================================================================

    /**
     * 发起微信授权
     *
     * @param pollToken 可选。非微信环境发起的登录会带上它，
     *                  这样授权成功后能把会员信息"送"回等待中的那个浏览器
     */
    @GetMapping("/oauth2/wechat")
    public String wechat(@RequestParam(value = "poll", required = false) String pollToken) {

        // state 防 CSRF，顺便把 pollToken 捎上 ——
        // 回调时通过 state 就能把 pollToken 找回来
        String state = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(
                STATE_CACHE_PREFIX + state,
                pollToken == null ? "" : pollToken,
                TTL_MINUTES, TimeUnit.MINUTES);

        String authorizeUrl = UriComponentsBuilder
                .fromUriString("https://open.weixin.qq.com/connect/oauth2/authorize")
                .queryParam("appid", appId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", scope)
                .queryParam("state", state)
                .build()
                .encode()
                // ⚠️ #wechat_redirect 必须拼在 encode 【之后】——
                //    写在 queryParam 里会被编码成 %23wechat_redirect，微信会报错
                .toUriString() + "#wechat_redirect";

        return "redirect:" + authorizeUrl;
    }

    /**
     * 微信授权回调
     *
     * @param code  微信发的一次性授权码（5 分钟有效）
     * @param state 上面发起时生成的随机串，用于校验请求来源；它的值里带着 pollToken
     * @param session 当前用户 Session
     * @param response 当前 HTTP 响应，用于写 agent JWT Cookie
     */
    @GetMapping("/oauth2/wechat/success")
    public String success(@RequestParam("code") String code,
                          @RequestParam(value = "state", required = false) String state,
                          HttpSession session,
                          HttpServletResponse response) {

        // 1、取出并删除 state。getAndDelete 是【原子】的：
        //    返回 null 就说明这个 state 根本不存在（被 CSRF 或已过期），
        //    返回非 null 说明确实是我发的，而且现在已经删掉了 —— 天然只能消费一次
        String pollToken = StringUtils.hasText(state)
                ? stringRedisTemplate.opsForValue().getAndDelete(STATE_CACHE_PREFIX + state)
                : null;

        if (pollToken == null) {
            log.warn("微信回调 state 校验失败（可能被 CSRF 或 state 已过期）：{}", state);
            return "redirect:" + authUrl + "/login.html";
        }

        // 2、把 code 交给 member。source=wechat 会让它去调 third-party 的微信接口
        R r = memberFeignClient.oauthLogin(code, "wechat");
        if (r.getCode() != 0) {
            log.warn("微信登录失败：{}", r.get("msg"));
            return "redirect:" + authUrl + "/login.html";
        }
        MemberResponseVo member = r.getData("data", new TypeReference<MemberResponseVo>() {
        });

        // 3、写当前会话（微信内置浏览器的会话）
        session.setAttribute(AuthServerConstant.LOGIN_USER, member);
        agentJwtService.issue(member, response);

        // 4、如果这次登录是从【另一个浏览器】发起的（带了 pollToken），
        //    就把会员信息放进 Redis，让那边轮询取走 ——
        //    浏览器之间 cookie 不共享，只能这样"隔空"把登录态递过去
        if (StringUtils.hasText(pollToken)) {
            stringRedisTemplate.opsForValue().set(
                    POLL_CACHE_PREFIX + pollToken,
                    JSON.toJSONString(member),
                    TTL_MINUTES, TimeUnit.MINUTES);
        }

        // 5、跳首页时带个标记，首页据此弹「登录成功，请返回浏览器查看」。
        //
        //    这个标记【只会出现在微信里的那次跳转】上 —— 浏览器那边是轮询到结果后
        //    自己 location.href 跳的，压根不经过这个方法，所以不会弹提示
        //    （浏览器本来就是登录成功了，不需要再提醒他"回浏览器看"）
        return loginRedirectService.afterLogin(member, shopHomeUrl + "?wechatLogin=1");
    }

}
