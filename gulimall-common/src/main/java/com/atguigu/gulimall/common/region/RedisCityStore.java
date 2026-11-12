package com.atguigu.gulimall.common.region;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Redis 城市存储：key 按访问者区分，TTL 30 天。
 *
 * <p>这个类只在引入 spring-data-redis 的模块里被加载，
 * 由 RedisCityAutoConfiguration 注册，没有 Redis 的模块不会碰到它。
 */
@Slf4j
public class RedisCityStore implements CityStore {

    private static final String KEY_PREFIX = "gulimall:city:";
    private static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;

    public RedisCityStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void save(String visitorId, String city) {
        if (redisTemplate == null || visitorId == null || city == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + visitorId, city, TTL);
        } catch (Exception e) {
            // Redis 挂了也不能影响页面，cookie 还有一份
            log.warn("[city] 城市写 Redis 失败：visitorId={}, city={}", visitorId, city, e);
        }
    }

    @Override
    public String get(String visitorId) {
        if (redisTemplate == null || visitorId == null) {
            return null;
        }
        try {
            return redisTemplate.opsForValue().get(KEY_PREFIX + visitorId);
        } catch (Exception e) {
            log.warn("[city] 城市读 Redis 失败：visitorId={}", visitorId, e);
            return null;
        }
    }
}
