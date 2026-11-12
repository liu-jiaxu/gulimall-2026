package com.atguigu.gulimall.agent.chat.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

/**
 * 普通对话的 AI Service 接口。
 *
 * <p>memoryId 传会话 id，LangChain4J 按它隔离多会话记忆。
 */
public interface ShopAssistant {

    /**
     * 发送一条用户消息并返回模型的完整回复。
     *
     * @param memoryId 会话记忆标识，用于隔离多会话上下文
     * @param message  用户消息内容
     * @return 模型的完整回复文本
     */
    String chat(@MemoryId String memoryId, @UserMessage String message);
}
