package com.atguigu.gulimall.agent.auth;

/**
 * AI 请求身份类型。
 *
 * <p>USER / ADMIN 由 agent JWT 的 role 声明映射：
 * role=user 对应用户端，role=admin 对应管理端。
 */
public enum PrincipalType {
    USER,
    ADMIN
}
