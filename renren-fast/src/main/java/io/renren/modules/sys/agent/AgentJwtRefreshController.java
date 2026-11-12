package io.renren.modules.sys.agent;

import io.renren.common.utils.R;
import io.renren.modules.sys.controller.AbstractController;
import io.renren.modules.sys.entity.SysUserEntity;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端 agent JWT 刷新接口。
 *
 * <p>接口本身由 renren-fast 现有 JWTFilter 保护；
 * 旧管理端 token 有效时重新签发 30 分钟 agent JWT，不读写 agent 的 JWT。
 */
@RestController
@RequestMapping("/sys/agent/jwt")
@RequiredArgsConstructor
public class AgentJwtRefreshController extends AbstractController {

    private final AgentJwtService agentJwtService;

    /**
     * 用当前管理端登录态换取新的 agent JWT。
     *
     * @param response 当前 HTTP 响应，用于更新 JWT Cookie
     * @return 统一响应，data 中是新的过期时间戳
     */
    @PostMapping("/refresh")
    public R refresh(HttpServletResponse response) {
        SysUserEntity user = getUser();
        if (user == null || user.getUserId() == null) {
            return R.error(401, "登录已过期，请重新登录");
        }
        long expiresAt = agentJwtService.issue(user, response);
        return R.ok().put("data", Map.of("expiresAt", expiresAt));
    }
}
