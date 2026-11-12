package com.atguigu.gulimall.k8s.chat;

import com.atguigu.gulimall.k8s.common.ApiException;
import com.atguigu.gulimall.k8s.config.ConversationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 多会话聊天记录，全部存在 Redis。
 *
 * <p>Key 设计：
 * <ul>
 *   <li>gulimall:k8s:ai:conv:index:{userId}  ZSET，会话 id，score 为最后活跃时间</li>
 *   <li>gulimall:k8s:ai:conv:meta:{convId}   HASH，归属用户、标题、模型、时间、消息数</li>
 *   <li>gulimall:k8s:ai:conv:msg:{convId}    LIST，完整消息 JSON</li>
 *   <li>gulimall:k8s:ai:memory:{convId}      模型记忆窗口，由 RedisChatMemoryStore 维护</li>
 * </ul>
 * 所有 key 每次写入都会续期 3 天，实现“最近 3 天”的滑动过期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final String PREFIX = "gulimall:k8s:ai:";
    private static final String INDEX = PREFIX + "conv:index:";
    private static final String META = PREFIX + "conv:meta:";
    private static final String MSG = PREFIX + "conv:msg:";
    private static final String MEMORY = RedisChatMemoryStore.PREFIX;
    private static final String TITLE_DEFAULT = "新对话";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConversationProperties properties;

    /**
     * 新建会话并写入元数据和用户索引。
     *
     * <p>超过每用户会话上限时，先淘汰最久未用的一个。
     *
     * @param userId  当前用户 id
     * @param modelId 会话默认模型 id
     * @return 新会话 id
     */
    public String create(Long userId, String modelId) {
        // 1. 超过上限时淘汰最久未用的会话
        String indexKey = indexKey(userId);
        Long count = redisTemplate.opsForZSet().zCard(indexKey);
        int max = properties.getMaxConversationsPerUser();
        if (count != null && count >= max) {
            Set<String> oldest = redisTemplate.opsForZSet().range(indexKey, 0, 0);
            if (oldest != null) {
                for (String oldId : oldest) {
                    deleteById(oldId);
                }
            }
        }

        // 2. 生成会话 id 和元数据
        String conversationId = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        String metaKey = META + conversationId;
        redisTemplate.opsForHash().putAll(metaKey, Map.of(
                "ownerId", String.valueOf(userId),
                "title", TITLE_DEFAULT,
                "modelId", modelId == null ? "" : modelId,
                "createdAt", String.valueOf(now),
                "updatedAt", String.valueOf(now),
                "messageCount", "0"
        ));
        redisTemplate.expire(metaKey, ttl());

        // 3. 写进当前用户的会话索引
        redisTemplate.opsForZSet().add(indexKey, conversationId, now);
        redisTemplate.expire(indexKey, ttl());
        return conversationId;
    }

    /**
     * 取会话；id 为空时新建，并校验会话归属。
     *
     * @param userId         当前用户 id
     * @param conversationId 会话 id，可为空
     * @param modelId        新建会话时使用的模型 id
     * @return 可用的会话 id
     */
    public String getOrCreate(Long userId, String conversationId, String modelId) {
        // 1. 没传会话 id 就新建
        if (!StringUtils.hasText(conversationId)) {
            return create(userId, modelId);
        }

        // 2. 元数据不存在说明会话已过期或被删除
        Map<Object, Object> meta = redisTemplate.opsForHash().entries(META + conversationId);
        if (meta.isEmpty()) {
            throw new ApiException(404, "会话不存在或已过期，请新建对话");
        }

        // 3. 只能访问自己的会话
        String ownerId = String.valueOf(meta.get("ownerId"));
        if (!String.valueOf(userId).equals(ownerId)) {
            throw new ApiException(403, "无权访问该会话");
        }
        return conversationId;
    }

    /**
     * 追加一条消息，并更新会话标题、消息数、最后活跃时间和索引。
     *
     * @param userId         当前用户 id
     * @param conversationId 目标会话 id
     * @param message        待追加的消息
     */
    public void append(Long userId, String conversationId, ChatMessage message) {
        // 1. 追加完整消息列表，并续期 3 天
        String msgKey = MSG + conversationId;
        redisTemplate.opsForList().rightPush(msgKey, toJson(message));
        redisTemplate.expire(msgKey, ttl());

        // 2. 更新会话元数据
        String metaKey = META + conversationId;
        long now = System.currentTimeMillis();
        redisTemplate.opsForHash().increment(metaKey, "messageCount", 1);
        redisTemplate.opsForHash().put(metaKey, "updatedAt", String.valueOf(now));
        if (StringUtils.hasText(message.modelId())) {
            redisTemplate.opsForHash().put(metaKey, "modelId", message.modelId());
        }

        // 3. 首条用户消息作为会话标题
        Object title = redisTemplate.opsForHash().get(metaKey, "title");
        if (TITLE_DEFAULT.equals(title) && "user".equals(message.role())) {
            String content = message.content() == null ? "" : message.content().trim().replaceAll("\\s+", " ");
            String newTitle = content.length() > 20 ? content.substring(0, 20) : content;
            if (StringUtils.hasText(newTitle)) {
                redisTemplate.opsForHash().put(metaKey, "title", newTitle);
            }
        }
        redisTemplate.expire(metaKey, ttl());

        // 4. 会话索引按最后活跃时间排序
        String indexKey = indexKey(userId);
        redisTemplate.opsForZSet().add(indexKey, conversationId, now);
        redisTemplate.expire(indexKey, ttl());
    }

    /**
     * 列出当前用户未过期的会话，按最后活跃时间倒序。
     *
     * @param userId 当前用户 id
     * @return 会话摘要列表
     */
    public List<ConversationSummary> list(Long userId) {
        // 1. 按最后活跃时间倒序取会话 id
        Set<String> ids = redisTemplate.opsForZSet().reverseRange(indexKey(userId), 0, -1);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        List<ConversationSummary> result = new ArrayList<>();
        for (String id : ids) {
            Map<Object, Object> meta = redisTemplate.opsForHash().entries(META + id);
            if (meta.isEmpty()) {
                // 2. 元数据过期就顺手清理索引，保持列表干净
                redisTemplate.opsForZSet().remove(indexKey(userId), id);
                continue;
            }
            result.add(new ConversationSummary(
                    id,
                    String.valueOf(meta.getOrDefault("title", TITLE_DEFAULT)),
                    String.valueOf(meta.getOrDefault("modelId", "")),
                    parseLong(meta.get("createdAt")),
                    parseLong(meta.get("updatedAt")),
                    parseInt(meta.get("messageCount"))
            ));
        }
        return result;
    }

    /**
     * 读取会话最近的 limit 条消息。
     *
     * @param userId         当前用户 id
     * @param conversationId 会话 id
     * @param limit          最多返回条数
     * @return 按时间顺序排列的消息
     */
    public List<ChatMessage> messages(Long userId, String conversationId, int limit) {
        // 1. 先校验会话归属
        getOrCreate(userId, conversationId, null);

        // 2. 读出完整列表并解析
        List<ChatMessage> all = parseMessages(
                redisTemplate.opsForList().range(MSG + conversationId, 0, -1));
        if (all.isEmpty()) {
            return List.of();
        }

        // 3. 只返回最后 limit 条，避免一次把几天的消息全塞给页面
        int start = Math.max(0, all.size() - Math.max(1, limit));
        return new ArrayList<>(all.subList(start, all.size()));
    }

    /**
     * 删除会话及其消息、元数据和模型记忆。
     *
     * @param userId         当前用户 id
     * @param conversationId 会话 id
     */
    public void delete(Long userId, String conversationId) {
        // 1. 校验归属后再删
        getOrCreate(userId, conversationId, null);
        deleteById(conversationId);
        redisTemplate.opsForZSet().remove(indexKey(userId), conversationId);
    }

    /**
     * 按会话 id 删除所有相关 key。
     *
     * @param conversationId 会话 id
     */
    private void deleteById(String conversationId) {
        // 1. 消息、元数据、模型记忆一起删
        redisTemplate.delete(List.of(
                MSG + conversationId,
                META + conversationId,
                MEMORY + conversationId
        ));
    }

    /**
     * 会话相关 key 的过期时间。
     *
     * @return 3 天
     */
    private Duration ttl() {
        return properties.getTtl();
    }

    /**
     * 当前用户的会话索引 key。
     *
     * @param userId 用户 id
     * @return Redis key
     */
    private String indexKey(Long userId) {
        return INDEX + userId;
    }

    /**
     * 把消息序列化为 JSON。
     *
     * @param message 消息
     * @return JSON 字符串
     */
    private String toJson(ChatMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception ex) {
            throw new ApiException(500, "消息序列化失败", ex);
        }
    }

    /**
     * 把 Redis 里的 JSON 列表解析成消息列表，单条坏数据跳过。
     *
     * @param raw JSON 字符串列表
     * @return 解析成功的消息列表
     */
    private List<ChatMessage> parseMessages(List<String> raw) {
        // 1. 空列表直接返回
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        // 2. 单条解析失败不影响整段历史
        List<ChatMessage> result = new ArrayList<>();
        for (String json : raw) {
            try {
                result.add(objectMapper.readValue(json, ChatMessage.class));
            } catch (Exception ex) {
                log.warn("跳过一条无法解析的历史消息：{}", ex.getMessage());
            }
        }
        return result;
    }

    /**
     * 安全地把 Redis 值转成 long。
     *
     * @param value 原始值
     * @return long 值，失败时返回 0
     */
    private long parseLong(Object value) {
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * 安全地把 Redis 值转成 int。
     *
     * @param value 原始值
     * @return int 值，失败时返回 0
     */
    private int parseInt(Object value) {
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
