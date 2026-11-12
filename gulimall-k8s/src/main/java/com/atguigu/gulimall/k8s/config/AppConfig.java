package com.atguigu.gulimall.k8s.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 基础 Bean 配置。
 *
 * <p>这里只引入 spring-security-crypto 做密码哈希，
 * 不启用 Spring Security 的过滤器链和登录页。
 */
@Configuration
public class AppConfig {

    /**
     * 密码哈希器：注册和改密时加密，登录时校验。
     *
     * @return BCrypt 密码哈希器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
