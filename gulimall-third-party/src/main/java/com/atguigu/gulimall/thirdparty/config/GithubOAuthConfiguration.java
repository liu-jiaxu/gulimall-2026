package com.atguigu.gulimall.thirdparty.config;

import com.atguigu.gulimall.thirdparty.service.GithubTokenInterface;
import com.atguigu.gulimall.thirdparty.service.GithubUserInterface;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

/**
 * GitHub OAuth 远程调用配置
 *
 * <p>和 {@link SmsConfiguration} 同一套路（Spring 6 的 Http Interface + WebClient），
 * 区别是这里需要两个 baseUrl 不同的 WebClient：
 * <ul>
 *   <li>{@code github.com} —— 换 access_token</li>
 *   <li>{@code api.github.com} —— 取用户信息</li>
 * </ul>
 *
 * <p>⚠️ 这里故意不把 HttpServiceProxyFactory 注册成 Bean：
 * {@code SmsConfiguration} 已经有一个同类型的 Bean，再注册一个，
 * 别处按类型注入就会报 "expected single matching bean but found 2"。
 *
 * <p>⚠️ <b>代理</b>：Netty 既不读 Windows 系统代理，也不读 JVM 的
 * {@code -Dhttp.proxyHost}，必须像下面这样显式配置。所以要代理时必须在这里配。
 */
@Configuration
public class GithubOAuthConfiguration {

    /**
     * 访问 GitHub 用的代理
     *
     * <p>国内开发机通常必须走代理才连得上 github.com；生产服务器一般能直连，
     * 那时留空即可 —— 为空时下面的 builder 会走直连。
     */
    @Value("${oauth.github.proxy-host:}")
    private String proxyHost;

    @Value("${oauth.github.proxy-port:0}")
    private int proxyPort;

    /** 换 token 用：https://github.com */
    @Bean
    public WebClient githubTokenWebClient() {
        return baseBuilder("https://github.com").build();
    }

    /** 取用户信息用：https://api.github.com */
    @Bean
    public WebClient githubApiWebClient() {
        return baseBuilder("https://api.github.com")
                // GitHub API 强制要求 User-Agent，缺了直接 403。
                // 放在这里统一带上，免得每个声明式接口方法都要多写一个参数
                .defaultHeader(HttpHeaders.USER_AGENT, "gulimall-third-party")
                .build();
    }

    @Bean
    public GithubTokenInterface githubTokenInterface(
            @Qualifier("githubTokenWebClient") WebClient githubTokenWebClient) {
        return HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(githubTokenWebClient))
                .build()
                .createClient(GithubTokenInterface.class);
    }

    @Bean
    public GithubUserInterface githubUserInterface(
            @Qualifier("githubApiWebClient") WebClient githubApiWebClient) {
        return HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(githubApiWebClient))
                .build()
                .createClient(GithubUserInterface.class);
    }

    /**
     * 公共的 WebClient 构造：baseUrl + 缓冲区 + （可选）代理
     */
    private WebClient.Builder baseBuilder(String baseUrl) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024));

        if (StringUtils.hasText(proxyHost) && proxyPort > 0) {
            HttpClient httpClient = HttpClient.create()
                    .proxy(spec -> spec
                            .type(ProxyProvider.Proxy.HTTP)
                            .host(proxyHost)
                            .port(proxyPort));
            builder.clientConnector(new ReactorClientHttpConnector(httpClient));
        }

        return builder;
    }

}
