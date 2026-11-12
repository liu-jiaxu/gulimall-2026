package com.atguigu.gulimall.auth_server.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * auth-server 签发用户端 agent JWT 的配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "gulimall.agent.jwt")
public class AgentJwtProperties {

    /** JWT 签发方 */
    private String issuer = "gulimall-auth-server";

    /** JWT 接收方 */
    private String audience = "gulimall-agent";

    /** 用户端 JWT Cookie 名 */
    private String cookieName = "AGENT_JWT_USER";

    /** 用户端 JWT Cookie 域 */
    private String cookieDomain = ".gulimall.vip";

    /** 用户端 JWT Cookie 路径 */
    private String cookiePath = "/gulimall/agent/";

    /** Cookie 是否只允许 HTTPS */
    private boolean cookieSecure = true;

    /** JWT 有效期 */
    private Duration ttl = Duration.ofMinutes(30);

    /** JWT header 中的 kid */
    private String keyId = "user-2026-01";

    /** user 私钥 PEM 文件路径 */
    private String privateKeyPath = "../jwt/user/jwt_private.pem";
}
