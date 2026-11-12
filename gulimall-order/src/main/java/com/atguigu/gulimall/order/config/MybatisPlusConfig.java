package com.atguigu.gulimall.order.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 订单模块 MyBatis-Plus 分页插件。
 *
 * <p>没有这个 Bean 时，Service 里的 {@code this.page(new Page<>(), wrapper)}
 * 不会拼接 LIMIT，也不会正确计算 total，管理端订单列表就没有分页效果。
 * 与 product/ware 模块的配置保持一致。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        // 1. 创建 MyBatis-Plus 拦截器链
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 2. 添加分页插件，指定 MySQL 方言
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        return interceptor;
    }
}
