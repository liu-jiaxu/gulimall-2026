package com.atguigu.gulimall.k8s.chat;

/**
 * 聊天请求体。
 *
 * @param conversationId 会话 id，为空时新建会话
 * @param modelId        模型 id，为空时使用默认模型
 * @param message        用户消息
 */
public record ChatRequest(String conversationId, String modelId, String message) {
}
