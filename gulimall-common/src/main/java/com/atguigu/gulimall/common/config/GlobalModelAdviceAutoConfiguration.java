package com.atguigu.gulimall.common.config;

import com.atguigu.gulimall.common.advice.GlobalModelAdvice;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * {@link GlobalModelAdvice} 的注册入口。
 * <p>
 * 各业务模块的 @SpringBootApplication 默认扫不到 com.atguigu.gulimall.common 包，
 * 所以这里的 @ControllerAdvice 必须靠 AutoConfiguration.imports 注册进来才生效。
 * <p>
 * ⚠️ 条件注解必须加在【本类】上，不能只加在 GlobalModelAdvice 上：
 * 后者是通过 @Bean 注册的，@Conditional 加在普通类的类声明上不生效，
 * 于是 gulimall-gateway（WebFlux，classpath 里没有 servlet API）加载 GlobalModelAdvice 时
 * 会因为找不到 HttpServletRequest 而抛 NoClassDefFoundError，网关起不来。
 * 加在自动配置类上，整个配置在非 Servlet 环境里直接不生效，那个类根本不会被加载。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GlobalModelAdviceAutoConfiguration {

    @Bean
    public GlobalModelAdvice globalModelAdvice() {
        return new GlobalModelAdvice();
    }

}
