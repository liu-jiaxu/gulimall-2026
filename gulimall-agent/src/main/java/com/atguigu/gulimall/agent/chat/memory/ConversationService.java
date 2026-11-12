package com.atguigu.gulimall.agent.chat.memory;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.chat.dto.ChatMessageDto;
import com.atguigu.gulimall.agent.chat.dto.ChatSearchResult;
import com.atguigu.gulimall.agent.chat.dto.ConversationSummary;
import com.atguigu.gulimall.agent.config.AiProperties;
import com.atguigu.gulimall.agent.exception.AgentErrorCode;
import com.atguigu.gulimall.agent.exception.AgentException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;

/**
 * 多会话聊天记录的 Redis 读写。
 *
 * <p>Key 设计：
 * <ul>
 *   <li>gulimall:ai:conv:index:{USER:1}     ZSET，存会话 id，score 为最后活跃时间</li>
 *   <li>gulimall:ai:conv:meta:{convId}      HASH，标题、模型、创建时间、消息数</li>
 *   <li>gulimall:ai:conv:msg:{convId}       LIST，完整 UI 消息（JSON）</li>
 *   <li>gulimall:ai:memory:{convId}         模型记忆窗口（由 RedisChatMemoryStore 维护）</li>
 * </ul>
 * 全部 key 每次写入都会续期 3 天，实现“最近 3 天”的滑动过期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final String PREFIX = "gulimall:ai:";
    private static final String INDEX = PREFIX + "conv:index:";
    private static final String META = PREFIX + "conv:meta:";
    private static final String MSG = PREFIX + "conv:msg:";
    private static final String MEMORY = RedisChatMemoryStore.PREFIX;
    private static final String TITLE_DEFAULT = "新对话";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;

    /**
     * 新建会话并写入元数据与会话索引，超过会话上限时先淘汰最久未使用的会话。
     *
     * @param principal 当前登录身份，用于确定会话归属和索引
     * @param modelId   会话使用的模型标识，可为空
     * @return 新生成的会话 id
     */
    public String create(Principal principal, String modelId) {
        // 1. 超过会话上限时，先淘汰最久没用的一个
        String indexKey = indexKey(principal);
        Long count = redisTemplate.opsForZSet().zCard(indexKey);
        int max = properties.getConversation().getMaxConversationsPerPrincipal();
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
        Map<String, String> meta = Map.of(
                "ownerType", principal.type().name(),
                "ownerId", String.valueOf(principal.id()),
                "title", TITLE_DEFAULT,
                "modelId", modelId == null ? "" : modelId,
                "createdAt", String.valueOf(now),
                "updatedAt", String.valueOf(now),
                "messageCount", "0"
        );
        String metaKey = META + conversationId;
        redisTemplate.opsForHash().putAll(metaKey, meta);
        redisTemplate.expire(metaKey, ttl());

        // 3. 写进用户会话索引
        redisTemplate.opsForZSet().add(indexKey, conversationId, now);
        redisTemplate.expire(indexKey, ttl());
        return conversationId;
    }

    /**
     * 取会话；id 为空时新建。会校验会话归属，防止改 URL 看别人的对话。
     *
     * @param principal      当前登录身份，用于校验会话归属
     * @param conversationId 会话 id，为空时新建会话
     * @param modelId        新建会话时使用的模型标识，已存在时忽略
     * @return 可用的会话 id
     */
    public String getOrCreate(Principal principal, String conversationId, String modelId) {
        if (!StringUtils.hasText(conversationId)) {
            return create(principal, modelId);
        }

        // 1. 元数据不存在 = 过期或删除了
        Map<Object, Object> meta = redisTemplate.opsForHash().entries(META + conversationId);
        if (meta.isEmpty()) {
            throw new AgentException(AgentErrorCode.MODEL_NOT_FOUND, "会话不存在或已过期，请新建对话");
        }

        // 2. 只能访问自己的会话
        if (!principal.type().name().equals(String.valueOf(meta.get("ownerType")))
                || !String.valueOf(principal.id()).equals(String.valueOf(meta.get("ownerId")))) {
            throw new AgentException(AgentErrorCode.NOT_LOGIN, "无权访问该会话");
        }
        return conversationId;
    }

    /**
     * 追加一条消息到会话历史，并更新会话的消息数、最后活跃时间、模型和标题。
     *
     * @param principal      当前登录身份
     * @param conversationId 目标会话 id
     * @param message        待追加的聊天消息
     */
    public void append(Principal principal, String conversationId, ChatMessageDto message) {
        // 1. 写完整消息列表（UI 历史和搜索用）
        String msgKey = MSG + conversationId;
        redisTemplate.opsForList().rightPush(msgKey, toJson(message));
        redisTemplate.expire(msgKey, ttl());

        // 2. 更新会话元数据：消息数、最后活跃时间、模型
        String metaKey = META + conversationId;
        long now = System.currentTimeMillis();
        redisTemplate.opsForHash().increment(metaKey, "messageCount", 1);
        redisTemplate.opsForHash().put(metaKey, "updatedAt", String.valueOf(now));
        if (StringUtils.hasText(message.getModelId())) {
            redisTemplate.opsForHash().put(metaKey, "modelId", message.getModelId());
        }

        // 3. 首条用户消息作为会话标题
        Object title = redisTemplate.opsForHash().get(metaKey, "title");
        if (TITLE_DEFAULT.equals(title) && "user".equals(message.getRole())) {
            String content = message.getContent() == null ? "" : message.getContent().trim();
            String newTitle = content.length() > 20 ? content.substring(0, 20) : content;
            if (StringUtils.hasText(newTitle)) {
                redisTemplate.opsForHash().put(metaKey, "title", newTitle);
            }
        }
        redisTemplate.expire(metaKey, ttl());

        // 4. 会话索引的 score 也更新，历史列表按最近使用排序
        String indexKey = indexKey(principal);
        redisTemplate.opsForZSet().add(indexKey, conversationId, now);
        redisTemplate.expire(indexKey, ttl());
    }

    /**
     * 列出当前身份下所有未过期会话，按最后活跃时间倒序排列。
     *
     * @param principal 当前登录身份
     * @return 会话摘要列表，按最近活跃时间倒序
     */
    public List<ConversationSummary> list(Principal principal) {
        // 1. 按最后活跃时间倒序取会话 id
        Set<String> ids = redisTemplate.opsForZSet().reverseRange(indexKey(principal), 0, -1);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        List<ConversationSummary> result = new ArrayList<>();
        for (String id : ids) {
            Map<Object, Object> meta = redisTemplate.opsForHash().entries(META + id);
            if (meta.isEmpty()) {
                // 2. 元数据过期了就顺手清掉索引，保持列表干净
                redisTemplate.opsForZSet().remove(indexKey(principal), id);
                continue;
            }
            ConversationSummary summary = new ConversationSummary();
            summary.setId(id);
            summary.setTitle(String.valueOf(meta.getOrDefault("title", TITLE_DEFAULT)));
            summary.setModelId(String.valueOf(meta.getOrDefault("modelId", "")));
            summary.setCreatedAt(parseLong(meta.get("createdAt")));
            summary.setUpdatedAt(parseLong(meta.get("updatedAt")));
            summary.setMessageCount(parseInt(meta.get("messageCount")));
            result.add(summary);
        }
        return result;
    }

    /**
     * 分页读取会话中的历史消息，支持按指定消息 id 向前加载更早内容。
     *
     * @param principal      当前登录身份
     * @param conversationId 会话 id
     * @param limit          本次最多返回的消息条数
     * @param beforeId       加载基准消息 id，只返回该消息之前的内容，为空时从最新消息取
     * @return 按时间顺序排列的聊天消息列表
     */
    public List<ChatMessageDto> messages(Principal principal, String conversationId, int limit, String beforeId) {
        // 1. 先校验归属
        getOrCreate(principal, conversationId, null);

        // 2. 读出完整列表
        List<String> raw = redisTemplate.opsForList().range(MSG + conversationId, 0, -1);
        List<ChatMessageDto> all = parseMessages(raw);
        if (all.isEmpty()) {
            return List.of();
        }

        // 3. before 用于“加载更早”：只取这条消息之前的内容
        int end = all.size();
        if (StringUtils.hasText(beforeId)) {
            for (int i = 0; i < all.size(); i++) {
                if (beforeId.equals(all.get(i).getId())) {
                    end = i;
                    break;
                }
            }
        }

        int start = Math.max(0, end - Math.max(1, limit));
        return new ArrayList<>(all.subList(start, end));
    }

    /**
     * 在指定会话内按关键词做大小写不敏感的子串匹配。
     *
     * @param principal      当前登录身份
     * @param conversationId 会话 id
     * @param keyword        搜索关键词
     * @param limit          最多返回的匹配条数
     * @return 命中的聊天消息列表
     */
    public List<ChatMessageDto> search(Principal principal, String conversationId, String keyword, int limit) {
        // 1. 先校验归属
        getOrCreate(principal, conversationId, null);

        // 2. 对当前会话做大小写不敏感的子串匹配
        String needle = keyword == null ? "" : keyword.trim().toLowerCase();
        if (needle.isEmpty()) {
            return List.of();
        }
        List<ChatMessageDto> all = parseMessages(redisTemplate.opsForList().range(MSG + conversationId, 0, -1));
        List<ChatMessageDto> result = new ArrayList<>();
        for (ChatMessageDto message : all) {
            if (message.getContent() != null && message.getContent().toLowerCase().contains(needle)) {
                result.add(message);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 在 3 天内所有会话中搜索关键词，返回带会话信息的搜索结果。
     *
     * @param principal 当前登录身份
     * @param keyword   搜索关键词
     * @param limit     最多返回的匹配条数
     * @return 跨会话的搜索结果列表
     */
    public List<ChatSearchResult> searchAll(Principal principal, String keyword, int limit) {
        // 1. 遍历 3 天内所有会话，逐个做关键词匹配
        List<ChatSearchResult> result = new ArrayList<>();
        for (ConversationSummary summary : list(principal)) {
            for (ChatMessageDto message : search(principal, summary.getId(), keyword, limit)) {
                ChatSearchResult item = new ChatSearchResult();
                item.setConversationId(summary.getId());
                item.setConversationTitle(summary.getTitle());
                item.setMessage(message);
                result.add(item);
                if (result.size() >= limit) {
                    return result;
                }
            }
        }
        return result;
    }

    /**
     * 删除会话及其消息、元数据、模型记忆，并将会话移出索引。
     *
     * @param principal      当前登录身份
     * @param conversationId 待删除的会话 id
     */
    public void delete(Principal principal, String conversationId) {
        // 1. 校验归属后再删
        getOrCreate(principal, conversationId, null);
        deleteById(conversationId);
        redisTemplate.opsForZSet().remove(indexKey(principal), conversationId);
    }

    /**
     * 按会话 id 删除消息列表、元数据和模型记忆。
     *
     * @param conversationId 待删除的会话 id
     */
    private void deleteById(String conversationId) {
        // 1. 删除消息、元数据、模型记忆
        redisTemplate.delete(List.of(MSG + conversationId, META + conversationId, MEMORY + conversationId));
    }

    /**
     * 返回会话相关 key 的过期时间。
     *
     * @return 配置的会话过期时长
     */
    private Duration ttl() {
        return properties.getConversation().getTtl();
    }

    /**
     * 构造当前身份的会话索引 key。
     *
     * @param principal 当前登录身份
     * @return 会话索引的 Redis key
     */
    private String indexKey(Principal principal) {
        return INDEX + principal.subject();
    }

    /**
     * 将聊天消息序列化为 JSON 字符串。
     *
     * @param message 待序列化的聊天消息
     * @return 序列化后的 JSON 字符串
     */
    private String toJson(ChatMessageDto message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception ex) {
            throw new AgentException(AgentErrorCode.MESSAGE_INVALID, "消息序列化失败", ex);
        }
    }

    /**
     * 将 JSON 字符串列表解析为聊天消息列表，单条解析失败会跳过。
     *
     * @param raw 原始 JSON 字符串列表
     * @return 解析成功的聊天消息列表
     */
    private List<ChatMessageDto> parseMessages(List<String> raw) {
        // 1. 空列表直接返回
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        // 2. 单条坏数据跳过，不影响整段历史
        List<ChatMessageDto> result = new ArrayList<>();
        for (String json : raw) {
            try {
                result.add(objectMapper.readValue(json, ChatMessageDto.class));
            } catch (Exception ex) {
                log.warn("跳过一条无法解析的历史消息：{}", ex.getMessage());
            }
        }
        return result;
    }

    /**
     * 将 Redis 中的值安全转换为 long，无法解析时返回 0。
     *
     * @param value 待转换的原始值
     * @return 转换后的 long 值，失败时为 0
     */
    private long parseLong(Object value) {
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * 将 Redis 中的值安全转换为 int，无法解析时返回 0。
     *
     * @param value 待转换的原始值
     * @return 转换后的 int 值，失败时为 0
     */
    private int parseInt(Object value) {
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
