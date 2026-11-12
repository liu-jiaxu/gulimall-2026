package com.atguigu.gulimall.ware.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置
 * <p>
 * 注意：{@code @MapperScan} 与 {@code @EnableTransactionManagement} 已经标在
 * {@code GulimallWareApplication} 上，这里不重复声明。
 * <p>
 * 分页插件必须显式注册，否则 {@code new Query<T>().getPage(params)} 拿到的 Page
 * 不会被拼进 SQL，等于查询全部数据。
 *
 * @author liujiaxu
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
