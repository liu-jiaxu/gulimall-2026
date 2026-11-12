package com.atguigu.gulimall.auth_server.agent;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import io.jsonwebtoken.Jwts;
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
 * auth-server 的用户端 agent JWT 签发服务。
 *
 * <p>只签发 role=user 的 JWT，把 JWT 写入 HttpOnly Cookie；
 * 不存数据库、不存 Redis，也不负责 agent 侧验签。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentJwtService {

    private final AgentJwtProperties properties;
    private PrivateKey privateKey;

    /**
     * 启动时加载用户端 RSA 私钥，缺少私钥时直接失败。
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
            log.info("用户端 agent JWT 私钥加载完成 kid={}", properties.getKeyId());
        } catch (Exception ex) {
            throw new IllegalStateException("加载用户端 agent JWT 私钥失败："
                    + properties.getPrivateKeyPath(), ex);
        }
    }

    /**
     * 为登录成功的会员签发用户端 JWT，并写入 HttpOnly Cookie。
     *
     * @param member 登录成功的会员信息
     * @param response 当前 HTTP 响应，用于写 Set-Cookie
     * @return 本次 JWT 的过期时间戳（毫秒）
     */
    public long issue(MemberResponseVo member, HttpServletResponse response) {
        return issue(member.getId(), member.getUsername(), response);
    }

    /**
     * 为指定的会员 id 和用户名签发用户端 JWT。
     *
     * @param memberId 会员 id
     * @param username 登录名
     * @param response 当前 HTTP 响应，用于写 Set-Cookie
     * @return 本次 JWT 的过期时间戳（毫秒）
     */
    public long issue(Long memberId, String username, HttpServletResponse response) {
        if (memberId == null) {
            throw new IllegalArgumentException("会员 id 不能为空");
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getTtl());
        String token = Jwts.builder()
                .header().keyId(properties.getKeyId()).and()
                .issuer(properties.getIssuer())
                .audience().add(properties.getAudience()).and()
                .subject(String.valueOf(memberId))
                .claim("username", username == null ? String.valueOf(memberId) : username)
                .claim("role", "user")
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
     * 清除浏览器中的用户端 agent JWT Cookie。
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
