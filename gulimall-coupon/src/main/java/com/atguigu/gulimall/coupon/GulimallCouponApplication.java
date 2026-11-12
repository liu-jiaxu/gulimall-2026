package com.atguigu.gulimall.coupon;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

// 扫描mapper组件
@MapperScan("com.atguigu.gulimall.coupon.dao")
// Nacos开启服务注册发现功能
@EnableDiscoveryClient
// 关联秒杀商品时要调 ware 锁库存
@EnableFeignClients(basePackages = "com.atguigu.gulimall.coupon.feign")
// 秒杀结算兜底任务需要定时调度
@EnableScheduling
@SpringBootApplication
public class GulimallCouponApplication {

    public static void main(String[] args) {
        SpringApplication.run(GulimallCouponApplication.class, args);
    }

}
