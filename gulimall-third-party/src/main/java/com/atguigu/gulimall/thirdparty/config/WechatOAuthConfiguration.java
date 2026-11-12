package com.atguigu.gulimall.thirdparty.config;

import com.atguigu.gulimall.thirdparty.service.WechatOAuthInterface;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * 微信网页授权配置
 *
 * <p>和 {@link GithubOAuthConfiguration} 同一套路（Http Interface + WebClient），
 * 区别是微信的换 token 和拉用户信息<b>都在 {@code api.weixin.qq.com} 下</b>，
 * 所以只需要<b>一个</b> WebClient。
 *
 * <p>同样地，这里不把 HttpServiceProxyFactory 注册成 Bean
 * （{@code SmsConfiguration} 已经有一个同类型的了，重复注册会撞车）。
 */
@Configuration
public class WechatOAuthConfiguration {

    /**
     * @param objectMapper 用 Spring 容器里那个（带着 Boot 的各项 Jackson 配置），
     *                     而不是自己 new 一个
     */
    @Bean
    public WebClient wechatWebClient(ObjectMapper objectMapper) {

        // ⚠️⚠️ 微信的坑，别删：
        //
        //    sns/oauth2/access_token 和 sns/userinfo 这两个接口返回的
        //    Content-Type 是【text/plain】，但响应体是货真价实的 JSON。
        //
        //    而 WebClient 默认的 Jackson2JsonDecoder 只认 application/json，
        //    碰到 text/plain 会在【HTTP 200 成功】的前提下直接抛：
        //        UnsupportedMediaTypeException:
        //        Content type 'text/plain' not supported for bodyType=WechatTokenResponse
        //
        //    从日志上看很像是网络或参数错误，其实请求早就成功了。
        //    解决办法：把 text/plain 也塞进这个 decoder 支持的媒体类型里。
        Jackson2JsonDecoder decoder = new Jackson2JsonDecoder(
                objectMapper, MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN);

        return WebClient.builder()
                .baseUrl("https://api.weixin.qq.com")
                .codecs(c -> {
                    c.defaultCodecs().jackson2JsonDecoder(decoder);
                    c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024);
                })
                .build();
    }

    @Bean
    public WechatOAuthInterface wechatOAuthInterface(
            @Qualifier("wechatWebClient") WebClient wechatWebClient) {
        return HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(wechatWebClient))
                .build()
                .createClient(WechatOAuthInterface.class);
    }

}
