/**
 * Copyright (c) 2016-2019 人人开源 All rights reserved.
 *
 * https://www.renren.io
 *
 * 版权所有，侵权必究！
 */

package io.renren.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 跨域统一由网关（gulimall-gateway）的 globalcors 处理。
     * 若后端再配置 CORS，响应会带上两个 Access-Control-Allow-Origin 头，
     * 浏览器报 "header contains multiple values"。
     * 直连本服务(8080)调试需要 CORS 时可临时放开本方法。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 已迁移到网关层处理，此处不再注册
    }
}