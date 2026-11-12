package com.atguigu.gulimall.common.region;

/**
 * 当前城市的持久化存储。
 *
 * <p>实现有两种：
 * <ul>
 *   <li>{@link RedisCityStore}：引入了 spring-data-redis 的模块使用；</li>
 *   <li>{@link CookieOnlyCityStore}：没有 Redis 的模块使用，城市只靠 cookie。</li>
 * </ul>
 *
 * <p>页面渲染始终优先读 cookie，所以即使没有 Redis 也不影响使用。
 */
public interface CityStore {

    void save(String visitorId, String city);

    String get(String visitorId);
}
