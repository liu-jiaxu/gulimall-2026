package com.atguigu.gulimall.coupon.property;

import lombok.Data;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * gulimall 分布式组件版本配置类。
 */
@Data
@ConfigurationProperties(prefix = "gulimall")
public class GulimallComponentsProperties {

    /** 分布式组件清单 */
    private List<Component> components;

    /** 单个组件 */
    @Data
    public static class Component {

        /** 组件名称，如 Nacos Server */
        private String name;

        /** 组件角色，如 注册中心 + 配置中心 */
        private String role;

        /** 版本号，如 3.0.3 */
        private String version;

        /** 端口列表 */
        private List<Port> ports;

    }

    /** 端口信息 */
    @Data
    public static class Port {

        /** 端口号 */
        private int port;

        /** 用途说明 */
        private String description;

    }
}
