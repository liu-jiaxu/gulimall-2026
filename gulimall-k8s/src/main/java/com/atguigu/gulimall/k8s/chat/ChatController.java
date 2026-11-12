package com.atguigu.gulimall.k8s.chat;

import com.atguigu.gulimall.k8s.auth.SessionUser;
import com.atguigu.gulimall.k8s.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聊天入口：普通 JSON 和 SSE 流式两种。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    /**
     * 普通对话：一次性返回完整回答。
     *
     * @param session 当前 Session
     * @param request 聊天请求
     * @return 统一响应，data 里是会话 id、回复和 token 用量
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(HttpSession session, @RequestBody ChatRequest request) {
        // 1. 没登录直接 401
        SessionUser user = SessionUser.require(session);

        // 2. 交给 ChatService 处理
        return Map.of("data", chatService.chat(user.id(), request));
    }

    /**
     * 流式对话：SSE 事件 meta -> delta... -> done（异常时 error）。
     *
     * @param session 当前 Session
     * @param request 聊天请求
     * @return SSE 连接
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpSession session, @RequestBody ChatRequest request) {
        // 1. 未登录也要用 SSE 事件返回，避免前端拿到 JSON 解析失败
        SessionUser user;
        try {
            user = SessionUser.require(session);
        } catch (ApiException ex) {
            return errorEmitter(ex.getStatus(), ex.getMessage());
        }

        // 2. 交给 ChatService 推送 token
        return chatService.stream(user.id(), request);
    }

    /**
     * 构造一个只推送 error 事件的 SSE 响应。
     *
     * @param code    错误码
     * @param message 错误提示
     * @return 已完成的 SSE 连接
     */
    private SseEmitter errorEmitter(int code, String message) {
        // 1. 用 ObjectMapper 序列化，避免提示文案里的引号把 JSON 拼坏
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("message", message);

        // 2. 只发一条 error 事件就结束
        SseEmitter emitter = new SseEmitter(30_000L);
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(objectMapper.writeValueAsString(data), MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
            // 客户端断开时不用处理
        }
        emitter.complete();
        return emitter;
    }
}
