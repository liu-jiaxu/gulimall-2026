package com.atguigu.gulimall.agent.flow;

import com.atguigu.gulimall.agent.chat.dto.ChatMessageDto;
import com.atguigu.gulimall.agent.chat.dto.ConversationSummary;
import com.atguigu.gulimall.agent.chat.memory.RedisChatMemoryStore;
import com.atguigu.gulimall.agent.config.AiProperties;
import com.atguigu.gulimall.agent.support.AgentIntegrationTestSupport;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 3.5：多会话、记忆、搜索。
 */
@Tag("integration")
class Flow35ConversationMemorySearchTest extends AgentIntegrationTestSupport {

    private static final String MODEL_ID = "deepseek-v4.1-flash";

    @Autowired
    private RedisChatMemoryStore memoryStore;

    @Autowired
    private AiProperties aiProperties;

    /**
     * 测试会话新建、列表、消息、会话内搜索、全局搜索和删除的完整读写链路。
     */
    @Test
    void conversationCrudSearchAndDelete() {
        // 1. 新建会话
        Map<String, Object> createResponse = post("/agent/conversations",
                Map.of("modelId", MODEL_ID), userHeaders());
        assertEquals(0, code(createResponse));
        String conversationId = String.valueOf(createResponse.get("conversationId"));
        trackConversation(conversationId);

        // 2. 直接写两条消息，避免为了测会话接口再调一次模型
        conversationService.append(TEST_USER, conversationId, message("user", "Hello World"));
        conversationService.append(TEST_USER, conversationId, message("assistant", "world reply"));

        // 3. 列表能看到会话摘要
        List<Map<String, Object>> conversations = list(get("/agent/conversations", userHeaders()));
        assertTrue(conversations.stream()
                .anyMatch(item -> conversationId.equals(item.get("id"))));

        // 4. 消息分页和会话内搜索
        List<Map<String, Object>> messages = list(get(
                "/agent/conversations/" + conversationId + "/messages?limit=20", userHeaders()));
        assertEquals(2, messages.size());
        List<Map<String, Object>> hits = list(get(
                "/agent/conversations/" + conversationId
                        + "/messages/search?keyword=HELLO", userHeaders()));
        assertEquals(1, hits.size());

        // 5. 全局搜索返回所属会话
        List<Map<String, Object>> allHits = list(get(
                "/agent/conversations/search?keyword=world", userHeaders()));
        assertTrue(allHits.stream()
                .anyMatch(item -> conversationId.equals(item.get("conversationId"))));

        // 6. 删除后列表里不再出现
        assertEquals(0, code(delete("/agent/conversations/" + conversationId, userHeaders())));
        List<Map<String, Object>> afterDelete = list(get("/agent/conversations", userHeaders()));
        assertFalse(afterDelete.stream()
                .anyMatch(item -> conversationId.equals(item.get("id"))));
    }

    /**
     * 测试消息分页和 before 游标向前加载更早消息。
     */
    @Test
    void paginationSupportsBeforeCursor() {
        String conversationId = conversationService.create(TEST_USER, MODEL_ID);
        trackConversation(conversationId);

        // 1. 写 5 条消息
        for (int i = 1; i <= 5; i++) {
            conversationService.append(TEST_USER, conversationId,
                    message("user", "message-" + i));
        }

        // 2. 取最新 2 条，再以第 3 条为游标向前取 2 条
        List<Map<String, Object>> latest = list(get(
                "/agent/conversations/" + conversationId + "/messages?limit=2", userHeaders()));
        assertEquals(2, latest.size());
        assertEquals("message-4", latest.get(0).get("content"));
        assertEquals("message-5", latest.get(1).get("content"));

        String beforeId = String.valueOf(latest.get(0).get("id"));
        List<Map<String, Object>> earlier = list(get(
                "/agent/conversations/" + conversationId
                        + "/messages?limit=2&before=" + beforeId, userHeaders()));
        assertEquals(2, earlier.size());
        assertEquals("message-2", earlier.get(0).get("content"));
        assertEquals("message-3", earlier.get(1).get("content"));
    }

    /**
     * 测试其他身份读取会话时被归属校验拒绝并返回 15001。
     */
    @Test
    void otherPrincipalCannotReadConversation() {
        String conversationId = conversationService.create(TEST_USER, MODEL_ID);
        trackConversation(conversationId);

        // 1. 用管理端身份读用户端会话
        Map<String, Object> response = get(
                "/agent/conversations/" + conversationId + "/messages", adminHeaders());

        // 2. 归属校验失败，返回 15001
        assertEquals(15001, code(response));
    }

    /**
     * 测试 RedisChatMemoryStore 的写入、读取和删除。
     */
    @Test
    void redisMemoryStoreRoundTrip() {
        String memoryId = "it-memory-" + UUID.randomUUID();
        try {
            // 1. 写入两条 LangChain4J 消息
            List<ChatMessage> messages = List.of(
                    UserMessage.from("hello"),
                    AiMessage.from("hi"));
            memoryStore.updateMessages(memoryId, messages);

            // 2. 能按 memoryId 读回
            List<ChatMessage> loaded = memoryStore.getMessages(memoryId);
            assertEquals(2, loaded.size());
            assertTrue(redis.hasKey("gulimall:ai:memory:" + memoryId));

            // 3. 删除后读回空列表
            memoryStore.deleteMessages(memoryId);
            assertTrue(memoryStore.getMessages(memoryId).isEmpty());
            assertFalse(redis.hasKey("gulimall:ai:memory:" + memoryId));
        } finally {
            redis.delete("gulimall:ai:memory:" + memoryId);
        }
    }

    /**
     * 测试会话数量达到上限时淘汰最久未使用的会话。
     */
    @Test
    void conversationLimitEvictsOldest() {
        int original = aiProperties.getConversation().getMaxConversationsPerPrincipal();
        aiProperties.getConversation().setMaxConversationsPerPrincipal(3);
        try {
            // 1. 连续创建 4 个会话
            String first = conversationService.create(TEST_USER, MODEL_ID);
            String second = conversationService.create(TEST_USER, MODEL_ID);
            String third = conversationService.create(TEST_USER, MODEL_ID);
            String fourth = conversationService.create(TEST_USER, MODEL_ID);
            trackConversation(first);
            trackConversation(second);
            trackConversation(third);
            trackConversation(fourth);

            // 2. 最久未用的第一个会话被淘汰，列表只剩 3 个
            List<ConversationSummary> summaries = conversationService.list(TEST_USER);
            assertEquals(3, summaries.size());
            assertFalse(summaries.stream().anyMatch(item -> first.equals(item.getId())));
            assertTrue(summaries.stream().anyMatch(item -> fourth.equals(item.getId())));
            assertNull(redis.opsForHash().get("gulimall:ai:conv:meta:" + first, "title"));
        } finally {
            aiProperties.getConversation().setMaxConversationsPerPrincipal(original);
        }
    }

    private ChatMessageDto message(String role, String content) {
        ChatMessageDto message = new ChatMessageDto();
        message.setId(UUID.randomUUID().toString().replace("-", ""));
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(System.currentTimeMillis());
        message.setStatus("ok");
        return message;
    }

    private int code(Map<String, Object> response) {
        return ((Number) response.get("code")).intValue();
    }
}
