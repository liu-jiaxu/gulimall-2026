package com.atguigu.gulimall.agent.auth;

/**
 * 当前 AI 请求的身份。
 *
 * <p>工具、提示词、会话 key 全部以它为准，避免把别人的订单/地址查出来。
 *
 * @param type        USER / ADMIN
 * @param id          会员 id 或管理员 id
 * @param username    登录名
 * @param displayName 页面展示名
 */
public record Principal(PrincipalType type, Long id, String username, String displayName) {

    /**
     * 会话/记忆使用的稳定标识，例如 USER:1 或 ADMIN:1。
     *
     * @return 由身份类型和 id 拼接而成的唯一标识
     */
    public String subject() {
        return type.name() + ":" + id;
    }

    /**
     * 判断当前身份是否来自管理端。
     *
     * @return 身份类型为 ADMIN 时返回 true，否则返回 false
     */
    public boolean isAdmin() {
        return type == PrincipalType.ADMIN;
    }
}
