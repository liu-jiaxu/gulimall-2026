package com.atguigu.gulimall.seckill.config;

import com.atguigu.gulimall.seckill.interceptor.LoginUserInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 秒杀模块 MVC 配置。
 *
 * <p>只有抢购和结果查询需要登录；场次/商品查询、上架刷新、内部接口放行。
 */
@Configuration
public class SeckillWebConfiguration implements WebMvcConfigurer {

    @Autowired
    private LoginUserInterceptor loginUserInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 注册登录拦截器，只放行游客可看页面、静态资源和内部接口，其余请求进来先校验登录
        registry.addInterceptor(loginUserInterceptor)
                .excludePathPatterns(
                        "/error",
                        // 页面：游客可看，确认页自己检查登录态
                        "/", "/seckill.html", "/confirm.html",
                        // 游客也能看的场次、商品、首页秒杀区
                        "/seckill/sessions", "/seckill/skus",
                        "/seckill/home", "/seckill/sku/info",
                        // 运维/测试接口
                        "/seckill/upload", "/seckill/refresh",
                        // 静态资源
                        "/css/**", "/js/**", "/img/**", "/favicon.ico",
                        // 服务之间调用，不带用户会话
                        "/internal/**");
    }
}
