package com.atguigu.gulimall.thirdparty.config;

import com.atguigu.gulimall.thirdparty.service.SmsInterface;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * 短信服务（阿里云云市场）配置
 *
 * <p>用 Spring 6 的 Http Interface + WebClient 声明式调用，替代传统 Feign。
 */
@Configuration
public class SmsConfiguration {

    /**
     * 短信专用 WebClient
     */
    @Bean
    public WebClient smsWebClient(@Value("${aliyun.apis.sms.base-url}") String baseUrl,
                                  @Value("${aliyun.apis.sms.app-code}") String appCode) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                // 云市场网关的鉴权头：值必须是 "APPCODE <AppCode>"，其中 APPCODE 全大写
                .defaultHeader("Authorization", "APPCODE " + appCode)
                // 响应体超过默认缓冲会报错，这里放宽（短信响应很小，属于防御性设置）
                .codecs(c -> c.defaultCodecs().maxInMemorySize(256 * 1024 * 1024))
                .build();
    }

    @Bean
    public SmsInterface smsInterface(@Qualifier("smsWebClient") WebClient smsWebClient) {
        return HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(smsWebClient))
                .build()
                .createClient(SmsInterface.class);
    }

}
