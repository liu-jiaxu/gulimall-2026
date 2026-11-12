package com.atguigu.gulimall.member.feign;

import com.atguigu.gulimall.common.utils.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 调用 gulimall-third-party:所有第三方（GitHub / 微信等）的远程调用出口
 *
 * <p>member 不直连 GitHub 或微信 —— 由 third-party 用 WebClient 去调，
 * 这里只负责把 code 递过去、把 {@code SocialUser} 拿回来。
 */
@FeignClient("gulimall-third-party")
public interface ThirdPartyFeignClient {

    /**
     * 用授权码换取社交用户信息（GitHub / 微信共用这一个入口）
     *
     * <p>路径里的 {@code source} 直接对应 third-party 那边的两个 Controller：
     * {@code /oauth2/github/token} 和 {@code /oauth2/wechat/token}。
     * 两个渠道换到的用户信息都统一封装成 {@code SocialUser}，member 侧无需区分。
     *
     * @param source 渠道标识：github / wechat
     * @param code   社交平台回调带回来的授权码（一次性）
     * @return 成功时 {@code data} 里是 {@code SocialUser}
     */
    @GetMapping("/oauth2/{source}/token")
    R oauthToken(@PathVariable("source") String source, @RequestParam("code") String code);

}
