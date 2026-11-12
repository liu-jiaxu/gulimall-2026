package com.atguigu.gulimall.seckill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Spring Session 序列化配置：和 order/product 等模块保持一致，
 * 否则同一份 GULIMALLSESSION 在 seckill 里读不出来。
 */
@Configuration
public class SessionConfig {

    /**
     * Bean 名必须是 springSessionDefaultRedisSerializer，Spring Session 按名字找
     */
    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        // 1. 用 JSON 序列化 Session 数据，保证和其他模块写进 Redis 的会话格式一致、能互相读取
        return new GenericJackson2JsonRedisSerializer();
    }
}
