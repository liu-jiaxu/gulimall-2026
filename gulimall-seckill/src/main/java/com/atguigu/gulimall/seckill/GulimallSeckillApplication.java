package com.atguigu.gulimall.seckill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 秒杀服务全流程
 *   秒杀准备
 *     1.添加秒杀活动，关联sku
 *       需要判断库存，不够失败，够了锁库存，同时锁库存数量进redis，同时加定时任务兜底
 *     2.秒杀活动开始
 *       用户正常下单支付，库存扣减redis
 *     3.秒杀结束
 *       活动到期结束，redis剩余库存真实扣减stock，删redis的key
 *
 */
@EnableFeignClients
@EnableDiscoveryClient
@EnableScheduling
@MapperScan("com.atguigu.gulimall.seckill.dao")
@SpringBootApplication
public class GulimallSeckillApplication {

    public static void main(String[] args) {
        // 1. 启动 Spring Boot 应用，加载秒杀模块的配置、Feign 客户端和定时任务
        SpringApplication.run(GulimallSeckillApplication.class, args);
    }
}
