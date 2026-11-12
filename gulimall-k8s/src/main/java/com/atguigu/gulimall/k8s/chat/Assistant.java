package com.atguigu.gulimall.k8s.chat;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

/**
 * 普通对话接口，由 LangChain4J 的 AiServices 生成实现。
 *
 * <p>没有工具、没有 RAG，只把用户消息和 Redis 记忆窗口交给模型。
 */
public interface Assistant {

    /**
     * 执行一次普通对话。
     *
     * @param memoryId 会话 id，用于隔离记忆
     * @param message  用户消息
     * @return 模型回复
     */
    String chat(@MemoryId String memoryId, @UserMessage String message);
}
