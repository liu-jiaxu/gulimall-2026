package com.atguigu.gulimall.thirdparty.service;

import com.atguigu.gulimall.thirdparty.dto.GithubUserResponse;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import reactor.core.publisher.Mono;

/**
 * GitHub 取用户信息接口（声明式 HTTP 接口 + WebClient）
 *
 * <p>对应 {@code GET https://api.github.com/user}
 * （baseUrl 由 {@code githubApiWebClient} 提供）。
 *
 * <p>{@code User-Agent} 由 WebClient 的 defaultHeader 统一带上 ——
 * GitHub API 强制要求这个头，缺了直接 403。
 */
public interface GithubUserInterface {

    /**
     * @param authorization {@code Bearer <access_token>}
     */
    @GetExchange(url = "/user")
    Mono<GithubUserResponse> getUser(@RequestHeader("Authorization") String authorization);

}
