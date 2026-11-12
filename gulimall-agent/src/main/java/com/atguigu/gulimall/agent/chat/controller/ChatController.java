package com.atguigu.gulimall.agent.chat.controller;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.auth.PrincipalResolver;
import com.atguigu.gulimall.agent.chat.dto.ChatRequest;
import com.atguigu.gulimall.agent.chat.service.ChatOrchestrator;
import com.atguigu.gulimall.agent.exception.AgentErrorCode;
import com.atguigu.gulimall.agent.exception.AgentException;
import com.atguigu.gulimall.common.utils.R;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聊天入口：普通 JSON 和 SSE 流式两种。
 */
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class ChatController {

    private final ChatOrchestrator chatOrchestrator;
    private final PrincipalResolver principalResolver;
    private final ObjectMapper objectMapper;

    /**
     * 普通对话：一次性返回完整回答。
     *
     * @param principal 当前登录身份
     * @param request   聊天请求，包含会话 id、用户消息、模型 id 与图片 id
     * @return 统一响应，data 里是本次对话的会话 id、回复消息与 token 用量
     */
    @PostMapping("/chat")
    public R chat(Principal principal, @RequestBody ChatRequest request) {
        return R.ok().put("data", chatOrchestrator.chat(principal, request));
    }

    /**
     * 流式对话：SSE 事件 meta -> delta... -> done（异常时 error）。
     *
     * <p>这里不用 Principal 参数，而是手动解析：
     * 未登录时参数解析器会直接返回 JSON，而流式接口的客户端期望 SSE 事件，
     * 手动解析可以保证“JWT 缺失/无效/过期”也以 error 事件返回。
     *
     * @param httpRequest 原始 HTTP 请求，用于手动解析登录身份
     * @param request     聊天请求，包含会话 id、用户消息、模型 id 与图片 id
     * @return SSE 发射器，按 meta -> delta... -> done（异常时为 error）推送事件
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest httpRequest, @RequestBody ChatRequest request) {
        Principal principal;
        try {
            principal = principalResolver.resolve(httpRequest);
        } catch (AgentException ex) {
            return errorEmitter(ex.getCode(), ex.getMessage());
        }
        if (principal == null) {
            return errorEmitter(AgentErrorCode.NOT_LOGIN, "请先登录后再使用 AI 助手");
        }
        return chatOrchestrator.stream(principal, request);
    }

    /**
     * 强制结束当前会话正在进行的流式回答。
     *
     * @param principal 当前登录身份
     * @param body      请求体，可含 conversationId，指定要停止的会话
     * @return 统一响应，停止操作不返回业务数据
     */
    @PostMapping("/chat/stop")
    public R stop(Principal principal, @RequestBody Map<String, Object> body) {
        Object conversationId = body == null ? null : body.get("conversationId");
        chatOrchestrator.stop(principal, conversationId == null ? null : String.valueOf(conversationId));
        return R.ok();
    }

    /**
     * 前端加载宠物/聊天窗时先调它判断登录态。
     * 没有 JWT 时抛 15001，JWT 无效/过期时抛 15012，前端据此刷新或跳登录。
     *
     * @param principal 当前登录身份
     * @return 统一响应，data 里是身份类型、id、用户名与显示名
     */
    @GetMapping("/auth/me")
    public R me(Principal principal) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", principal.type().name());
        data.put("id", principal.id());
        data.put("username", principal.username());
        data.put("displayName", principal.displayName());
        return R.ok().put("data", data);
    }

    /**
     * 构造一个只推送一条 error 事件后立即结束的 SSE 响应。
     *
     * @param code    错误码，前端按它走统一错误提示
     * @param message 错误提示文案
     * @return 已完成的 SSE 发射器
     */
    private SseEmitter errorEmitter(int code, String message) {
        // 1. 建一个只发一条 error 事件的 SSE，前端用同一套解析逻辑处理
        SseEmitter emitter = new SseEmitter(30_000L);
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("code", code);
            data.put("message", message);
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(objectMapper.writeValueAsString(data), MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
            // 客户端断开时不用再处理
        }
        emitter.complete();
        return emitter;
    }
}
