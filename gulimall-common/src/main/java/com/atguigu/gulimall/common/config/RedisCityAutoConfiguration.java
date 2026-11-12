package com.atguigu.gulimall.common.config;

import com.atguigu.gulimall.common.region.CityStore;
import com.atguigu.gulimall.common.region.RedisCityStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 有 Redis 时才注册的 CityStore 实现。
 *
 * <p>@ConditionalOnClass 用【类名】而不是 Class 字面量，
 * 这样 coupon / third-party 这种 classpath 里根本没有 spring-data-redis 的模块，
 * 整个配置类不会被加载，也不会去解析 StringRedisTemplate 参数。
 *
 * <p>@AutoConfigureAfter 保证 RedisAutoConfiguration 已经创建了 StringRedisTemplate。
 * @AutoConfiguration(before = CityAutoConfiguration.class) 保证 Redis 实现先注册，
 * CityAutoConfiguration 里的 CookieOnlyCityStore 会因为 @ConditionalOnMissingBean 退让。
 */
@AutoConfiguration(before = CityAutoConfiguration.class)
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
@ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RedisCityAutoConfiguration {

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    public CityStore redisCityStore(StringRedisTemplate redisTemplate) {
        return new RedisCityStore(redisTemplate);
    }
}
