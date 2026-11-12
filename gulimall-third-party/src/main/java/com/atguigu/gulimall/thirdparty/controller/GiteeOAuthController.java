package com.atguigu.gulimall.thirdparty.controller;

import com.atguigu.gulimall.api.auth_server.SocialUser;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.thirdparty.dto.GiteeTokenResponse;
import com.atguigu.gulimall.thirdparty.dto.GiteeUserResponse;
import com.atguigu.gulimall.thirdparty.service.GiteeOAuthInterface;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Gitee 授权登录 —— 第三方调用出口
 *
 * <p>和 {@link GithubOAuthController} 完全对称，只是对接的是 Gitee
 * （国内直连，不用挂梯子）。对外暴露 {@code GET /oauth2/gitee/token?code=xxx}，
 * 由 member 通过 Feign 调用。
 *
 * <p>⚠️ <b>和 GitHub 的三个不同点</b>：
 * <ol>
 *   <li>换 token 的路径是 {@code /oauth/token}（GitHub 是 {@code /login/oauth/access_token}）</li>
 *   <li>换 token 的请求体是 <b>JSON</b>（GitHub 是 form 表单）</li>
 *   <li>取用户时令牌走 <b>query 参数</b> {@code ?access_token=xxx}
 *       （GitHub 放在 {@code Authorization: Bearer} 头里）</li>
 * </ol>
 */
@RestController
@RequestMapping("/oauth2/gitee")
@Slf4j
public class GiteeOAuthController {

    @Value("${oauth.gitee.client-id}")
    private String clientId;

    @Value("${oauth.gitee.client-secret}")
    private String clientSecret;

    @Value("${oauth.gitee.redirect-uri}")
    private String redirectUri;

    @Autowired
    private GiteeOAuthInterface giteeOAuthInterface;

    /**
     * 用授权码换取社交用户信息
     *
     * @param code Gitee 回调时带回来的授权码（一次性、短时效）
     * @return 成功时 {@code data} 里是 {@link SocialUser}
     */
    @GetMapping("/token")
    public Mono<R> token(@RequestParam("code") String code) {

        // Gitee 的换 token 接口收 JSON 体
        Map<String, String> body = new HashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("client_id", clientId);
        body.put("client_secret", clientSecret);
        body.put("redirect_uri", redirectUri);

        return giteeOAuthInterface.exchangeToken(body)
                .flatMap(token -> {

                    // 和 GitHub/微信一样：出错不一定改状态码，看 access_token 在不在
                    if (!StringUtils.hasText(token.access_token())) {
                        log.error("Gitee 换取 token 失败：error={}，description={}",
                                token.error(), token.error_description());
                        return Mono.just(R.error(BizCodeEnum.OAUTH_LOGIN_EXCEPTION.getCode(),
                                BizCodeEnum.OAUTH_LOGIN_EXCEPTION.getMsg()));
                    }

                    // 拿 access_token 取用户（Gitee 走 query 参数）
                    return giteeOAuthInterface.getUser(token.access_token())
                            .map(user -> R.ok().put("data", toSocialUser(user, token)));
                })
                .onErrorResume(e -> {
                    log.error("调用 Gitee 接口异常", e);
                    return Mono.just(R.error(BizCodeEnum.OAUTH_LOGIN_EXCEPTION.getCode(),
                            BizCodeEnum.OAUTH_LOGIN_EXCEPTION.getMsg()));
                });
    }

    /**
     * 把 Gitee 的两段响应组装成统一的社交用户对象
     */
    private SocialUser toSocialUser(GiteeUserResponse user, GiteeTokenResponse token) {
        SocialUser socialUser = new SocialUser();

        // Gitee 和 GitHub 一样有数字 id（稳定不变），拿它当 social_uid
        socialUser.setUid(String.valueOf(user.id()));

        socialUser.setAccess_token(token.access_token());
        socialUser.setExpires_in(token.expires_in() != null ? token.expires_in() : 0);

        socialUser.setLogin(user.login());
        socialUser.setNickname(StringUtils.hasText(user.name()) ? user.name() : user.login());
        socialUser.setHeader(user.avatar_url());
        socialUser.setEmail(user.email());

        return socialUser;
    }

}
