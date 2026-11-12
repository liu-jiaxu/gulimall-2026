package com.atguigu.gulimall.agent.chat.dto;

import lombok.Data;

/**
 * 生成图片请求。
 */
@Data
public class GenerateImageRequest {

    private String prompt;

    /**
     * 可选：生成完成后把图片消息写进这个会话
     */
    private String conversationId;

    /**
     * 1024x1024 / 1024x1536 / 1536x1024，不传用配置默认值
     */
    private String size;

    /**
     * low / medium / high，不传用配置默认值
     */
    private String quality;
}
