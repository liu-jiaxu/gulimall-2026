package com.atguigu.gulimall.agent.flow;

import com.atguigu.gulimall.agent.support.AgentIntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 3.4：流式对话与停止。
 */
@Tag("integration")
class Flow34StreamStopTest extends AgentIntegrationTestSupport {

    private static final String MODEL_ID = "deepseek-v4.1-flash";

    /**
     * 测试真实 SSE 流依次返回 meta、delta、done，并且 AI 回复写入会话历史。
     */
    @Test
    void streamEmitsMetaDeltaAndDone() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("message", "请只回复OK");
        body.put("modelId", MODEL_ID);

        try (SseSession session = openSse("/agent/chat/stream", body, userHeaders())) {
            // 1. meta 事件先到
            SseEvent meta = session.await("meta", Duration.ofSeconds(60));
            assertNotNull(meta, "没有收到 meta 事件");
            Map<String, Object> metaData = meta.json(objectMapper);
            String conversationId = String.valueOf(metaData.get("conversationId"));
            trackConversation(conversationId);
            assertFalse(conversationId.isBlank());
            assertNotNull(metaData.get("messageId"));

            // 2. 至少收到一个 delta
            SseEvent delta = session.await("delta", Duration.ofSeconds(120));
            assertNotNull(delta, "没有收到 delta 事件");
            assertFalse(String.valueOf(delta.json(objectMapper).get("content")).isEmpty());

            // 3. done 事件结束本次流
            SseEvent done = session.await("done", Duration.ofSeconds(180));
            assertNotNull(done, "没有收到 done 事件");
            Map<String, Object> doneData = done.json(objectMapper);
            assertEquals(conversationId, doneData.get("conversationId"));
            assertNotNull(doneData.get("messageId"));

            // 4. AI 回复已经落 Redis
            Map<String, Object> messagesResponse = get(
                    "/agent/conversations/" + conversationId + "/messages?limit=20", userHeaders());
            assertEquals(0, code(messagesResponse));
            List<Map<String, Object>> messages = list(messagesResponse);
            assertTrue(messages.size() >= 2);
            assertEquals("assistant", messages.get(messages.size() - 1).get("role"));
        }
    }

    /**
     * 测试流式接口在没有 JWT 时返回 SSE error 事件 15001。
     */
    @Test
    void missingJwtReturnsSseErrorEvent() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("message", "只回复OK");
        body.put("modelId", MODEL_ID);

        try (SseSession session = openSse("/agent/chat/stream", body, anonHeaders())) {
            // 1. 流式接口必须用 SSE error 事件返回 15001，而不是普通 JSON
            SseEvent error = session.await("error", Duration.ofSeconds(20));
            assertNotNull(error, "没有收到 error 事件");
            assertEquals(15001, ((Number) error.json(objectMapper).get("code")).intValue());
        }
    }

    /**
     * 测试流式接口在会话锁被占用时返回 SSE error 事件 15006。
     */
    @Test
    void busyConversationReturnsSseErrorEvent() throws Exception {
        // 1. 先占住会话锁
        String conversationId = conversationService.create(TEST_USER, MODEL_ID);
        trackConversation(conversationId);
        String lockKey = "gulimall:ai:lock:conv:" + conversationId;
        redis.opsForValue().set(lockKey, "1", Duration.ofMinutes(5));

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("conversationId", conversationId);
            body.put("message", "只回复OK");
            body.put("modelId", MODEL_ID);

            try (SseSession session = openSse("/agent/chat/stream", body, userHeaders())) {
                // 2. 抢锁失败返回 15006
                SseEvent error = session.await("error", Duration.ofSeconds(20));
                assertNotNull(error, "没有收到 error 事件");
                assertEquals(15006, ((Number) error.json(objectMapper).get("code")).intValue());
            }
        } finally {
            redis.delete(lockKey);
        }
    }

    /**
     * 测试停止接口把当前 streamId 写入 stop 标记。
     */
    @Test
    void stopWritesCurrentStreamMarker() {
        // 1. 模拟当前会话有一个正在跑的流
        String conversationId = conversationService.create(TEST_USER, MODEL_ID);
        trackConversation(conversationId);
        String streamId = "stream-" + System.nanoTime();
        redis.opsForValue().set("gulimall:ai:stream:" + conversationId,
                streamId, Duration.ofMinutes(5));

        // 2. 调停止接口，应该把当前 streamId 写入停止标记
        Map<String, Object> response = post("/agent/chat/stop",
                Map.of("conversationId", conversationId), userHeaders());
        assertEquals(0, code(response));
        assertEquals(streamId, redis.opsForValue().get("gulimall:ai:stop:" + conversationId));
    }

    /**
     * 测试流运行中打停止标记后，done 事件带 stopped=true，已生成部分仍保存到会话。
     */
    @Test
    void runningStreamHonoursStopMarker() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("message", "请写一篇两千字的文章，主题是春天的商场促销活动，要求段落完整。");
        body.put("modelId", MODEL_ID);

        try (SseSession session = openSse("/agent/chat/stream", body, userHeaders())) {
            // 1. 收到 meta 后立刻给当前流打停止标记
            SseEvent meta = session.await("meta", Duration.ofSeconds(60));
            assertNotNull(meta, "没有收到 meta 事件");
            String conversationId = String.valueOf(meta.json(objectMapper).get("conversationId"));
            trackConversation(conversationId);

            // 2. 先等至少一个 token，确保停止后仍有一段已生成内容可落库
            SseEvent firstDelta = session.await("delta", Duration.ofSeconds(120));
            assertNotNull(firstDelta, "没有收到 delta 事件");

            String streamId = redis.opsForValue().get("gulimall:ai:stream:" + conversationId);
            assertNotNull(streamId, "当前会话没有记录 streamId");
            redis.opsForValue().set("gulimall:ai:stop:" + conversationId,
                    streamId, Duration.ofMinutes(2));
            post("/agent/chat/stop", Map.of("conversationId", conversationId), userHeaders());

            // 3. 流结束事件应带 stopped=true，且只保存已生成的部分
            SseEvent done = session.await("done", Duration.ofSeconds(180));
            assertNotNull(done, "没有收到 done 事件");
            Map<String, Object> doneData = done.json(objectMapper);
            assertEquals(Boolean.TRUE, doneData.get("stopped"));

            // 4. 已生成的部分仍然可以从会话历史读到
            Map<String, Object> messagesResponse = get(
                    "/agent/conversations/" + conversationId + "/messages?limit=20", userHeaders());
            assertEquals(0, code(messagesResponse));
            assertTrue(list(messagesResponse).size() >= 2);
        }
    }

    private int code(Map<String, Object> response) {
        return ((Number) response.get("code")).intValue();
    }
}
