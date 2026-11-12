package com.atguigu.gulimall.agent.chat.memory;

import com.atguigu.gulimall.agent.config.AiProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LangChain4J 的聊天记忆落到 Redis。
 *
 * <p>只存“模型窗口”消息（默认 40 条），完整历史由 ConversationService 另行保存。
 * 这样窗口截断不会把用户 3 天内的历史记录删掉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

    public static final String PREFIX = "gulimall:ai:memory:";

    private final StringRedisTemplate redisTemplate;
    private final AiProperties properties;

    /**
     * 读取指定会话的记忆窗口消息；没有数据或反序列化失败时返回空列表。
     *
     * @param memoryId 会话记忆标识，通常为会话 id
     * @return 该会话已保存的聊天消息列表，无数据时为空列表
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        // 1. 没数据返回空列表（接口约定不能返回 null）
        String json = redisTemplate.opsForValue().get(PREFIX + memoryId);
        if (json == null || json.isBlank()) {
            return List.of();
        }

        // 2. 坏数据不让整个对话崩掉
        try {
            return ChatMessageDeserializer.messagesFromJson(json);
        } catch (Exception ex) {
            log.warn("聊天记忆反序列化失败 memoryId={}：{}", memoryId, ex.getMessage());
            return List.of();
        }
    }

    /**
     * 将整个记忆窗口覆盖写入 Redis，并按配置续期过期时间。
     *
     * @param memoryId 会话记忆标识，通常为会话 id
     * @param messages 需要保存的完整聊天消息列表
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // 1. 序列化成 LangChain4J 官方 JSON 格式
        String json = ChatMessageSerializer.messagesToJson(messages);

        // 2. 每次写入续期 3 天
        redisTemplate.opsForValue().set(PREFIX + memoryId, json, properties.getConversation().getTtl());
    }

    /**
     * 删除指定会话的记忆窗口数据。
     *
     * @param memoryId 会话记忆标识，通常为会话 id
     */
    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(PREFIX + memoryId);
    }
}
