package com.atguigu.gulimall.k8s.auth;

import com.atguigu.gulimall.k8s.common.ApiException;
import jakarta.servlet.http.HttpSession;

import java.io.Serializable;
import java.time.Duration;

/**
 * 登录后放进 HttpSession 的用户信息。
 *
 * <p>这里不使用 JWT 或自定义 token，登录成功后在服务端 Session 里记住用户；
 * 浏览器只持有 JSESSIONID，后续请求由 Spring MVC 自动关联 Session。
 *
 * <p>登录态是“绝对 12 小时”：从登录成功那一刻开始计时，
 * 即使一直有请求，超过 12 小时也必须重新登录。
 *
 * @param id          用户主键
 * @param username    登录名
 * @param displayName 页面展示名
 */
public record SessionUser(Long id, String username, String displayName) implements Serializable {

    /**
     * Session 里保存用户信息的 key
     */
    public static final String SESSION_KEY = "GULIMALL_K8S_LOGIN_USER";

    /**
     * Session 里保存登录时间戳的 key
     */
    public static final String LOGIN_AT_KEY = "GULIMALL_K8S_LOGIN_AT";

    /**
     * 登录态最长 12 小时，绝对过期，不随请求续期
     */
    public static final Duration SESSION_MAX_AGE = Duration.ofHours(12);

    /**
     * 从 Session 取当前用户；没登录或超过 12 小时时抛 401。
     *
     * @param session 当前 HTTP Session
     * @return 当前登录用户
     */
    public static SessionUser require(HttpSession session) {
        // 1. 统一走 find，find 内部已经处理 12 小时绝对过期
        SessionUser user = find(session);
        if (user == null) {
            throw new ApiException(401, "请先登录或重新登录（登录状态最长 12 小时）");
        }
        return user;
    }

    /**
     * 从 Session 取当前用户；没登录或过期时返回 null。
     *
     * @param session 当前 HTTP Session
     * @return 当前登录用户或 null
     */
    public static SessionUser find(HttpSession session) {
        // 1. 没有用户信息就是未登录
        Object value = session.getAttribute(SESSION_KEY);
        if (!(value instanceof SessionUser user)) {
            return null;
        }

        // 2. 超过 12 小时：销毁 Session，让前端回到登录页
        if (isExpired(session)) {
            invalidate(session);
            return null;
        }
        return user;
    }

    /**
     * 判断登录态是否超过 12 小时。
     *
     * @param session 当前 HTTP Session
     * @return 过期时为 true
     */
    private static boolean isExpired(HttpSession session) {
        // 1. 没有登录时间戳时补一个，兼容升级前已经存在的旧 Session
        Object value = session.getAttribute(LOGIN_AT_KEY);
        if (!(value instanceof Long loginAt)) {
            session.setAttribute(LOGIN_AT_KEY, System.currentTimeMillis());
            return false;
        }

        // 2. 从登录成功开始算绝对 12 小时，不随请求续期
        return System.currentTimeMillis() - loginAt > SESSION_MAX_AGE.toMillis();
    }

    /**
     * 销毁 Session；已经销毁时不抛异常。
     *
     * @param session 当前 HTTP Session
     */
    private static void invalidate(HttpSession session) {
        try {
            session.invalidate();
        } catch (IllegalStateException ignored) {
            // Session 已经被容器销毁时不用再处理
        }
    }
}
