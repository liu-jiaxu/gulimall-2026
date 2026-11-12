package com.atguigu.gulimall.k8s.chat;

/**
 * 一条聊天消息，存 Redis 列表并返回给前端。
 *
 * @param id           消息 id
 * @param role         user 或 assistant
 * @param content      消息正文
 * @param modelId      生成这条消息的模型 id；用户消息为空
 * @param createdAt    创建时间戳（毫秒）
 * @param inputTokens  输入 token 数，拿不到时为 null
 * @param outputTokens 输出 token 数，拿不到时为 null
 */
public record ChatMessage(
        String id,
        String role,
        String content,
        String modelId,
        long createdAt,
        Integer inputTokens,
        Integer outputTokens
) {

    /**
     * 构造一条用户消息。
     *
     * @param id        消息 id
     * @param content   消息正文
     * @param createdAt 创建时间戳
     * @return 用户消息
     */
    public static ChatMessage user(String id, String content, long createdAt) {
        // 1. 用户消息没有模型和 token 用量
        return new ChatMessage(id, "user", content, null, createdAt, null, null);
    }

    /**
     * 构造一条 AI 消息。
     *
     * @param id           消息 id
     * @param content      消息正文
     * @param modelId      模型 id
     * @param createdAt    创建时间戳
     * @param inputTokens  输入 token 数，可为 null
     * @param outputTokens 输出 token 数，可为 null
     * @return AI 消息
     */
    public static ChatMessage assistant(String id, String content, String modelId,
                                        long createdAt, Integer inputTokens, Integer outputTokens) {
        // 1. AI 消息记录模型 id 和 token 用量，方便页面展示
        return new ChatMessage(id, "assistant", content, modelId, createdAt, inputTokens, outputTokens);
    }
}
