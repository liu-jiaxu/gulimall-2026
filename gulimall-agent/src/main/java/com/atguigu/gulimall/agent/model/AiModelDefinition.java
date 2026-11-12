package com.atguigu.gulimall.agent.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.Duration;

/**
 * 一个可用的对话模型定义。
 *
 * <p>yaml 里只写与 defaults 不同的项，ModelCatalog 会负责补齐默认值。
 * 自定义模型（用户自己填 baseUrl/apiKey/modelName）也转成这个对象，
 * 这样工厂、页面模型列表、缓存可以完全复用同一套逻辑。
 */
@Data
public class AiModelDefinition {

    /**
     * 页面和接口里使用的唯一 id，例如 gpt-6.1-sol 或 custom:12
     */
    @NotBlank
    private String id;

    /**
     * 页面展示名
     */
    private String displayName;

    /**
     * 真正发给 closeai 的 model 字段
     */
    @NotBlank
    private String modelName;

    /**
     * 目前统一是 openai-compatible，后续要接别的协议再加策略
     */
    private String provider = "openai-compatible";

    private String baseUrl;

    private String apiKey;

    /**
     * 是否支持图片输入（看图）
     */
    private boolean vision;

    /**
     * 是否支持流式（null 表示继承 defaults）
     */
    private Boolean streaming;

    /**
     * 是否支持 function calling（null 表示继承 defaults）
     */
    private Boolean tools;

    /**
     * 是否启用（null 表示继承 defaults）
     */
    private Boolean enabled;

    /**
     * 排序，数字越小越靠前
     */
    private int sort;

    private Double temperature;

    /**
     * 是否把 temperature 发给模型。
     * 部分模型（GPT-6.1 系列、Claude 5.5、Kimi K3.1）只接受默认值，
     * 传 0.7 会直接 400，所以这些模型要设为 false。
     */
    private Boolean temperatureSupported = true;

    private Integer maxRetries;

    private Duration timeout;

    private Duration streamingTimeout;

    private Boolean logRequests;

    private Boolean logResponses;

    /**
     * 是否为用户自定义模型（页面要标注）
     */
    private boolean custom;

    /**
     * 自定义模型在 MySQL 里的主键，删除/编辑时用
     */
    private Long customModelId;

    /**
     * 复制一份并替换超时时间，用于“看图”这种需要更长超时的场景。
     * 不改原对象，避免影响普通对话的超时配置。
     *
     * @param timeout 复制后对象要使用的超时时间
     * @return 替换了超时时间的新模型定义对象
     */
    public AiModelDefinition withTimeout(Duration timeout) {
        AiModelDefinition copy = new AiModelDefinition();
        copy.id = id;
        copy.displayName = displayName;
        copy.modelName = modelName;
        copy.provider = provider;
        copy.baseUrl = baseUrl;
        copy.apiKey = apiKey;
        copy.vision = vision;
        copy.streaming = streaming;
        copy.tools = tools;
        copy.enabled = enabled;
        copy.sort = sort;
        copy.temperature = temperature;
        copy.temperatureSupported = temperatureSupported;
        copy.maxRetries = maxRetries;
        copy.timeout = timeout;
        copy.streamingTimeout = streamingTimeout;
        copy.logRequests = logRequests;
        copy.logResponses = logResponses;
        copy.custom = custom;
        copy.customModelId = customModelId;
        return copy;
    }
}
