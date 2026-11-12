package com.atguigu.gulimall.product.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * @ClassName: MybatisPlusConfig
 * @Package: com.atguigu.gulimall.product.config
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/7 - 20:17
 * @Version: v1.0
 */
@Configuration
// 开启事务
@EnableTransactionManagement
@MapperScan("com.atguigu.gulimall.product.dao")
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        /*
          配置项	        默认值	                                说明
          maxLimit	    Long.MAX_VALUE (9223372036854775807)	单页最大限制，实际无限制
          overflow	    false	                                是否处理溢出（超过 maxLimit 时是否回退到 maxLimit）
          optimizeJoin	true	                                是否优化 join 分页（只 count 主表）
          dbType	    DbType.MYSQL	                        数据库类型，决定分页方言
          dialect	    根据 dbType 自动选择	                    具体方言实现
          PaginationInnerInterceptor可以手动set这些属性
         */
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

}
