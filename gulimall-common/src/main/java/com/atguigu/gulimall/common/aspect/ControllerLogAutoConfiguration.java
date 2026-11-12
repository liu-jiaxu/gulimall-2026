package com.atguigu.gulimall.common.aspect;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Controller 日志切面自动配置
 * <p>
 * 通过 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} 注册，
 * 使 {@link ControllerLogAspect} 在所有业务微服务中生效（业务模块 {@code @SpringBootApplication}
 * 默认扫不到 {@code com.atguigu.gulimall.common} 包，故不能用 @Component）。
 * <p>
 * 可通过 {@code gulimall.controller-log.enabled=false} 关闭（默认开启）。
 *
 * @author liujiaxu
 */
@AutoConfiguration
@ConditionalOnProperty(name = "gulimall.controller-log.enabled", havingValue = "true", matchIfMissing = true)
public class ControllerLogAutoConfiguration {

    /**
     * 注册 Controller 日志切面
     */
    @Bean
    public ControllerLogAspect controllerLogAspect() {
        return new ControllerLogAspect();
    }
}
