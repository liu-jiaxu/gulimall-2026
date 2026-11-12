package com.atguigu.gulimall.agent.chat.dto;

import lombok.Data;

/**
 * 跨会话关键词搜索的一条结果。
 */
@Data
public class ChatSearchResult {

    /**
     * 命中消息所属会话 id，点击结果时用它跳转
     */
    private String conversationId;

    /**
     * 会话标题，列表里展示“来自哪个对话”
     */
    private String conversationTitle;

    /**
     * 命中的那条消息（包含 role/content/imageUrls 等）
     */
    private ChatMessageDto message;
}
