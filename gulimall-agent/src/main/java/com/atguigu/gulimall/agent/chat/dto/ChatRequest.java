package com.atguigu.gulimall.agent.chat.dto;

import lombok.Data;

import java.util.List;

/**
 * 前端发起的聊天请求。
 */
@Data
public class ChatRequest {

    /**
     * 为空表示新建会话
     */
    private String conversationId;

    private String message;

    /**
     * 为空使用默认模型
     */
    private String modelId;

    /**
     * 已上传图片的 id（阶段 4 使用）
     */
    private List<String> imageIds;
}
