package com.atguigu.gulimall.agent.auth.jwt;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.auth.PrincipalType;
import com.atguigu.gulimall.agent.exception.AgentErrorCode;
import com.atguigu.gulimall.agent.exception.AgentException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * agent 专用 JWT 校验服务。
 *
 * <p>只校验 auth-server 和 renren-fast 用 RSA 私钥签发的 JWT，
 * 不签发、不存储 JWT，也不访问数据库或 Redis。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentJwtService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ADMIN = "admin";

    private final AgentJwtProperties properties;
    private final ObjectMapper objectMapper;

    /** kid -> 公钥及该密钥允许的签发方、角色 */
    private final Map<String, KeyEntry> keys = new LinkedHashMap<>();

    /**
     * 启动时加载用户端和管理端公钥，缺少公钥时直接失败，避免带病运行。
     */
    @PostConstruct
    public void loadKeys() {
        keys.put(properties.getUserKeyId(), new KeyEntry(
                loadPublicKey(properties.getUserPublicKeyPath()),
                properties.getIssuerUser(),
                ROLE_USER));
        keys.put(properties.getAdminKeyId(), new KeyEntry(
                loadPublicKey(properties.getAdminPublicKeyPath()),
                properties.getIssuerAdmin(),
                ROLE_ADMIN));
        log.info("agent JWT 公钥加载完成 userKid={} adminKid={}",
                properties.getUserKeyId(), properties.getAdminKeyId());
    }

    /**
     * 校验 JWT 并转换为当前请求身份。
     *
     * @param token 浏览器 Cookie 中的 agent JWT
     * @return 解析成功后的用户或管理员身份
     */
    public Principal verify(String token) {
        try {
            String kid = readKeyId(token);
            KeyEntry entry = keys.get(kid);
            if (entry == null) {
                throw new AgentException(AgentErrorCode.AGENT_TOKEN_INVALID, "登录凭证密钥不匹配");
            }

            Claims claims = Jwts.parser()
                    .verifyWith(entry.key())
                    .clockSkewSeconds(properties.getClockSkewSeconds())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // 1. 签发方、接收方和 kid 必须互相对应，避免 user/admin 凭证混用
            if (!entry.issuer().equals(claims.getIssuer())) {
                throw new AgentException(AgentErrorCode.AGENT_TOKEN_INVALID, "登录凭证签发方不匹配");
            }
            if (claims.getAudience() == null
                    || !claims.getAudience().contains(properties.getAudience())) {
                throw new AgentException(AgentErrorCode.AGENT_TOKEN_INVALID, "登录凭证接收方不匹配");
            }

            // 2. 角色必须与签发密钥匹配
            String role = claims.get("role", String.class);
            if (!entry.role().equals(role)) {
                throw new AgentException(AgentErrorCode.AGENT_TOKEN_INVALID, "登录凭证角色不匹配");
            }

            // 3. sub 是会员 id 或管理员 id，username 用于展示
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                throw new AgentException(AgentErrorCode.AGENT_TOKEN_INVALID, "登录凭证缺少用户 id");
            }
            long id = Long.parseLong(subject);
            String username = claims.get("username", String.class);
            if (username == null || username.isBlank()) {
                username = subject;
            }

            PrincipalType type = ROLE_ADMIN.equals(role) ? PrincipalType.ADMIN : PrincipalType.USER;
            return new Principal(type, id, username, username);
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("agent JWT 校验失败：{}", ex.getMessage());
            throw new AgentException(AgentErrorCode.AGENT_TOKEN_INVALID,
                    "登录凭证无效或已过期，请重新登录", ex);
        }
    }

    /**
     * 读取 JWT header 中的 kid。
     *
     * @param token 待校验的 JWT
     * @return JWT header 中的 kid
     */
    private String readKeyId(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) {
            throw new AgentException(AgentErrorCode.AGENT_TOKEN_INVALID, "登录凭证格式不正确");
        }
        try {
            byte[] header = Base64.getUrlDecoder().decode(parts[0]);
            JsonNode node = objectMapper.readTree(header);
            String kid = node.path("kid").asText("");
            if (kid.isBlank()) {
                throw new AgentException(AgentErrorCode.AGENT_TOKEN_INVALID, "登录凭证缺少 kid");
            }
            return kid;
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(AgentErrorCode.AGENT_TOKEN_INVALID, "登录凭证 header 解析失败", ex);
        }
    }

    /**
     * 从 PEM 文件加载 RSA 公钥。
     *
     * @param path PEM 公钥文件路径
     * @return RSA 公钥
     */
    private PublicKey loadPublicKey(String path) {
        try {
            String pem = Files.readString(Path.of(path), StandardCharsets.UTF_8)
                    .replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "")
                    .replaceAll("\\s", "");
            byte[] encoded = Base64.getDecoder().decode(pem);
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception ex) {
            throw new IllegalStateException("加载 agent JWT 公钥失败：" + path, ex);
        }
    }

    /**
     * 一把公钥对应的签发方和允许角色。
     *
     * @param key RSA 公钥
     * @param issuer 允许的签发方
     * @param role 允许的角色
     */
    private record KeyEntry(PublicKey key, String issuer, String role) {
    }
}
