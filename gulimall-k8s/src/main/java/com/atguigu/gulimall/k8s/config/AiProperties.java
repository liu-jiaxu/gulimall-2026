package com.atguigu.gulimall.k8s.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * AI 模型配置，对应 application.yaml 的 gulimall.k8s.ai。
 *
 * <p>只保留纯文本对话需要的字段，不包含图片、工具、RAG 和自定义模型。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "gulimall.k8s.ai")
public class AiProperties {

    /**
     * OpenAI 兼容模型服务地址
     */
    @NotBlank
    private String baseUrl;

    /**
     * 模型服务密钥
     */
    @NotBlank
    private String apiKey;

    /**
     * 默认模型 id
     */
    @NotBlank
    private String defaultModel;

    /**
     * 采样温度；部分模型不允许传
     */
    @NotNull
    private Double temperature;

    /**
     * 普通对话超时
     */
    @NotNull
    private Duration timeout;

    /**
     * 流式对话超时
     */
    @NotNull
    private Duration streamingTimeout;

    /**
     * 调用失败重试次数
     */
    @NotNull
    @PositiveOrZero
    private Integer maxRetries;

    /**
     * 内置模型清单
     */
    @Valid
    @NotEmpty
    private List<ModelDefinition> models;

    /**
     * 单个模型的配置。
     */
    @Data
    public static class ModelDefinition {

        /**
         * 页面和接口使用的唯一标识
         */
        @NotBlank
        private String id;

        /**
         * 页面展示名；不填时使用 id
         */
        private String displayName;

        /**
         * 真正发给模型服务的 model 字段；不填时使用 id
         */
        private String modelName;

        /**
         * 是否允许发送 temperature；null 按 true 处理
         */
        private Boolean temperatureSupported;

        /**
         * 页面展示名，没配置时回退到 id。
         *
         * @return 展示名
         */
        public String displayNameOrId() {
            return displayName == null || displayName.isBlank() ? id : displayName;
        }

        /**
         * 请求体里的模型名，没配置时回退到 id。
         *
         * @return 模型名
         */
        public String modelNameOrId() {
            return modelName == null || modelName.isBlank() ? id : modelName;
        }

        /**
         * 是否允许传 temperature。
         *
         * @return 允许时为 true
         */
        public boolean supportsTemperature() {
            return !Boolean.FALSE.equals(temperatureSupported);
        }
    }
}
