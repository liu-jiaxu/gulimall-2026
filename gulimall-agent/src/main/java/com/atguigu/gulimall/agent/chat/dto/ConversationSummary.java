package com.atguigu.gulimall.agent.chat.dto;

import lombok.Data;

/**
 * 历史会话列表里的一项。
 */
@Data
public class ConversationSummary {

    /**
     * 会话 id
     */
    private String id;

    /**
     * 会话标题：默认取第一条用户消息的前 20 个字
     */
    private String title;

    /**
     * 会话创建时/最后使用的模型 id
     */
    private String modelId;

    /**
     * 会话创建时间戳（毫秒）
     */
    private Long createdAt;

    /**
     * 最后活跃时间戳（毫秒），列表按它倒序
     */
    private Long updatedAt;

    /**
     * 会话里一共有多少条消息
     */
    private Integer messageCount;
}
