package com.atguigu.gulimall.k8s.chat;

import com.atguigu.gulimall.k8s.auth.SessionUser;
import com.atguigu.gulimall.k8s.config.ConversationProperties;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 会话历史：列表、新建、消息、删除。
 *
 * <p>所有数据都在 Redis，保留最近 3 天。
 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final ConversationProperties properties;

    /**
     * 查询当前用户最近 3 天的会话列表。
     *
     * @param session 当前 Session
     * @return 会话摘要列表
     */
    @GetMapping
    public Map<String, Object> list(HttpSession session) {
        // 1. 只返回当前用户自己的会话
        SessionUser user = SessionUser.require(session);
        return Map.of("list", conversationService.list(user.id()));
    }

    /**
     * 新建一个会话。
     *
     * @param session 当前 Session
     * @param body    请求体，modelId 可选
     * @return 新会话 id
     */
    @PostMapping
    public Map<String, Object> create(HttpSession session,
                                      @RequestBody(required = false) Map<String, Object> body) {
        // 1. modelId 可选；不传时由默认模型兜底
        SessionUser user = SessionUser.require(session);
        Object rawModelId = body == null ? null : body.get("modelId");
        String modelId = rawModelId == null ? null : String.valueOf(rawModelId);

        // 2. 创建会话并返回 id
        return Map.of("conversationId", conversationService.create(user.id(), modelId));
    }

    /**
     * 查询指定会话的消息。
     *
     * @param session 当前 Session
     * @param id      会话 id
     * @param limit   最多返回条数
     * @return 消息列表
     */
    @GetMapping("/{id}/messages")
    public Map<String, Object> messages(HttpSession session,
                                        @PathVariable("id") String id,
                                        @RequestParam(value = "limit", required = false) Integer limit) {
        // 1. 没传 limit 时使用配置的页面条数
        SessionUser user = SessionUser.require(session);
        int pageSize = limit == null || limit < 1 ? properties.getUiPageSize() : limit;

        // 2. 读取最近 pageSize 条消息
        return Map.of("list", conversationService.messages(user.id(), id, pageSize));
    }

    /**
     * 删除指定会话及其消息、记忆。
     *
     * @param session 当前 Session
     * @param id      会话 id
     * @return 固定成功响应
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(HttpSession session, @PathVariable("id") String id) {
        // 1. 先校验归属再删
        SessionUser user = SessionUser.require(session);
        conversationService.delete(user.id(), id);
        return Map.of("ok", true);
    }
}
