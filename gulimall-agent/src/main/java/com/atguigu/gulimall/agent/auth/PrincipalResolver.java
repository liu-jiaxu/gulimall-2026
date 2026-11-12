package com.atguigu.gulimall.agent.auth;

import com.atguigu.gulimall.agent.auth.jwt.AgentJwtProperties;
import com.atguigu.gulimall.agent.auth.jwt.AgentJwtService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 从 agent 专用 JWT Cookie 中解析当前身份。
 *
 * <p>只看 Cookie 名称：
 * 有 AGENT_JWT_ADMIN 就是管理端，没有再看 AGENT_JWT_USER，两者都没有返回未登录。
 * 管理端 Cookie 只会在 admin.gulimall.vip 域名发送，用户端 Cookie 域是 .gulimall.vip，
 * 浏览器本身已经完成了第一层隔离。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrincipalResolver {

    private final AgentJwtProperties properties;
    private final AgentJwtService agentJwtService;

    /**
     * 只看 Cookie 名称解析身份。
     *
     * @param request 当前 HTTP 请求
     * @return 解析到的身份；两个 Cookie 都不存在时返回 null
     */
    public Principal resolve(HttpServletRequest request) {
        // 1. 有 AGENT_JWT_ADMIN 就按管理端验签
        String adminToken = readCookie(request, properties.getAdminCookie());
        log.debug("agent 身份解析 hasAdminCookie={} hasUserCookie={}",
                StringUtils.hasText(adminToken),
                StringUtils.hasText(readCookie(request, properties.getUserCookie())));
        if (StringUtils.hasText(adminToken)) {
            return agentJwtService.verify(adminToken);
        }

        // 2. 没有管理端 Cookie，再看用户端 Cookie
        String userToken = readCookie(request, properties.getUserCookie());
        if (StringUtils.hasText(userToken)) {
            return agentJwtService.verify(userToken);
        }

        // 3. 两个 Cookie 都没有，返回 null，由上层转成 15001
        return null;
    }

    /**
     * 从请求 Cookie 中读取指定名称的值。
     *
     * @param request 当前 HTTP 请求
     * @param name Cookie 名称
     * @return Cookie 值；不存在时返回 null
     */
    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
