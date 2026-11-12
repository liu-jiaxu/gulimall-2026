package com.atguigu.gulimall.agent.chat.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 流式对话的 AI Service 接口。
 *
 * <p>返回 TokenStream，由 Controller 转成 SSE 的 delta 事件。
 */
public interface ShopStreamingAssistant {

    /**
     * 以流式方式发送用户消息，返回可逐段消费的 TokenStream。
     *
     * @param memoryId 会话记忆标识，用于隔离多会话上下文
     * @param message  用户消息内容
     * @return 流式响应 TokenStream，用于接收增量 token 和结束回调
     */
    TokenStream chatStream(@MemoryId String memoryId, @UserMessage String message);
}
