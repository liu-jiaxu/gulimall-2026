package com.atguigu.gulimall.agent.chat.dto;

import lombok.Data;

/**
 * 普通对话的返回。
 */
@Data
public class ChatResponseDto {

    /**
     * 本次对话所属会话 id
     */
    private String conversationId;

    /**
     * 本次 AI 回复的消息 id，前端用来定位/更新气泡
     */
    private String messageId;

    /**
     * AI 回复正文
     */
    private String content;

    /**
     * 实际使用的模型 id
     */
    private String modelId;

    /**
     * AI 回复时间戳（毫秒）
     */
    private Long createdAt;

    /**
     * 本次请求消耗的输入 token，模型没返回时可能为空
     */
    private Integer inputTokens;

    /**
     * 本次请求消耗的输出 token，模型没返回时可能为空
     */
    private Integer outputTokens;
}
