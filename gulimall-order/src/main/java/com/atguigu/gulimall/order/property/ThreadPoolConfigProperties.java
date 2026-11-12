package com.atguigu.gulimall.order.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @ClassName: ThreadPoolConfigProperties
 * @Package: com.atguigu.gulimall.product.property
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/1 - 10:24
 * @Version: v1.0
 */
@ConfigurationProperties(prefix = "gulimall.threadpool")
@Data
public class ThreadPoolConfigProperties {

    private Integer corePoolSize;
    private Integer maxPoolSize;
    private Integer keepAliveTime;
    private String threadNamePrefix;

}
