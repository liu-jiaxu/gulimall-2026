package com.atguigu.gulimall.thirdparty.service;

import com.atguigu.gulimall.thirdparty.dto.GithubTokenResponse;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import reactor.core.publisher.Mono;

/**
 * GitHub 换 token 接口（声明式 HTTP 接口 + WebClient）
 *
 * <p>对应 {@code POST https://github.com/login/oauth/access_token}
 * （baseUrl 由 {@code githubTokenWebClient} 提供）。
 *
 * <p>两个必须点：
 * <ul>
 *   <li>{@code contentType} 必须是 {@code application/x-www-form-urlencoded} —— 这是
 *       OAuth2 规范（RFC 6749）对 token 端点的要求，不是 JSON</li>
 *   <li>{@code accept} 必须声明 {@code application/json} —— 否则 GitHub 返回的是
 *       {@code access_token=xxx&scope=...} 这种 form 格式，JSON 反序列化会失败</li>
 * </ul>
 */
public interface GithubTokenInterface {

    @PostExchange(
            url = "/login/oauth/access_token",
            contentType = "application/x-www-form-urlencoded",
            accept = "application/json")
    Mono<GithubTokenResponse> exchangeToken(@RequestBody MultiValueMap<String, String> form);

}
