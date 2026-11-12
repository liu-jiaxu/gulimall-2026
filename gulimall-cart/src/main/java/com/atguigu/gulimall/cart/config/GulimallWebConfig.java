package com.atguigu.gulimall.cart.config;

import com.atguigu.gulimall.cart.interceptor.CartInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class GulimallWebConfig implements WebMvcConfigurer {

    @Value("${server.servlet.session.cookie.domain}")
    private String sessionCookieDomain;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CartInterceptor(sessionCookieDomain))//注册拦截器
                .addPathPatterns("/**")
                .excludePathPatterns("/cart/js/**", "/cart/css/**", "/cart/img/**", "/cart/bootstrap/**");
    }
}
