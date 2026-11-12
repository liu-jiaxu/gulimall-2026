package com.atguigu.gulimall.thirdparty.config;

import com.atguigu.gulimall.thirdparty.service.GiteeOAuthInterface;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Gitee 授权登录配置
 *
 * <p>背景：GitHub 在国内要挂梯子、不稳定，所以补一个 Gitee 渠道。
 * 两套<b>并存</b>，用户想用哪个点哪个 —— 它们在 auth-server 上是两个独立的入口，
 * 到 member 之后就合流了（都产出 SocialUser）。
 *
 * <p>Gitee 的三个接口（授权页 / 换 token / 取用户）都在 {@code gitee.com}
 * 一个域名下，所以只需要<b>一个</b> WebClient，
 * 不像 GitHub 那样要分 {@code github.com} 和 {@code api.github.com} 两个。
 */
@Configuration
public class GiteeOAuthConfiguration {

    @Bean
    public WebClient giteeWebClient(@Value("${oauth.gitee.base-url}") String baseUrl,
                                    ObjectMapper objectMapper) {

        // 顺手把 text/plain 也加进解码器的支持范围（微信那边踩过这个坑）。
        // Gitee 返回的是标准 application/json，多支持一种媒体类型没有坏处，
        // 免得哪天它改一下 Content-Type 又要排查半天
        Jackson2JsonDecoder decoder = new Jackson2JsonDecoder(
                objectMapper, MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN);

        return WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> {
                    c.defaultCodecs().jackson2JsonDecoder(decoder);
                    c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024);
                })
                .build();
    }

    @Bean
    public GiteeOAuthInterface giteeOAuthInterface(
            @Qualifier("giteeWebClient") WebClient giteeWebClient) {
        return HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(giteeWebClient))
                .build()
                .createClient(GiteeOAuthInterface.class);
    }

}
