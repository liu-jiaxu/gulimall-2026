package com.atguigu.gulimall.coupon.bean;

import com.atguigu.gulimall.coupon.property.GulimallComponentsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 {@link GulimallComponentsProperties} 配置绑定。
 * <p>
 * 直接 {@code @Autowired} 注入 {@link GulimallComponentsProperties} 即可读取版本信息。
 *
 * @author liujiaxu
 */
@Configuration
@EnableConfigurationProperties(GulimallComponentsProperties.class)
public class GulimallPropertiesAutoConfiguration {
}
