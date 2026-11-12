package com.atguigu.gulimall.agent.settings.dto;

import lombok.Data;

/**
 * 用户录入自定义模型时提交的信息。
 *
 * <p>apiKey 只在这一次请求里以明文传输（HTTPS），落库前立即 AES 加密。
 */
@Data
public class CustomModelRequest {

    /**
     * 页面展示名，例如“我的 GPT”
     */
    private String displayName;

    /**
     * 模型协议，当前只允许 openai-compatible
     */
    private String provider = "openai-compatible";

    /**
     * 真正发给模型服务的 model 字段，例如 gpt-4o-mini
     */
    private String modelName;

    /**
     * 模型服务地址，必须是 https，且不能指向内网
     */
    private String baseUrl;

    /**
     * 模型密钥；只在本次请求明文传输，落库前 AES-GCM 加密
     */
    private String apiKey;

    /**
     * true 时使用 common.yaml 里的公共 closeai key，不再单独保存 apiKey
     */
    private Boolean useSharedKey = false;

    /**
     * 是否支持图片输入
     */
    private Boolean supportsVision = false;

    /**
     * 是否支持流式输出
     */
    private Boolean supportsStreaming = true;

    /**
     * 采样温度，0～2
     */
    private Double temperature = 0.7;

    /**
     * 调用超时秒数，5～120
     */
    private Integer timeoutSeconds = 10;
}
