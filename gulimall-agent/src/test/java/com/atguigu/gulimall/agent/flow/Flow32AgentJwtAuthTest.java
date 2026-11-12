package com.atguigu.gulimall.agent.flow;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.auth.PrincipalType;
import com.atguigu.gulimall.agent.auth.jwt.AgentJwtService;
import com.atguigu.gulimall.agent.support.AgentIntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 3.2：agent JWT 鉴权与统一异常。
 */
@Tag("integration")
class Flow32AgentJwtAuthTest extends AgentIntegrationTestSupport {

    @Autowired
    private AgentJwtService agentJwtService;

    /**
     * 测试真实 user/admin JWT 的验签结果，以及 /agent/auth/me 返回的身份类型、id 和用户名。
     */
    @Test
    void realUserAndAdminTokensResolveToCorrectPrincipal() throws Exception {
        // 1. 直接调用验签服务
        Principal user = agentJwtService.verify(userToken());
        Principal admin = agentJwtService.verify(adminToken());

        // 2. 身份类型、id、用户名、角色判断都正确
        assertEquals(PrincipalType.USER, user.type());
        assertEquals(TEST_USER_ID, user.id());
        assertEquals("it_user", user.username());
        assertFalse(user.isAdmin());
        assertEquals("USER:" + TEST_USER_ID, user.subject());

        assertEquals(PrincipalType.ADMIN, admin.type());
        assertEquals(TEST_ADMIN_ID, admin.id());
        assertTrue(admin.isAdmin());

        // 3. 通过真实 HTTP 访问 /agent/auth/me
        Map<String, Object> userResponse = get("/agent/auth/me", userHeaders());
        assertEquals(0, code(userResponse));
        assertEquals("USER", data(userResponse).get("type"));
        assertEquals(TEST_USER_ID, ((Number) data(userResponse).get("id")).longValue());

        Map<String, Object> adminResponse = get("/agent/auth/me", adminHeaders());
        assertEquals(0, code(adminResponse));
        assertEquals("ADMIN", data(adminResponse).get("type"));
        assertEquals(TEST_ADMIN_ID, ((Number) data(adminResponse).get("id")).longValue());
    }

    /**
     * 测试没有 JWT Cookie 时，参数解析器返回 15001。
     */
    @Test
    void missingCookieReturnsNotLogin() {
        // 1. 不带 Cookie 访问受保护接口
        Map<String, Object> response = get("/agent/auth/me", anonHeaders());

        // 2. PrincipalArgumentResolver 抛 15001，统一异常处理器转成 R
        assertEquals(15001, code(response));
    }

    /**
     * 测试格式错误的 JWT 被拒绝并返回 15012。
     */
    @Test
    void invalidTokenReturnsAgentTokenInvalid() {
        // 1. 伪造一个格式正确的假 JWT
        HttpHeaders headers = headers("AGENT_JWT_USER", "aaa.bbb.ccc");
        Map<String, Object> response = get("/agent/auth/me", headers);

        // 2. 验签失败返回 15012
        assertEquals(15012, code(response));
    }

    /**
     * 测试只按 Cookie 名称区分身份：有管理端 Cookie 就是 ADMIN，没有才看用户 Cookie。
     */
    @Test
    void adminCookieWinsOverUserCookie() {
        // 1. 只有管理端 Cookie：按 ADMIN 处理
        HttpHeaders adminOnly = headers("AGENT_JWT_ADMIN", adminToken());
        Map<String, Object> adminResponse = get("/agent/auth/me", adminOnly);
        assertEquals(0, code(adminResponse));
        assertEquals("ADMIN", data(adminResponse).get("type"));

        // 2. 只有用户 Cookie：按 USER 处理
        HttpHeaders userOnly = headers("AGENT_JWT_USER", userToken());
        Map<String, Object> userResponse = get("/agent/auth/me", userOnly);
        assertEquals(0, code(userResponse));
        assertEquals("USER", data(userResponse).get("type"));

        // 3. 两个 Cookie 同时存在：管理端 Cookie 优先
        HttpHeaders both = headers("AGENT_JWT_ADMIN", adminToken());
        both.add(HttpHeaders.COOKIE, "AGENT_JWT_USER=" + userToken());
        Map<String, Object> bothResponse = get("/agent/auth/me", both);
        assertEquals(0, code(bothResponse));
        assertEquals("ADMIN", data(bothResponse).get("type"));
    }

    /**
     * 测试 JWT 的 role 必须与 kid 对应，用户端私钥签发 role=admin 时被拒绝。
     */
    @Test
    void roleMustMatchSigningKey() {
        // 1. 用用户端私钥签一个 role=admin 的 JWT
        String wrongRole = signToken(TEST_USER_ID, "it_user", "admin",
                "user-2026-01", "gulimall-auth-server",
                projectRoot().resolve("jwt/user/jwt_private.pem"));

        // 2. kid 与 role 不匹配，验签拒绝
        HttpHeaders headers = headers("AGENT_JWT_USER", wrongRole);
        assertEquals(15012, code(get("/agent/auth/me", headers)));
    }

    /**
     * 测试 JWT 的签发方必须与 kid 对应，错误 iss 被拒绝。
     */
    @Test
    void issuerAndAudienceMustMatch() {
        // 1. 用用户端私钥签发错误的 iss
        String wrongIssuer = signToken(TEST_USER_ID, "it_user", "user",
                "user-2026-01", "unknown-issuer",
                projectRoot().resolve("jwt/user/jwt_private.pem"));
        HttpHeaders headers = headers("AGENT_JWT_USER", wrongIssuer);
        assertEquals(15012, code(get("/agent/auth/me", headers)));
    }

    private int code(Map<String, Object> response) {
        return ((Number) response.get("code")).intValue();
    }
}
