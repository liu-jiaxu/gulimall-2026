package com.atguigu.gulimall.k8s.chat;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 流式对话接口，由 LangChain4J 的 AiServices 生成实现。
 */
public interface StreamingAssistant {

    /**
     * 执行一次流式对话。
     *
     * @param memoryId 会话 id，用于隔离记忆
     * @param message  用户消息
     * @return token 流
     */
    TokenStream chatStream(@MemoryId String memoryId, @UserMessage String message);
}
