package com.atguigu.gulimall.auth_server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class GulimallWebConfig implements WebMvcConfigurer {

    /**
     * 视图映射:发送一个请求，直接跳转到一个页面
     *
     * @param registry 视图控制器注册表
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
//        registry.addViewController("/login.html").setViewName("login");
//        registry.addViewController("/reg.html").setViewName("reg");
    }

    /**
     * 允许用户端页面跨子域调用 agent JWT 刷新接口，并携带 Session Cookie。
     *
     * @param registry CORS 注册表
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/agent/jwt/refresh")
                .allowedOriginPatterns("http://gulimall.vip", "http://*.gulimall.vip",
                        "https://gulimall.vip", "https://*.gulimall.vip")
                .allowedMethods("POST", "OPTIONS")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
