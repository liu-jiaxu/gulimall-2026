package com.atguigu.gulimall.product.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * @ClassName: FeignCookieConfig
 * @Package: com.atguigu.gulimall.product.config
 * @Description: Feign 调用时把浏览器带来的 Cookie 一起转发过去
 * @Author: 刘家旭
 * @Create: 2026/10/8
 * @Version: v1.0
 */
@Configuration
public class FeignCookieConfig {

    /**
     * 购物车是**按 cookie 区分"谁的购物车"**的：没登录用 user-key，登录了用会话 cookie。
     * <p>服务端发起的 feign 调用默认不带 Cookie，购物车服务每次都当成一个新游客，
     * 查到的永远是一个空购物车 —— 表现就是"购物车里有商品，页头却显示没有商品（0 件）"。
     * <p>这里把当前请求的 Cookie 原样带过去，两边说的就是同一个购物车了。
     */
    @Bean
    public RequestInterceptor cookieForwardInterceptor() {
        /*
          调用时机；远程调用发起时
            1.当前线程发起远程调用时，拦截器生效，拦截远程请求
            2.从当前线程中依次获取attributes -> request -> cookie（这里面包含当前线程的用户登录信息）
            3.将cookie设置到远程调用方的请求头中，其它远程模块就可以获取cookie信息
          调用异常：异步调用
            问题：例如CompletableFuture会开启子线程执行，子线程中没有主线程的attributes，因此无法设置cookie，请求异常
            解决：线程池ThreadPoolTaskExecutor加装饰器，将主线程的attributes设置到线程中
         */
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
