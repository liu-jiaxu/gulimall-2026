package com.atguigu.gulimall.agent.chat.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis 里存的一条 UI 消息。
 *
 * <p>与 LangChain4J 的 ChatMessage 分开存：这里保留完整历史给页面/搜索，
 * 模型记忆只保留最近窗口，两者互不影响。
 */
@Data
public class ChatMessageDto {

    private String id;

    /**
     * user / assistant
     */
    private String role;

    private String content;

    private String modelId;

    private List<String> imageUrls = new ArrayList<>();

    /**
     * 上传图片的短 id。
     *
     * <p>imageUrls 只够展示；按上方向键回显上一问时，前端要拿 imageId 重新发送，
     * 所以两者必须一起落 Redis。
     */
    private List<String> imageIds = new ArrayList<>();

    /**
     * ok / error
     */
    private String status = "ok";

    private Long createdAt;

    private Integer inputTokens;

    private Integer outputTokens;
}
