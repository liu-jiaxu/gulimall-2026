package io.renren.modules.sys.agent;

import io.jsonwebtoken.Jwts;
import io.renren.modules.sys.entity.SysUserEntity;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * renren-fast 的管理端 agent JWT 签发服务。
 *
 * <p>只签发 role=admin 的 JWT，把 JWT 写入 admin.gulimall.vip 的 HttpOnly Cookie；
 * 不存数据库、不存 Redis，也不负责 agent 侧验签。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentJwtService {

    private final AgentJwtProperties properties;
    private PrivateKey privateKey;

    /**
     * 启动时加载管理端 RSA 私钥，缺少私钥时直接失败。
     */
    @PostConstruct
    public void loadPrivateKey() {
        try {
            String pem = Files.readString(Path.of(properties.getPrivateKeyPath()), StandardCharsets.UTF_8)
                    .replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "")
                    .replaceAll("\\s", "");
            byte[] encoded = Base64.getDecoder().decode(pem);
            privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(encoded));
            log.info("管理端 agent JWT 私钥加载完成 kid={}", properties.getKeyId());
        } catch (Exception ex) {
            throw new IllegalStateException("加载管理端 agent JWT 私钥失败："
                    + properties.getPrivateKeyPath(), ex);
        }
    }

    /**
     * 为登录成功的管理员签发 JWT，并写入 HttpOnly Cookie。
     *
     * @param user 登录成功的管理员
     * @param response 当前 HTTP 响应，用于写 Set-Cookie
     * @return 本次 JWT 的过期时间戳（毫秒）
     */
    public long issue(SysUserEntity user, HttpServletResponse response) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getTtl());
        String token = Jwts.builder()
                .header().keyId(properties.getKeyId()).and()
                .issuer(properties.getIssuer())
                .audience().add(properties.getAudience()).and()
                .subject(String.valueOf(user.getUserId()))
                .claim("username", user.getUsername())
                .claim("role", "admin")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .id(UUID.randomUUID().toString())
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();

        ResponseCookie cookie = ResponseCookie.from(properties.getCookieName(), token)
                .domain(properties.getCookieDomain())
                .path(properties.getCookiePath())
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite("Lax")
                .maxAge(properties.getTtl())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return expiresAt.toEpochMilli();
    }

    /**
     * 清除浏览器中的管理端 agent JWT Cookie。
     *
     * @param response 当前 HTTP 响应，用于写 Set-Cookie
     */
    public void clear(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(properties.getCookieName(), "")
                .domain(properties.getCookieDomain())
                .path(properties.getCookiePath())
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite("Lax")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
