package com.atguigu.gulimall.thirdparty.service;

import com.atguigu.gulimall.thirdparty.dto.WechatTokenResponse;
import com.atguigu.gulimall.thirdparty.dto.WechatUserResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import reactor.core.publisher.Mono;

/**
 * 微信网页授权（声明式 HTTP 接口 + WebClient）
 *
 * <p>两个接口都挂在 {@code api.weixin.qq.com} 下，所以共用一个 WebClient。
 *
 * <p>⚠️ <b>和 GitHub 最大的不同：这两个接口都是 GET，参数拼在 URL 上</b>。
 * GitHub 换 token 是 POST + 表单，别照着抄错了。
 */
public interface WechatOAuthInterface {

    /**
     * 用授权码换 access_token + openid
     *
     * @param appid       测试号的 appID
     * @param secret      测试号的 appsecret
     * @param code        微信回调带回来的一次性 code
     * @param grantType   固定写 {@code authorization_code}
     */
    @GetExchange(url = "/sns/oauth2/access_token")
    Mono<WechatTokenResponse> exchangeToken(@RequestParam("appid") String appid,
                                            @RequestParam("secret") String secret,
                                            @RequestParam("code") String code,
                                            @RequestParam("grant_type") String grantType);

    /**
     * 拉取用户信息（昵称、头像等）
     *
     * @param accessToken 上一步换到的 access_token
     * @param openid      上一步换到的 openid
     * @param lang        语言，固定 zh_CN
     */
    @GetExchange(url = "/sns/userinfo")
    Mono<WechatUserResponse> getUserInfo(@RequestParam("access_token") String accessToken,
                                         @RequestParam("openid") String openid,
                                         @RequestParam("lang") String lang);

}
