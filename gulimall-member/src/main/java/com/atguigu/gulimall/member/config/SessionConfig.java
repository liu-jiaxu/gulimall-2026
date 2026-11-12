package com.atguigu.gulimall.member.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Spring Session 的序列化配置
 *
 * <p>和 order / cart 里那份保持一致。Spring Session 默认用 JDK 序列化：存进 Redis 是二进制乱码，
 * 而且被序列化的类一旦加/删字段，旧会话就可能反序列化失败。
 *
 * <p>换成 JSON 之后 Redis 里是明文，排查登录态问题方便得多；
 * 更重要的是<b>各模块必须用同一种序列化方式</b>，否则一个模块写进去的会话另一个模块读不出来。
 */
@Configuration
public class SessionConfig {

    /**
     * ⚠️ Bean 名必须叫 {@code springSessionDefaultRedisSerializer} ——
     * Spring Session 是按这个名字去找序列化器的，改成别的名字不生效
     */
    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        return new GenericJackson2JsonRedisSerializer();
    }

}
