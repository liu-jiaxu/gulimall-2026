package com.atguigu.gulimall.common.filter;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang.StringUtils;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.filter.AccessControlFilter;

/**
 * 从请求头 {@code X-User-Id} 自动登录，绑定当前请求的 Subject。
 * <p>
 * 这样 {@code @RequiresPermissions} 注解能拿到认证身份并做权限校验：
 * <ul>
 *     <li>携带了 {@code X-User-Id}：自动登录成功，按配置校验权限；</li>
 *     <li>未携带：保持匿名，访问受权限注解保护的接口会被拦截（401）。</li>
 * </ul>
 * 本 filter 始终放行，是否拦截由方法上的 {@code @RequiresPermissions} 注解决定。
 *
 * @author liujiaxu
 */
public class UserLoginFilter extends AccessControlFilter {

    /**
     * 用户身份请求头名称（网关 / 上游服务透传）
     */
    public static final String USER_ID_HEADER = "X-User-Id";

    @Override
    protected boolean isAccessAllowed(ServletRequest request, ServletResponse response, Object mappedValue) throws Exception {
        Subject subject = SecurityUtils.getSubject();
        if (!subject.isAuthenticated()) {
            String userId = null;
            if (request instanceof HttpServletRequest) {
                userId = ((HttpServletRequest) request).getHeader(USER_ID_HEADER);
            }
            if (StringUtils.isNotBlank(userId)) {
                try {
                    subject.login(new UsernamePasswordToken(userId, ""));
                } catch (AuthenticationException e) {
                    // 登录失败保持匿名，由注解拦截兜底
                }
            }
        }
        // 始终放行
        return true;
    }

    /**
     * isAccessAllowed 恒放行，此方法不会被调用，仅满足抽象方法要求
     */
    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) {
        return false;
    }
}
