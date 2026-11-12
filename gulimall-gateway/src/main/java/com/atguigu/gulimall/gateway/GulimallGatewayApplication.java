package com.atguigu.gulimall.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@EnableDiscoveryClient
// 引入common模块后，排除mybatis-plus的自动配置，否则会报错（因为网关没有数据库配置）
// pom 或 启动类 排除二选一即可
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class GulimallGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GulimallGatewayApplication.class, args);
    }

}
