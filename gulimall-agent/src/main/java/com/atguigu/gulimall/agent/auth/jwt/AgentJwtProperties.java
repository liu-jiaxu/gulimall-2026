package com.atguigu.gulimall.agent.auth.jwt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * agent 专用 RS256 JWT 的校验配置。
 *
 * <p>两个签发方各自持有私钥，agent 只保存公钥；
 * JWT header 里的 kid 用来选择 user/admin 对应的公钥。
 */
@Data
@Component
@ConfigurationProperties(prefix = "gulimall.agent.jwt")
public class AgentJwtProperties {

    /** 用户端 JWT 签发方 */
    private String issuerUser = "gulimall-auth-server";

    /** 管理端 JWT 签发方 */
    private String issuerAdmin = "renren-fast";

    /** JWT 接收方，必须与签发时一致 */
    private String audience = "gulimall-agent";

    /** 用户端 JWT Cookie 名 */
    private String userCookie = "AGENT_JWT_USER";

    /** 管理端 JWT Cookie 名 */
    private String adminCookie = "AGENT_JWT_ADMIN";

    /** 用户端 JWT 公钥文件路径 */
    private String userPublicKeyPath = "../jwt/user/jwt_public.pem";

    /** 管理端 JWT 公钥文件路径 */
    private String adminPublicKeyPath = "../jwt/admin/jwt_public.pem";

    /** 用户端 JWT 的 kid */
    private String userKeyId = "user-2026-01";

    /** 管理端 JWT 的 kid */
    private String adminKeyId = "admin-2026-01";

    /** 允许的时钟偏差秒数 */
    private long clockSkewSeconds = 30;
}
