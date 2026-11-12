package com.atguigu.gulimall.thirdparty.controller;

import com.atguigu.gulimall.api.auth_server.SocialUser;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.thirdparty.dto.GithubTokenResponse;
import com.atguigu.gulimall.thirdparty.dto.GithubUserResponse;
import com.atguigu.gulimall.thirdparty.service.GithubTokenInterface;
import com.atguigu.gulimall.thirdparty.service.GithubUserInterface;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * GitHub 社交登录 —— 第三方调用出口
 *
 * <p>把「拿 code 换 token」「拿 token 取用户」这两次对 GitHub 的调用
 * 收拢在本模块，其它服务不需要直连 GitHub。
 *
 * <p>对外只暴露一个接口：{@code GET /oauth2/github/token?code=xxx}，
 * 由 member 服务通过 Feign 调用。返回统一的 {@link SocialUser}。
 *
 * <p>⚠️ 和 {@code /sms/send} 一样，这个接口<b>不要直接暴露给前端</b>，
 * 正确链路是：浏览器 → auth-server → member → 本接口。
 */
@RestController
@RequestMapping("/oauth2/github")
@Slf4j
public class GithubOAuthController {

    @Value("${oauth.github.client-id}")
    private String clientId;

    @Value("${oauth.github.client-secret}")
    private String clientSecret;

    @Value("${oauth.github.redirect-uri}")
    private String redirectUri;

    @Autowired
    private GithubTokenInterface githubTokenInterface;

    @Autowired
    private GithubUserInterface githubUserInterface;

    /**
     * 用授权码换取社交用户信息
     *
     * @param code GitHub 回调时带回来的授权码（一次性、短时效）
     * @return 成功时 {@code data} 里是 {@link SocialUser}
     */
    @GetMapping("/token")
    public Mono<R> token(@RequestParam("code") String code) {

        // 换 token 的四个参数按 OAuth2 规范放在表单里
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("code", code);
        form.add("redirect_uri", redirectUri);

        return githubTokenInterface.exchangeToken(form)
                .flatMap(token -> {

                    // GitHub 的 token 接口成功/失败都返回 HTTP 200，只能看 body 里有没有 access_token
                    if (!StringUtils.hasText(token.access_token())) {
                        log.error("GitHub 换取 token 失败：error={}，description={}",
                                token.error(), token.error_description());
                        return Mono.just(R.error(BizCodeEnum.OAUTH_LOGIN_EXCEPTION.getCode(),
                                BizCodeEnum.OAUTH_LOGIN_EXCEPTION.getMsg()));
                    }

                    // 拿着 token 取用户。reactor 的 flatMap：上一步的异步结果出来后，
                    // 把下一步的异步任务"拍平"接到同一个流里，不需要阻塞等待
                    return githubUserInterface.getUser("Bearer " + token.access_token())
                            .map(user -> R.ok().put("data", toSocialUser(user, token)));
                })
                .onErrorResume(e -> {
                    // 网络不通 / GitHub 报错都会走到这里。转成正常响应体返回，
                    // 免得把一个 500 抛给调用方导致整条登录链路断掉
                    log.error("调用 GitHub 接口异常", e);
                    return Mono.just(R.error(BizCodeEnum.OAUTH_LOGIN_EXCEPTION.getCode(),
                            BizCodeEnum.OAUTH_LOGIN_EXCEPTION.getMsg()));
                });
    }

    /**
     * 把 GitHub 的两段响应组装成统一的社交用户对象
     */
    private SocialUser toSocialUser(GithubUserResponse user, GithubTokenResponse token) {
        SocialUser socialUser = new SocialUser();

        // 唯一标识用 id（数字），不要用 login —— 用户改名后 login 会变
        socialUser.setUid(String.valueOf(user.id()));
        socialUser.setAccess_token(token.access_token());
        // GitHub 的 token 接口不返回有效期，这里不猜，填 0
        socialUser.setExpires_in(0);
        socialUser.setLogin(user.login());
        // name 用户可能没设，回退到 login，保证会员昵称不为空
        socialUser.setNickname(StringUtils.hasText(user.name()) ? user.name() : user.login());
        socialUser.setHeader(user.avatar_url());
        socialUser.setEmail(user.email());

        return socialUser;
    }

}
