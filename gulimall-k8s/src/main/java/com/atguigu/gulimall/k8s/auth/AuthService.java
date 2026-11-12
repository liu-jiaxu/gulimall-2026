package com.atguigu.gulimall.k8s.auth;

import com.atguigu.gulimall.k8s.common.ApiException;
import com.atguigu.gulimall.k8s.config.AuthProperties;
import com.atguigu.gulimall.k8s.user.UserRepository;
import com.atguigu.gulimall.k8s.user.UserRow;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 注册、登录和改密。
 *
 * <p>这里没有 JWT、没有 Spring Security 过滤器链：
 * 注册/登录成功后只把用户放进 HttpSession，后续请求靠 JSESSIONID 识别。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;

    /**
     * 注册新用户；用户名已存在时返回 409。
     *
     * @param username    登录名
     * @param password    原始密码
     * @param displayName 页面展示名，可为空
     * @param inviteCode  邀请码，每次注册都必须输入
     * @return 注册成功的用户
     */
    public SessionUser register(String username, String password, String displayName, String inviteCode) {
        // 1. 先校验邀请码，没有邀请码不能注册
        verifyInviteCode(inviteCode);

        // 2. 校验用户名和密码格式，避免把脏数据写进库
        String name = normalizeUsername(username);
        validatePassword(password);
        String display = normalizeDisplayName(displayName, name);

        // 3. 用户名唯一；已存在就明确提示，不覆盖原用户
        if (userRepository.existsByUsername(name)) {
            throw new ApiException(409, "用户已存在");
        }

        // 4. BCrypt 加密后入库，数据库里不保存明文密码
        String hash = passwordEncoder.encode(password);
        long id = userRepository.insert(name, hash, display);
        return new SessionUser(id, name, display);
    }

    /**
     * 登录：先校验邀请码，再把用户名密码和数据库里的 BCrypt 哈希比对。
     *
     * @param username   登录名
     * @param password   原始密码
     * @param inviteCode 邀请码，每次登录都必须输入
     * @return 登录成功的用户
     */
    public SessionUser login(String username, String password, String inviteCode) {
        // 1. 每次登录都必须带正确邀请码
        verifyInviteCode(inviteCode);

        // 2. 用户名和密码都不能为空
        String name = normalizeUsername(username);
        if (password == null || password.isEmpty()) {
            throw new ApiException(400, "密码不能为空");
        }

        // 3. 用户不存在时提示先注册
        UserRow user = userRepository.findByUsername(name)
                .orElseThrow(() -> new ApiException(401, "用户不存在，请先注册"));

        // 4. 被禁用的账号不允许登录
        if (!user.enabled()) {
            throw new ApiException(403, "用户已被禁用");
        }

        // 5. BCrypt 比对失败就是密码错误
        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new ApiException(401, "密码错误");
        }

        // 6. 返回 Session 里要保存的最小用户信息
        return new SessionUser(user.id(), user.username(), user.displayName());
    }

    /**
     * 校验邀请码。
     *
     * <p>邀请码来自 application.yaml 的 gulimall.k8s.auth.invite-code，
     * 部署时用环境变量 INVITE_CODE 覆盖。
     *
     * @param inviteCode 请求里的邀请码
     */
    private void verifyInviteCode(String inviteCode) {
        // 1. 服务端没配置邀请码时直接报配置错误，不允许放行
        String configured = authProperties.getInviteCode();
        if (configured == null || configured.isBlank()) {
            throw new ApiException(500, "服务端未配置邀请码");
        }

        // 2. 没传邀请码时明确提示
        if (inviteCode == null || inviteCode.isBlank()) {
            throw new ApiException(403, "请输入邀请码");
        }

        // 3. 用常量时间比较，避免通过响应时间猜邀请码
        boolean matched = MessageDigest.isEqual(
                inviteCode.trim().getBytes(StandardCharsets.UTF_8),
                configured.trim().getBytes(StandardCharsets.UTF_8)
        );
        if (!matched) {
            throw new ApiException(403, "邀请码错误");
        }
    }

    /**
     * 忘记密码：按用户名直接改密码。
     *
     * <p>这是练习用的简化逻辑，没有短信/邮箱验证；
     * 任何知道用户名的人都能改密码，生产环境不能这样用。
     *
     * @param username    登录名
     * @param newPassword 新密码
     */
    public void resetPassword(String username, String newPassword) {
        // 1. 校验用户名和新密码格式
        String name = normalizeUsername(username);
        validatePassword(newPassword);

        // 2. 用户不存在时返回 404
        if (!userRepository.existsByUsername(name)) {
            throw new ApiException(404, "用户不存在");
        }

        // 3. 用新密码的 BCrypt 哈希覆盖旧哈希
        userRepository.updatePassword(name, passwordEncoder.encode(newPassword));
    }

    /**
     * 去掉首尾空格并校验用户名。
     *
     * @param username 原始用户名
     * @return 规范化后的用户名
     */
    private String normalizeUsername(String username) {
        // 1. 空值直接拒绝
        if (username == null || username.isBlank()) {
            throw new ApiException(400, "用户名不能为空");
        }

        // 2. 去掉首尾空格后长度必须在 3～32 之间
        String name = username.trim();
        if (name.length() < 3 || name.length() > 32) {
            throw new ApiException(400, "用户名长度必须在 3～32 个字符之间");
        }

        // 3. 不允许用户名里带空格，避免登录时前后空格造成困惑
        if (name.chars().anyMatch(Character::isWhitespace)) {
            throw new ApiException(400, "用户名不能包含空格");
        }
        return name;
    }

    /**
     * 校验密码长度。
     *
     * @param password 原始密码
     */
    private void validatePassword(String password) {
        // 1. 密码不能为空
        if (password == null || password.isEmpty()) {
            throw new ApiException(400, "密码不能为空");
        }

        // 2. 练习项目要求不高，6～64 位即可
        if (password.length() < 6 || password.length() > 64) {
            throw new ApiException(400, "密码长度必须在 6～64 个字符之间");
        }
    }

    /**
     * 规范化展示名，没填就用用户名。
     *
     * @param displayName 原始展示名
     * @param username    登录名
     * @return 规范化后的展示名
     */
    private String normalizeDisplayName(String displayName, String username) {
        // 1. 没填展示名时直接用用户名
        if (displayName == null || displayName.isBlank()) {
            return username;
        }

        // 2. 展示名最长 64 个字符，避免页面排版被撑坏
        String display = displayName.trim();
        if (display.length() > 64) {
            throw new ApiException(400, "展示名不能超过 64 个字符");
        }
        return display;
    }
}
