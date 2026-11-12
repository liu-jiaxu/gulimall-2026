package com.atguigu.gulimall.common.config;

import com.atguigu.gulimall.common.exception.ShiroExceptionHandler;
import com.atguigu.gulimall.common.filter.UserLoginFilter;
import jakarta.servlet.Filter;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shiro 权限配置：让 {@code @RequiresPermissions} 注解真正生效做权限拦截。
 * <p>
 * 通过配置 {@code gulimall.shiro.enabled=true} 在需要的模块开启（默认关闭，避免影响其它微服务）。
 * 用户身份从请求头 {@code X-User-Id} 解析（见 {@link UserLoginFilter}），权限从配置读取（轻量模拟）。
 * <p>
 * 本类位于 {@code com.atguigu.gulimall.common}（公共模块），各业务模块的 {@code @SpringBootApplication}
 * 默认扫描不到，因此通过 {@code META-INF/spring/...AutoConfiguration.imports} 注册为自动配置，
 * 由 {@code gulimall.shiro.enabled} 控制开关。
 *
 * @author liujiaxu
 */
@AutoConfiguration
@ConditionalOnProperty(name = "gulimall.shiro.enabled", havingValue = "true")
public class ShiroConfig {

    /**
     * 轻量 Realm：认证 + 授权
     */
    @Bean
    public Realm realm() {
        return new GulimallRealm();
    }

    /**
     * Web 安全管理器
     */
    @Bean
    public DefaultWebSecurityManager securityManager(Realm realm) {
        DefaultWebSecurityManager securityManager = new DefaultWebSecurityManager();
        securityManager.setRealm(realm);
        return securityManager;
    }

    /**
     * Shiro 过滤器：负责每个请求的 Subject 绑定，并执行过滤链。
     */
    @Bean
    public ShiroFilterFactoryBean shiroFilterFactoryBean(SecurityManager securityManager) {
        ShiroFilterFactoryBean shiroFilterFactoryBean = new ShiroFilterFactoryBean();
        shiroFilterFactoryBean.setSecurityManager(securityManager);

        // 自定义 filter：从请求头自动登录，绑定当前用户身份
        Map<String, Filter> filters = new LinkedHashMap<>();
        filters.put("userLogin", new UserLoginFilter());
        shiroFilterFactoryBean.setFilters(filters);

        // 过滤链：所有请求先经过 userLogin（自动绑定身份），整体不强制登录，
        // 是否拦截由方法上的 @RequiresPermissions 注解决定
        Map<String, String> filterChainDefinitionMap = new LinkedHashMap<>();
        filterChainDefinitionMap.put("/**", "userLogin");
        shiroFilterFactoryBean.setFilterChainDefinitionMap(filterChainDefinitionMap);

        return shiroFilterFactoryBean;
    }

    /**
     * 让 {@code @RequiresPermissions} 等 Shiro 注解生效（方法级拦截）
     */
    @Bean
    public AuthorizationAttributeSourceAdvisor authorizationAttributeSourceAdvisor(SecurityManager securityManager) {
        AuthorizationAttributeSourceAdvisor advisor = new AuthorizationAttributeSourceAdvisor();
        advisor.setSecurityManager(securityManager);
        return advisor;
    }

    /**
     * 权限异常统一处理（401/403）。通过 @Bean 注册，使各模块无需扫描 com.atguigu.gulimall.common.exception
     */
    @Bean
    public ShiroExceptionHandler shiroExceptionHandler() {
        return new ShiroExceptionHandler();
    }
}
