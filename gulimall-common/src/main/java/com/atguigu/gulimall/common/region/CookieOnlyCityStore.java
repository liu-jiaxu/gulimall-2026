package com.atguigu.gulimall.common.region;

/**
 * 没有 Redis 的模块使用的空实现。
 *
 * <p>城市仍然会写 30 天 cookie，页面层直接读 cookie，
 * 所以这些模块（coupon、third-party 等）不接 Redis 也能正常显示城市。
 */
public class CookieOnlyCityStore implements CityStore {

    @Override
    public void save(String visitorId, String city) {
        // cookie 由 CityController 负责写，这里不需要额外存储
    }

    @Override
    public String get(String visitorId) {
        return null;
    }
}
