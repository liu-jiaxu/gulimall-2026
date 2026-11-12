package com.atguigu.gulimall.search.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * @ClassName: FeignCookieConfig
 * @Package: com.atguigu.gulimall.search.config
 * @Description: Feign 调用时把浏览器带来的 Cookie 一起转发过去
 * @Author: 刘家旭
 * @Create: 2026/10/8
 * @Version: v1.0
 */
@Configuration
public class FeignCookieConfig {

    /**
     * 和 product 模块里那个一模一样：购物车按 cookie 区分"谁的购物车"，
     * 服务端 feign 不转发 Cookie 的话，查到的永远是空购物车（页头件数一直是 0）
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
