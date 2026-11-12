package com.atguigu.gulimall.agent.settings.dto;

import lombok.Data;

/**
 * 返回给前端的自定义模型信息，只含掩码，不含明文 apiKey。
 */
@Data
public class CustomModelView {

    /**
     * 数据库主键，编辑/删除时用
     */
    private Long id;

    /**
     * 页面选择的模型 id，形如 custom:12
     */
    private String modelId;

    /**
     * 页面展示名
     */
    private String displayName;

    /**
     * 模型协议，当前 openai-compatible
     */
    private String provider;

    /**
     * 真正发给模型服务的 model 字段
     */
    private String modelName;

    /**
     * 模型服务地址
     */
    private String baseUrl;

    /**
     * apiKey 掩码，例如 sk-****abcd；绝不会返回明文
     */
    private String apiKeyMask;

    /**
     * 是否使用公共 closeai key
     */
    private Boolean useSharedKey;

    /**
     * 是否支持图片输入
     */
    private Boolean supportsVision;

    /**
     * 是否支持流式输出
     */
    private Boolean supportsStreaming;

    /**
     * 是否启用
     */
    private Boolean enabled;
}
