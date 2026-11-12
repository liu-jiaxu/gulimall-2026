package com.atguigu.gulimall.k8s.user;

/**
 * ai_user 表的一行数据。
 *
 * @param id           用户主键
 * @param username     登录名
 * @param passwordHash BCrypt 密码哈希
 * @param displayName  页面展示名
 * @param enabled      是否启用
 */
public record UserRow(Long id, String username, String passwordHash, String displayName, boolean enabled) {
}
