package com.atguigu.gulimall.k8s.auth;

import com.atguigu.gulimall.k8s.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证登录态 12 小时绝对过期逻辑。
 */
class SessionUserTest {

    /**
     * 1 小时的毫秒数
     */
    private static final long HOUR_MILLIS = Duration.ofHours(1).toMillis();

    /**
     * 刚登录的 Session 必须有效。
     */
    @Test
    void freshSessionIsValid() {
        // 1. 登录时间为当前时间
        MockHttpSession session = loginSession(0);

        // 2. 能取到用户
        SessionUser user = SessionUser.find(session);
        assertNotNull(user);
        assertEquals("admin", user.username());
    }

    /**
     * 超过 12 小时的 Session 必须失效。
     */
    @Test
    void sessionOlderThan12HoursIsExpired() {
        // 1. 登录时间设为 13 小时前
        MockHttpSession session = loginSession(13 * HOUR_MILLIS);

        // 2. find 返回 null，不能继续使用登录态
        assertNull(SessionUser.find(session));
    }

    /**
     * 超过 12 小时时 require 必须抛 401。
     */
    @Test
    void requireThrows401WhenExpired() {
        // 1. 登录时间设为 13 小时前
        MockHttpSession session = loginSession(13 * HOUR_MILLIS);

        // 2. require 抛 401，前端据此回登录页
        ApiException ex = assertThrows(ApiException.class, () -> SessionUser.require(session));
        assertEquals(401, ex.getStatus());
    }

    /**
     * 11 小时 59 分的 Session 仍然有效。
     */
    @Test
    void sessionJustUnder12HoursIsValid() {
        // 1. 登录时间设为 11 小时 59 分钟前
        long age = Duration.ofHours(12).toMillis() - Duration.ofMinutes(1).toMillis();
        MockHttpSession session = loginSession(age);

        // 2. 还没到 12 小时，登录态有效
        assertNotNull(SessionUser.find(session));
    }

    /**
     * 构造一个指定登录时间的 Session。
     *
     * @param ageMillis 登录时间距现在多少毫秒
     * @return 带用户和登录时间的 Session
     */
    private MockHttpSession loginSession(long ageMillis) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionUser.SESSION_KEY, new SessionUser(1L, "admin", "管理员"));
        session.setAttribute(SessionUser.LOGIN_AT_KEY, System.currentTimeMillis() - ageMillis);
        return session;
    }
}
