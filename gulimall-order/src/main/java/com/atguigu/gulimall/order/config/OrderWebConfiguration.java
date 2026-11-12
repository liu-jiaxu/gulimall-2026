package com.atguigu.gulimall.order.config;

import com.atguigu.gulimall.order.interceptor.LoginUserInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @ClassName: OrderWebConfiguration
 * @Package: com.atguigu.gulimall.order.config
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/14 - 09:56
 * @Version: v1.0
 */
@Configuration
public class OrderWebConfiguration implements WebMvcConfigurer {

    @Autowired
    LoginUserInterceptor loginUserInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginUserInterceptor)
                .excludePathPatterns("/error",
                        "/confirm/js/**", "/confirm/css/**", "/confirm/img/**",
                        "/pay/css/**", "/pay/js/**", "/pay/img/**",
                        // 支付宝回调用的是服务器之间的请求，没有登录会话，必须放行
                        "/pay/notify", "/pay/return",
                        // 城市保存 / 省市区动态数据：未登录也要能用
                        "/city/save", "/region/**",
                        // ===== 后台管理 CRUD =====
                        // 这些接口由 renren-fast-vue 调用，后台有自己的登录态；
                        // 不能再套商城的 GULIMALLSESSION，否则后台页面会被 302 到商城登录页。
                        "/order/order/**", "/order/orderitem/**",
                        "/order/orderreturnapply/**", "/order/orderreturnreason/**",
                        "/order/ordersetting/**", "/order/orderoperatehistory/**",
                        "/order/paymentinfo/**", "/order/refundinfo/**",
                        // ===== 服务内部调用：seckill 消费建单消息时没有用户会话 =====
                        "/internal/**");
    }
}
