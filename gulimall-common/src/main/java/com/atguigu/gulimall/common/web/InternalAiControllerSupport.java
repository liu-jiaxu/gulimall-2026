package com.atguigu.gulimall.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 业务模块内部 AI 只读接口的鉴权基类。
 *
 * <p>这些接口只允许 gulimall-agent 调用，不对外开放。
 * 两道防线：
 * 1. 必须带 X-Internal-Token，且与 config/common.yaml 的 INTERNAL_API_TOKEN 一致；
 * 2. Nginx/网关对 /gulimall/*&#47;internal/** 返回 404（部署时配置）。
 *
 * <p>token 没配置时按失败处理（fail closed），避免漏配后接口裸奔。
 */
public abstract class InternalAiControllerSupport {

    @Value("${INTERNAL_API_TOKEN:}")
    private String expectedToken;

    protected void verifyInternal(HttpServletRequest request) {
        // 1. 服务端没配 token 时直接拒绝，绝不默认放行
        if (expectedToken == null || expectedToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        // 2. 取请求头并做常量时间比较，避免时序攻击
        String actual = request.getHeader("X-Internal-Token");
        if (actual == null || !MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    /**
     * 手机号脱敏：138****1234。
     */
    protected String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
