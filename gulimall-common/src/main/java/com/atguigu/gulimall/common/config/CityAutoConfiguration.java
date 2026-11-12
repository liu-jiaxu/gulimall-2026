package com.atguigu.gulimall.common.config;

import com.atguigu.gulimall.common.region.CityStore;
import com.atguigu.gulimall.common.region.CookieOnlyCityStore;
import com.atguigu.gulimall.common.web.CityController;
import com.atguigu.gulimall.common.web.RegionController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * 注册城市与省市区相关接口。
 *
 * <p>这里【不直接引用 Redis 类型】：
 * 有 Redis 的模块由 {@link RedisCityAutoConfiguration} 先注册 RedisCityStore；
 * coupon / third-party 这类没有 Redis 的模块，这里退化成 CookieOnlyCityStore，
 * 不会再出现 StringRedisTemplate ClassNotFoundException。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CityStore.class)
    public CityStore cookieOnlyCityStore() {
        return new CookieOnlyCityStore();
    }

    @Bean
    public CityController cityController() {
        return new CityController();
    }

    @Bean
    public RegionController regionController() {
        return new RegionController();
    }
}
