package com.atguigu.gulimall.thirdparty.controller;

import com.atguigu.gulimall.api.auth_server.SocialUser;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.thirdparty.dto.WechatTokenResponse;
import com.atguigu.gulimall.thirdparty.dto.WechatUserResponse;
import com.atguigu.gulimall.thirdparty.service.WechatOAuthInterface;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 微信网页授权 —— 第三方调用出口
 *
 * <p>和 {@link GithubOAuthController} 完全对称：把「code 换 token」「token 拉用户」
 * 这两次对微信的调用收拢在本模块，对外只暴露 {@code GET /oauth2/wechat/token?code=xxx}，
 * 由 member 通过 Feign 调用。
 *
 * <p>⚠️ 本接口服务于【微信内 H5 网页授权】（测试号可用）。
 * PC 扫码登录走的是另一个接口（{@code open.weixin.qq.com/connect/qrconnect}），
 * 那需要开放平台的「网站应用」资质，测试号调不通。
 */
@RestController
@RequestMapping("/oauth2/wechat")
@Slf4j
public class WechatOAuthController {

    @Value("${oauth.wechat.app-id}")
    private String appId;

    @Value("${oauth.wechat.app-secret}")
    private String appSecret;

    @Autowired
    private WechatOAuthInterface wechatOAuthInterface;

    /**
     * 用授权码换取社交用户信息
     *
     * @param code 微信回调时带回来的授权码（一次性、5 分钟有效）
     * @return 成功时 {@code data} 里是 {@link SocialUser}
     */
    @GetMapping("/token")
    public Mono<R> token(@RequestParam("code") String code) {

        return wechatOAuthInterface.exchangeToken(appId, appSecret, code, "authorization_code")
                .flatMap(token -> {

                    // 微信出错也是 HTTP 200，只能看 body 里 openid / access_token 在不在
                    if (!StringUtils.hasText(token.openid())
                            || !StringUtils.hasText(token.access_token())) {
                        log.error("微信换取 token 失败：errcode={}，errmsg={}",
                                token.errcode(), token.errmsg());
                        return Mono.just(R.error(BizCodeEnum.OAUTH_LOGIN_EXCEPTION.getCode(),
                                BizCodeEnum.OAUTH_LOGIN_EXCEPTION.getMsg()));
                    }

                    // 拿 access_token + openid 去拉昵称和头像
                    return wechatOAuthInterface
                            .getUserInfo(token.access_token(), token.openid(), "zh_CN")
                            .flatMap(user -> {
                                if (user.errcode() != null && user.errcode() != 0) {
                                    log.error("微信拉取用户信息失败：errcode={}，errmsg={}",
                                            user.errcode(), user.errmsg());
                                    return Mono.just(R.error(BizCodeEnum.OAUTH_LOGIN_EXCEPTION.getCode(),
                                            BizCodeEnum.OAUTH_LOGIN_EXCEPTION.getMsg()));
                                }
                                return Mono.just(R.ok().put("data", toSocialUser(token, user)));
                            });
                })
                .onErrorResume(e -> {
                    log.error("调用微信接口异常", e);
                    return Mono.just(R.error(BizCodeEnum.OAUTH_LOGIN_EXCEPTION.getCode(),
                            BizCodeEnum.OAUTH_LOGIN_EXCEPTION.getMsg()));
                });
    }

    /**
     * 把微信的两段响应组装成统一的社交用户对象
     */
    private SocialUser toSocialUser(WechatTokenResponse token, WechatUserResponse user) {
        SocialUser socialUser = new SocialUser();

        // uid 用 openid —— 微信没有 GitHub 那种数字 id，openid 就是唯一标识。
        // ⚠️ openid 只在【这一个公众号下】唯一；要跨小程序/App 识别同一个人得用 unionid
        socialUser.setUid(token.openid());

        socialUser.setAccess_token(token.access_token());
        socialUser.setExpires_in(token.expires_in() != null ? token.expires_in() : 0);

        // login：微信没有"用户名"这个概念，拿 openid 顶上。
        //        member 侧会用它生成会员的 username（超长会被截断，撞名会加随机后缀）
        socialUser.setLogin(token.openid());

        // 微信用户很多从没设过昵称，微信会给个默认的"微信用户"，这里再兜一层底
        socialUser.setNickname(StringUtils.hasText(user.nickname()) ? user.nickname() : "微信用户");
        socialUser.setHeader(user.headimgurl());
        // 微信这个接口不返回邮箱，留空即可
        socialUser.setEmail(null);

        return socialUser;
    }

}
