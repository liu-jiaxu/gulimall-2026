package com.atguigu.gulimall.product.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * @ClassName: MyRedisConfig
 * @Package: com.atguigu.gulimall.product.config
 * @Description: 二进制缓存值专用的 RedisTemplate
 * @Author: 刘家旭
 * @Create: 2026/9/22
 * @Version: v1.0
 */
@Configuration
public class MyRedisConfig {

    /**
     * 专门用来存取二进制缓存值的模板：key 走 String，value 走 byte[]。
     * <p>
     * 为什么需要它：GZIP 压缩后的缓存在 Redis 里是二进制数据。如果用
     * StringRedisTemplate（value 序列化器是 StringRedisSerializer，只认 UTF-8 文本），
     * 二进制会被编码转换损坏，所以必须先 Base64 编码再存 —— 而 Base64 会让体积
     * 膨胀约 33%。改用 byte[] 序列化器可以把 GZIP 结果原样写进去，省掉这层损耗。
     * <p>
     * 注意 bean 名不能叫 redisTemplate：Spring Boot 自动配置的
     * RedisTemplate&lt;Object,Object&gt; 标注了 @ConditionalOnMissingBean(name = "redisTemplate")，
     * 同名会把自动配置顶掉，影响其它依赖默认 RedisTemplate 的组件。
     */
    @Bean
    public RedisTemplate<String, byte[]> byteRedisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(RedisSerializer.byteArray());
        template.setHashValueSerializer(RedisSerializer.byteArray());
        template.afterPropertiesSet();
        return template;
    }
}
