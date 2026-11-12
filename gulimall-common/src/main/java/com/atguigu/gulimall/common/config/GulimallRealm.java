package com.atguigu.gulimall.common.config;

import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 轻量 Realm：先跑通注解拦截，再接入真实权限数据。
 * <p>
 * 认证：用户身份（principal）即请求头解析出的 userId（由 {@code UserLoginFilter} 自动登录），不校验密码。
 * 授权：权限从配置读取——
 * <ul>
 *     <li>{@code gulimall.shiro.permissions}：逗号分隔的权限字符串列表；</li>
 *     <li>{@code gulimall.shiro.permission-all=true}：开发期放行所有权限。</li>
 * </ul>
 *
 * @author liujiaxu
 */
public class GulimallRealm extends AuthorizingRealm {

    /**
     * 配置的权限列表（逗号分隔），如 {@code product:brand:list,product:brand:save}
     */
    @Value("${gulimall.shiro.permissions:}")
    private String permissions;

    /**
     * 开发期是否放行所有权限（true 时授权返回 {@code *}）
     */
    @Value("${gulimall.shiro.permission-all:false}")
    private boolean permissionAll;

    /**
     * 授权：返回当前用户的权限集合
     */
    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
        if (permissionAll) {
            // 开发期放行所有权限
            info.addStringPermission("*");
            return info;
        }
        Set<String> perms = new HashSet<>();
        if (permissions != null && !permissions.trim().isEmpty()) {
            Arrays.stream(permissions.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(perms::add);
        }
        info.addStringPermissions(perms);
        return info;
    }

    /**
     * 认证：principal 即 userId，不校验密码（轻量模拟）
     */
    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) {
        String userId = (String) token.getPrincipal();
        return new SimpleAuthenticationInfo(userId, "", getName());
    }
}
