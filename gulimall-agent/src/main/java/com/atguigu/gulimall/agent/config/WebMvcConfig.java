package com.atguigu.gulimall.agent.config;

import com.atguigu.gulimall.agent.auth.PrincipalArgumentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web 层配置：
 * 1. 注册 Principal 参数解析器；
 * 2. 把 /agent/widget/** 映射到 classpath:/static/agent/widget/，宠物和聊天窗静态资源由本服务直接提供。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final PrincipalArgumentResolver principalArgumentResolver;

    /**
     * 注册 Principal 参数解析器，让 Controller 可直接声明 Principal 参数。
     *
     * @param resolvers Spring MVC 的参数解析器列表
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(principalArgumentResolver);
    }

    /**
     * 把 /agent/widget/** 映射到本服务提供的静态资源目录。
     *
     * @param registry 静态资源处理器注册表
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 1. 网关会把 /gulimall/agent/widget/** 去前缀成 /agent/widget/**，
        //    这里再落到 resources/static/agent/widget/ 下的文件
        // 2. cachePeriod=0：前端更新 JS/CSS 后浏览器必须回源校验，
        //    否则用户会一直用缓存的旧版，出现“登录后仍显示未登录”这类假故障
        registry.addResourceHandler("/agent/widget/**")
                .addResourceLocations("classpath:/static/agent/widget/")
                .setCachePeriod(0);
    }
}
