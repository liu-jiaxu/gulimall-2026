package com.atguigu.gulimall.thirdparty.service;

import com.atguigu.gulimall.thirdparty.dto.GiteeTokenResponse;
import com.atguigu.gulimall.thirdparty.dto.GiteeUserResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Gitee 授权登录（声明式 HTTP 接口 + WebClient）
 *
 * <p>⚠️ <b>和 GitHub 的三个不同点</b>：
 * <ol>
 *   <li><b>换 token 的路径</b>是 {@code /oauth/token}，不是 GitHub 的 {@code /login/oauth/access_token}</li>
 *   <li><b>取用户的路径</b>是 {@code /api/v5/user} —— 注意它在<b>同一个域名</b> {@code gitee.com} 下，
 *       GitHub 那边是分开的 {@code api.github.com}，所以这里只需要一个 WebClient</li>
 *   <li><b>取用户时令牌走 query 参数</b> {@code ?access_token=xxx}，
 *       不像 GitHub 那样放在 {@code Authorization: Bearer} 头里</li>
 * </ol>
 */
public interface GiteeOAuthInterface {

    /**
     * 用授权码换 access_token
     *
     * <p>Gitee 收的是 <b>JSON 体</b>（GitHub 那边是 form 表单）
     *
     * @param body 需要 grant_type / code / client_id / client_secret / redirect_uri
     */
    @PostExchange(url = "/oauth/token", contentType = "application/json")
    Mono<GiteeTokenResponse> exchangeToken(@RequestBody Map<String, String> body);

    /**
     * 拉取用户信息
     *
     * @param accessToken 上一步换到的 access_token，走 query 参数传
     */
    @GetExchange(url = "/api/v5/user")
    Mono<GiteeUserResponse> getUser(@RequestParam("access_token") String accessToken);

}
