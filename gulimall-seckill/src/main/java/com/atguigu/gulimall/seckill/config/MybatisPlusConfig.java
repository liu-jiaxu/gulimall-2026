package com.atguigu.gulimall.seckill.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 秒杀模块 MyBatis-Plus 分页插件。
 *
 * <p>秒杀模块也有 MySQL 表；补上分页插件，后续加管理端列表时不用再踩坑。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        // 1. 创建拦截器链
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 2. 添加分页插件，指定 MySQL 方言
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        // 3. 把配好的拦截器链交给 Spring 管理，MyBatis-Plus 执行 SQL 时会自动套用
        return interceptor;
    }
}
