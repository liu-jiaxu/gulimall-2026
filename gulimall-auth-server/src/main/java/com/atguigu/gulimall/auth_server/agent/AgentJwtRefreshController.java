package com.atguigu.gulimall.auth_server.agent;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.common.constant.AuthServerConstant;
import com.atguigu.gulimall.common.utils.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户端 agent JWT 刷新接口。
 *
 * <p>只读取 auth-server 自己的 Spring Session，不读写 agent 的 JWT；
 * Session 有效就重新签发 30 分钟 JWT，Session 过期则要求重新登录。
 */
@RestController
@RequiredArgsConstructor
public class AgentJwtRefreshController {

    private final AgentJwtService agentJwtService;

    /**
     * 用当前用户 Session 换取新的 agent JWT。
     *
     * @param request 当前 HTTP 请求，用于读取已有 Session
     * @param response 当前 HTTP 响应，用于更新 JWT Cookie
     * @return 统一响应，data 中是新的过期时间戳
     */
    @PostMapping("/agent/jwt/refresh")
    public R refresh(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        Object loginUser = session == null
                ? null
                : session.getAttribute(AuthServerConstant.LOGIN_USER);
        if (!(loginUser instanceof MemberResponseVo member) || member.getId() == null) {
            return R.error(15001, "登录已过期，请重新登录");
        }
        long expiresAt = agentJwtService.issue(member, response);
        return R.ok().put("data", Map.of("expiresAt", expiresAt));
    }
}
