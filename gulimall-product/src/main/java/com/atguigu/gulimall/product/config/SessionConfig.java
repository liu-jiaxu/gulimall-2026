package com.atguigu.gulimall.product.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Spring Session 的序列化配置
 *
 * <p>Spring Session 写进 Redis 的默认序列化器是 JDK 序列化：存出来是二进制，
 * 用 redis-cli 看是一堆乱码；而且被存的类一旦加/删字段，旧数据就可能反序列化失败。
 * 换成 JSON 之后，Redis 里是明文可读的，排查登录态问题方便得多。
 *
 * <p>⚠️ <b>这个序列化器必须与 gulimall-auth-server 里的那份完全一致</b>。
 * auth-server 用 JSON 写入会话，product 却用 JDK 去读的话，会直接反序列化失败。
 * （两份配置是刻意复制的 —— 放进 common 会让不相关的模块也带上 session 依赖。）
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
        // 所以取出来能还原成原来的对象（包括 auth-server 存的 MemberResponseVo）
        return new GenericJackson2JsonRedisSerializer();
    }

}
