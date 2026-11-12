package com.atguigu.gulimall.agent.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 给所有 Feign 请求加内部调用头。
 *
 * <p>业务模块的 /internal/ai/** 只认 X-Internal-Token，
 * 这样即使外部有人猜到路径，没有这个头也会被 404。
 */
@Configuration
public class FeignInternalTokenConfig {

    /**
     * 创建给 Feign 请求补充内部调用头的拦截器。
     *
     * @param token 内部调用令牌，配置了才写入请求头
     * @return 负责添加 X-Internal-Token 头的 Feign 请求拦截器
     */
    @Bean
    public RequestInterceptor internalTokenInterceptor(@Value("${INTERNAL_API_TOKEN:}") String token) {
        return template -> {
            // 1. 配置了 token 才加头；没配置时业务侧会 fail closed
            if (StringUtils.hasText(token)) {
                template.header("X-Internal-Token", token);
            }
            // 2.InternalAiControllerSupport 会在服务端验证 token，没配置时直接拒绝，避免漏配后接口裸奔
        };
    }
}
