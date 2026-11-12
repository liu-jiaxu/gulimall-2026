package com.atguigu.gulimall.thirdparty.config;

import com.atguigu.gulimall.thirdparty.service.BaiduMapInterface;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * 百度地图（物流距离计算）配置
 *
 * <p>地理编码和批量算路两个接口都在 {@code api.map.baidu.com} 一个域名下，
 * 所以只需要<b>一个</b> WebClient（跟 Gitee 那边的情况一样，不像 GitHub 要分两个域名）。
 */
@Configuration
public class BaiduMapConfiguration {

    @Bean
    public WebClient baiduMapWebClient(@Value("${baidu.map.base-url}") String baseUrl,
                                       @Value("${baidu.map.timeout-ms:3000}") int timeoutMs,
                                       ObjectMapper objectMapper) {

        // ⚠️ 必须用 Boot 的 ObjectMapper（也就是上面这个入参）：WebClient.builder() 默认新建的那个
        //    开着 FAIL_ON_UNKNOWN_PROPERTIES，而百度返回里的 message / level / comprehension
        //    等我们没声明的字段会让反序列化直接抛 UnrecognizedPropertyException。
        //    本项目的 DTO 一律不加注解，靠的就是 Boot 这个 ObjectMapper 已经关掉了该特性
        //    （GiteeOAuthConfiguration 显式传 ObjectMapper 也是同一个原因）。
        //
        //    顺带把 text/plain 也加进解码器支持范围：百度出错时 Content-Type 可能是它，
        //    免得因为媒体类型对不上而解析失败（微信那边踩过这个坑）
        // ⚠️ 百度地理编码接口回的 Content-Type 是 text/javascript;charset=utf-8（不是 application/json！），
        //    因为它也支持 JSONP。只认 application/json 的解码器会抛 UnsupportedMediaTypeException，
        //    还被包在 WebClientResponseException 里、外层挂着 "200 OK"，看着像响应异常，其实是类型不匹配 ——
        //    用 curl 试同一个接口是好的，差别就在这里（curl 不看 Content-Type）。
        //    这里把百度可能用到的几种类型都放进去（text/plain 是它出错时用的）
        Jackson2JsonDecoder decoder = new Jackson2JsonDecoder(
                objectMapper,
                MediaType.APPLICATION_JSON,
                MediaType.TEXT_PLAIN,
                MediaType.valueOf("text/javascript"));

        // 超时：本模块是 Servlet 栈，WebClient 的慢调用会一直占着业务线程不放。
        // common.yaml 里那段 openfeign 超时只作用于 Feign，管不到 WebClient，所以这里单独配
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> {
                    c.defaultCodecs().jackson2JsonDecoder(decoder);
                    c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024);
                })
                .build();
    }

    /**
     * ⚠️ 不要把 HttpServiceProxyFactory 注册成 Bean —— {@code SmsConfiguration} 里已经有一个
     * 同类型的，再注册一个会按类型注入时冲突
     */
    @Bean
    public BaiduMapInterface baiduMapInterface(
            @Qualifier("baiduMapWebClient") WebClient baiduMapWebClient) {
        return HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(baiduMapWebClient))
                .build()
                .createClient(BaiduMapInterface.class);
    }

}
