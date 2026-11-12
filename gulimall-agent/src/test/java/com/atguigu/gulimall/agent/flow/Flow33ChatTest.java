package com.atguigu.gulimall.agent.flow;

import com.atguigu.gulimall.agent.support.AgentIntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 3.3：普通对话。
 */
@Tag("integration")
class Flow33ChatTest extends AgentIntegrationTestSupport {

    private static final String MODEL_ID = "deepseek-v4.1-flash";

    /**
     * 测试普通对话完整链路：参数准备、会话锁、保存用户消息、调用模型、保存 AI 消息并返回会话 id。
     */
    @Test
    void normalChatRunsPrepareLockAppendAssistantAndSave() {
        // 1. 发起真实普通对话
        Map<String, Object> body = new HashMap<>();
        body.put("message", "只回复OK");
        body.put("modelId", MODEL_ID);
        Map<String, Object> response = post("/agent/chat", body, userHeaders());

        // 2. 返回成功，包含会话 id、消息 id 和回答
        assertEquals(0, code(response));
        Map<String, Object> data = data(response);
        String conversationId = String.valueOf(data.get("conversationId"));
        trackConversation(conversationId);
        assertFalse(String.valueOf(data.get("content")).isBlank());
        assertEquals(MODEL_ID, data.get("modelId"));

        // 3. 用户消息和 AI 消息都已写入 Redis
        Map<String, Object> messagesResponse = get(
                "/agent/conversations/" + conversationId + "/messages?limit=20", userHeaders());
        assertEquals(0, code(messagesResponse));
        List<Map<String, Object>> messages = list(messagesResponse);
        assertTrue(messages.size() >= 2);
        assertEquals("user", messages.get(messages.size() - 2).get("role"));
        assertEquals("assistant", messages.get(messages.size() - 1).get("role"));
    }

    /**
     * 测试空消息且没有图片时返回 15005。
     */
    @Test
    void emptyMessageIsRejected() {
        // 1. 空消息且没有图片
        Map<String, Object> response = post("/agent/chat",
                Map.of("message", "   ", "modelId", MODEL_ID), userHeaders());

        // 2. prepare 阶段抛 15005
        assertEquals(15005, code(response));
    }

    /**
     * 测试同一会话已有锁时返回 15006。
     */
    @Test
    void busyConversationIsRejected() {
        // 1. 先创建一个会话，并人为占用它的锁
        String conversationId = conversationService.create(TEST_USER, MODEL_ID);
        trackConversation(conversationId);
        String lockKey = "gulimall:ai:lock:conv:" + conversationId;
        redis.opsForValue().set(lockKey, "1", java.time.Duration.ofMinutes(5));

        try {
            // 2. 再发消息时抢不到锁，返回 15006
            Map<String, Object> response = post("/agent/chat",
                    Map.of("conversationId", conversationId,
                            "message", "只回复OK",
                            "modelId", MODEL_ID), userHeaders());
            assertEquals(15006, code(response));
        } finally {
            redis.delete(lockKey);
        }
    }

    /**
     * 测试请求不存在的模型时返回 15004。
     */
    @Test
    void unknownModelIsRejected() {
        // 1. 请求一个不存在的模型
        Map<String, Object> response = post("/agent/chat",
                Map.of("message", "只回复OK", "modelId", "not-exists"), userHeaders());

        // 2. ModelCatalog 找不到时返回 15004
        assertEquals(15004, code(response));
    }

    /**
     * 测试没有进行中的流时调用停止接口不写停止标记。
     */
    @Test
    void stopWithoutActiveStreamDoesNothing() {
        // 1. 创建会话，但当前没有流
        String conversationId = conversationService.create(TEST_USER, MODEL_ID);
        trackConversation(conversationId);

        // 2. 调用停止接口应成功，不会写停止标记
        Map<String, Object> response = post("/agent/chat/stop",
                Map.of("conversationId", conversationId), userHeaders());
        assertEquals(0, code(response));
        assertNull(redis.opsForValue().get("gulimall:ai:stop:" + conversationId));
    }

    private int code(Map<String, Object> response) {
        return ((Number) response.get("code")).intValue();
    }
}
