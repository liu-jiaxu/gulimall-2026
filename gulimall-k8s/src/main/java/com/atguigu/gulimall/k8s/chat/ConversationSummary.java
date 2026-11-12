package com.atguigu.gulimall.k8s.chat;

/**
 * 会话列表里的一项摘要。
 *
 * @param id           会话 id
 * @param title        会话标题，取首条用户消息
 * @param modelId      会话使用的模型 id
 * @param createdAt    创建时间戳
 * @param updatedAt    最后活跃时间戳
 * @param messageCount 消息条数
 */
public record ConversationSummary(
        String id,
        String title,
        String modelId,
        long createdAt,
        long updatedAt,
        int messageCount
) {
}
