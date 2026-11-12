package com.atguigu.gulimall.order.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * @ClassName: FeignCookieConfig
 * @Package: com.atguigu.gulimall.order.config
 * @Description: Feign 调用时把浏览器带来的 Cookie 一起转发过去
 * @Author: 刘家旭
 * @Create: 2026/10/8
 * @Version: v1.0
 */
@Configuration
public class FeignCookieConfig {

    /**
     * 登录状态解释
     * 浏览器发送请求请求头会携带cookie，后端会收到登录信息，但是远程调用不会有请求头。
     * 因此order远程调用cart获取购物车商品时（getCurrentCartItems），购物车没有用户登录信息，无法查询，此时需要手动构建请求头和cookie
     */
    @Bean
    public RequestInterceptor cookieForwardInterceptor() {
        return (RequestTemplate template) -> {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return;
            }
            HttpServletRequest request = attributes.getRequest();
            String cookie = request.getHeader("Cookie");
            if (cookie != null && !cookie.isEmpty()) {
                template.header("Cookie", cookie);
            }
        };
    }

}
