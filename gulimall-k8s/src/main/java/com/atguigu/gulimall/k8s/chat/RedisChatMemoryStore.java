package com.atguigu.gulimall.k8s.chat;

import com.atguigu.gulimall.k8s.config.ConversationProperties;
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
 * <p>这里只存“模型窗口”消息（默认 40 条），
 * 完整 UI 历史由 ConversationService 另外保存，窗口截断不会丢历史。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

    /**
     * 模型记忆 key 前缀
     */
    public static final String PREFIX = "gulimall:k8s:ai:memory:";

    private final StringRedisTemplate redisTemplate;
    private final ConversationProperties properties;

    /**
     * 读取指定会话的记忆窗口消息。
     *
     * @param memoryId 会话 id
     * @return 记忆窗口消息，无数据时为空列表
     */
    @Override
    public List<dev.langchain4j.data.message.ChatMessage> getMessages(Object memoryId) {
        // 1. 没数据时返回空列表（接口约定不能返回 null）
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
     * 覆盖写入记忆窗口，并续期 3 天。
     *
     * @param memoryId 会话 id
     * @param messages 记忆窗口消息
     */
    @Override
    public void updateMessages(Object memoryId, List<dev.langchain4j.data.message.ChatMessage> messages) {
        // 1. 序列化成 LangChain4J 官方 JSON 格式
        String json = ChatMessageSerializer.messagesToJson(messages);

        // 2. 每次写入续期 3 天
        redisTemplate.opsForValue().set(PREFIX + memoryId, json, properties.getTtl());
    }

    /**
     * 删除指定会话的记忆窗口。
     *
     * @param memoryId 会话 id
     */
    @Override
    public void deleteMessages(Object memoryId) {
        // 1. 删除会话时一起清掉
        redisTemplate.delete(PREFIX + memoryId);
    }
}
