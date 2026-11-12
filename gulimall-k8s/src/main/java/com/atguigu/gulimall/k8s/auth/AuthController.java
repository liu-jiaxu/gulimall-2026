package com.atguigu.gulimall.k8s.auth;

import com.atguigu.gulimall.k8s.common.ApiException;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录、注册、退出、当前用户和忘记密码接口。
 *
 * <p>登录/注册都必须输入邀请码；登录态最长 12 小时，绝对过期。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 注册新用户，成功后直接进入登录态。
     *
     * @param request 注册请求
     * @param session 当前 Session
     * @return 当前用户和登录时间
     */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterRequest request, HttpSession session) {
        // 1. 邀请码错误、用户名已存在等情况由 AuthService 抛业务异常
        SessionUser user = authService.register(
                request.username(),
                request.password(),
                request.displayName(),
                request.inviteCode()
        );

        // 2. 注册成功直接写 Session，前端不用再调一次登录
        saveSession(session, user);
        return authResponse(session, user);
    }

    /**
     * 登录，成功后写入 Session。
     *
     * @param request 登录请求
     * @param session 当前 Session
     * @return 当前用户和登录时间
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request, HttpSession session) {
        // 1. 每次登录都校验邀请码，再和数据库里的 BCrypt 哈希比对
        SessionUser user = authService.login(
                request.username(),
                request.password(),
                request.inviteCode()
        );

        // 2. 登录成功写 Session，后续请求靠 JSESSIONID 识别
        saveSession(session, user);
        return authResponse(session, user);
    }

    /**
     * 退出登录，销毁 Session。
     *
     * @param session 当前 Session
     * @return 固定成功响应
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        // 1. 清除服务端 Session；已经过期销毁时不再抛异常
        try {
            session.invalidate();
        } catch (IllegalStateException ignored) {
            // Session 已经被容器销毁，直接返回成功
        }
        return Map.of("ok", true);
    }

    /**
     * 查询当前登录用户和登录时间。
     *
     * @param session 当前 Session
     * @return 当前用户和登录时间
     */
    @GetMapping("/me")
    public Map<String, Object> me(HttpSession session) {
        // 1. find 内部会检查 12 小时绝对过期；过期时返回 null
        SessionUser user = SessionUser.find(session);
        if (user == null) {
            throw new ApiException(401, "请先登录或重新登录（登录状态最长 12 小时）");
        }
        return authResponse(session, user);
    }

    /**
     * 忘记密码：按用户名直接改密码。
     *
     * @param request 改密请求
     * @return 固定成功响应
     */
    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@RequestBody ResetPasswordRequest request) {
        // 1. 没有验证码/邮箱验证，直接改库；这是练习用的简化逻辑
        authService.resetPassword(request.username(), request.newPassword());
        return Map.of("ok", true);
    }

    /**
     * 把用户写进 Session，并设置 12 小时登录态。
     *
     * @param session 当前 Session
     * @param user    登录用户
     */
    private void saveSession(HttpSession session, SessionUser user) {
        // 1. 登录成功后覆盖可能存在的旧身份
        session.setAttribute(SessionUser.SESSION_KEY, user);

        // 2. 记录登录时间，SessionUser.find 用它做绝对 12 小时判断
        session.setAttribute(SessionUser.LOGIN_AT_KEY, System.currentTimeMillis());

        // 3. 同时设置容器的空闲过期时间，作为兜底
        session.setMaxInactiveInterval((int) SessionUser.SESSION_MAX_AGE.toSeconds());
    }

    /**
     * 组装登录/注册/当前用户的响应。
     *
     * @param session 当前 Session
     * @param user    当前用户
     * @return 用户和登录时间
     */
    private Map<String, Object> authResponse(HttpSession session, SessionUser user) {
        // 1. 前端用 loginAt 计算 12 小时剩余时间，过期后自动回登录页
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", user);
        body.put("loginAt", session.getAttribute(SessionUser.LOGIN_AT_KEY));
        return body;
    }

    /**
     * 注册请求体。
     *
     * @param username    登录名
     * @param password    密码
     * @param displayName 展示名，可为空
     * @param inviteCode  邀请码，必填
     */
    public record RegisterRequest(String username, String password, String displayName, String inviteCode) {
    }

    /**
     * 登录请求体。
     *
     * @param username   登录名
     * @param password   密码
     * @param inviteCode 邀请码，必填
     */
    public record LoginRequest(String username, String password, String inviteCode) {
    }

    /**
     * 改密请求体。
     *
     * @param username    登录名
     * @param newPassword 新密码
     */
    public record ResetPasswordRequest(String username, String newPassword) {
    }
}
