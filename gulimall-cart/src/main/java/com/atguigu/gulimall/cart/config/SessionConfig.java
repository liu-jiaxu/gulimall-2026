package com.atguigu.gulimall.cart.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Spring Session 的序列化配置
 *
 * <p>Spring Session 写进 Redis 的默认序列化器是 JDK 序列化：存出来是二进制，
 * 用 redis-cli 看是一堆乱码；而且被存的类一旦加/删字段，旧数据就可能反序列化失败。
 *
 * <p>换成 JSON 之后，Redis 里是明文可读的，排查登录态问题方便得多。
 */
@Configuration
public class SessionConfig {

    /**
     * ⚠️ Bean 名必须叫 {@code springSessionDefaultRedisSerializer}
     * —— Spring Session 是按这个名字去找序列化器的，改成别的名字不生效
     */
    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        // GenericJackson2JsonRedisSerializer 会自动在 JSON 里写入 @class 类型信息，
        // 所以取出来能还原成原来的对象（包括 MemberResponseVo）
        return new GenericJackson2JsonRedisSerializer();
    }

}
