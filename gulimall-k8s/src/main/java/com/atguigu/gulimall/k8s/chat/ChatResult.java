package com.atguigu.gulimall.k8s.chat;

/**
 * 一次普通对话的返回结果。
 *
 * @param conversationId 会话 id
 * @param messageId      AI 消息 id
 * @param content        AI 回复正文
 * @param modelId        使用的模型 id
 * @param createdAt      创建时间戳
 * @param inputTokens    输入 token 数，拿不到时为 null
 * @param outputTokens   输出 token 数，拿不到时为 null
 */
public record ChatResult(
        String conversationId,
        String messageId,
        String content,
        String modelId,
        long createdAt,
        Integer inputTokens,
        Integer outputTokens
) {
}
