package com.atguigu.gulimall.agent.config;

import com.atguigu.gulimall.agent.model.AiModelDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * AI 相关配置，对应 config/gulimall-agent.yaml 的 gulimall.ai 段。
 *
 * <p>这里不提供任何业务默认值：配置缺项时让 Spring 在启动阶段校验失败，
 * 避免“漏配 timeout / enabled 却静默按 0 或 false 运行”。
 *
 * <p>只有模型差异字段（{@link AiModelDefinition}）允许为空，
 * 它们由 ModelCatalog 从 {@link Defaults} 合并，不是漏配。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "gulimall.ai")
public class AiProperties {

    /**
     * 所有模型的默认参数
     */
    @Valid
    @NotNull
    private Defaults defaults;

    /**
     * 默认模型 id
     */
    @NotBlank
    private String defaultModel;

    /**
     * 内置模型清单，至少配置一个
     */
    @Valid
    @NotEmpty
    private List<AiModelDefinition> models;

    /**
     * 图片上传 / 识别 / 生成
     */
    @Valid
    @NotNull
    private Image image;

    /**
     * 向量化模型
     */
    @Valid
    @NotNull
    private Embedding embedding;

    /**
     * RAG 检索增强
     */
    @Valid
    @NotNull
    private Rag rag;

    /**
     * 会话与记忆
     */
    @Valid
    @NotNull
    private Conversation conversation;

    /**
     * 安全限制
     */
    @Valid
    @NotNull
    private Security security;

    @Data
    public static class Defaults {
        /**
         * 模型协议类型，当前只有 openai-compatible
         */
        @NotBlank
        private String provider;

        /**
         * 默认模型服务地址，单个模型没写 baseUrl 时用它
         */
        @NotBlank
        private String baseUrl;

        /**
         * 默认 API key，单个模型没写 key 时用它
         */
        @NotBlank
        private String apiKey;

        /**
         * 是否记录模型请求日志；配合 dev.langchain4j DEBUG 使用
         */
        @NotNull
        private Boolean logRequests;

        /**
         * 是否记录模型响应日志；内容可能包含用户问题，生产慎开
         */
        @NotNull
        private Boolean logResponses;

        /**
         * 调用失败重试次数，0 表示不重试
         */
        @NotNull
        @PositiveOrZero
        private Integer maxRetries;

        /**
         * 采样温度，0 更稳定、1 更发散；部分模型不允许传
         */
        @NotNull
        @DecimalMin("0.0")
        @DecimalMax("2.0")
        private Double temperature;

        /**
         * 普通（非流式）对话超时；工具调用要“思考 + 查库 + 总结”，当前 60 秒
         */
        @NotNull
        private Duration timeout;

        /**
         * 流式对话超时；首 token 和长回答都要覆盖，当前 90 秒
         */
        @NotNull
        private Duration streamingTimeout;

        /**
         * 模型是否默认支持流式输出
         */
        @NotNull
        private Boolean streaming;

        /**
         * 模型是否默认支持 function calling（工具）
         */
        @NotNull
        private Boolean tools;

        /**
         * 模型是否默认启用；false 时页面不展示
         */
        @NotNull
        private Boolean enabled;

        // 1. 兼容现有 isXxx() 调用，Boolean 为 null 时按 false 返回；
        //    启动阶段已经用 @NotNull 保证不会为 null

        /**
         * 是否记录模型请求日志。
         *
         * @return 开启时为 true，否则为 false
         */
        public boolean isLogRequests() {
            return Boolean.TRUE.equals(logRequests);
        }

        /**
         * 是否记录模型响应日志。
         *
         * @return 开启时为 true，否则为 false
         */
        public boolean isLogResponses() {
            return Boolean.TRUE.equals(logResponses);
        }

        /**
         * 模型是否支持流式输出。
         *
         * @return 支持时为 true，否则为 false
         */
        public boolean isStreaming() {
            return Boolean.TRUE.equals(streaming);
        }

        /**
         * 模型是否支持工具调用。
         *
         * @return 支持时为 true，否则为 false
         */
        public boolean isTools() {
            return Boolean.TRUE.equals(tools);
        }

        /**
         * 模型是否启用。
         *
         * @return 启用时为 true，否则为 false
         */
        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }
    }

    @Data
    public static class Image {
        /**
         * 看图用的视觉对话模型，必须是 supportsVision 的模型
         */
        @NotBlank
        private String visionModel;

        /**
         * 看图超时，当前 90 秒
         */
        @NotNull
        private Duration timeout;

        /**
         * 单张图片最大字节数，默认 8MB
         */
        @NotNull
        @Positive
        private Long maxUploadBytes;

        /**
         * 允许上传的 MIME 类型，按文件头识别后还要在此白名单内
         */
        @NotEmpty
        private List<String> allowedTypes;

        /**
         * 一条消息最多几张图，避免一次请求塞太多图片
         */
        @NotNull
        @Positive
        private Integer maxImagesPerMessage;

        /**
         * 图片生成模型配置
         */
        @Valid
        @NotNull
        private Generation generation;
    }

    @Data
    public static class Generation {
        /**
         * 真正发给图片服务的 model 名称，例如 gpt-image-1-mini
         */
        @NotBlank
        private String modelName;

        /**
         * 图片模型服务地址
         */
        @NotBlank
        private String baseUrl;

        /**
         * 图片模型密钥
         */
        @NotBlank
        private String apiKey;

        /**
         * 生成尺寸，例如 1024x1024
         */
        @NotBlank
        private String size;

        /**
         * 生成质量：low / medium / high
         */
        @NotBlank
        private String quality;

        /**
         * 图片生成超时，单次生成可能 1～4 分钟
         */
        @NotNull
        private Duration timeout;

        /**
         * 图片生成失败重试次数
         */
        @NotNull
        @PositiveOrZero
        private Integer maxRetries;
    }

    @Data
    public static class Embedding {
        /**
         * 向量模型名称，例如 text-embedding-3-small
         */
        @NotBlank
        private String modelName;

        /**
         * 向量维度，必须和 Milvus collection 的维度一致
         */
        @NotNull
        @Positive
        private Integer dimension;

        /**
         * 向量模型服务地址
         */
        @NotBlank
        private String baseUrl;

        /**
         * 向量模型密钥
         */
        @NotBlank
        private String apiKey;

        /**
         * 向量化超时
         */
        @NotNull
        private Duration timeout;

        /**
         * 向量化失败重试次数
         */
        @NotNull
        @PositiveOrZero
        private Integer maxRetries;
    }

    @Data
    public static class Rag {
        /**
         * 是否启用 RAG；关闭后不连接 Milvus，也不挂检索器
         */
        @NotNull
        private Boolean enabled;

        /**
         * Milvus 地址
         */
        @NotBlank
        private String host;

        /**
         * Milvus gRPC 端口，默认 19530
         */
        @NotNull
        @Positive
        private Integer port;

        /**
         * Milvus 数据库名
         */
        @NotBlank
        private String database;

        /**
         * Milvus collection 名
         */
        @NotBlank
        private String collection;

        /**
         * 向量维度，必须和 embedding 模型一致
         */
        @NotNull
        @Positive
        private Integer dimension;

        /**
         * 每次检索最多返回几个片段
         */
        @NotNull
        @Positive
        private Integer maxResults;

        /**
         * 相似度阈值，低于它的片段直接丢弃；0～1 之间
         */
        @NotNull
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private Double minScore;

        /**
         * 启动时是否自动检查/重建索引
         */
        @NotNull
        private Boolean ingestOnStartup;

        /**
         * 每个文档片段最大字符数
         */
        @NotNull
        @Positive
        private Integer chunkMaxChars;

        /**
         * 相邻片段重叠字符数，避免把一句话切断后丢上下文
         */
        @NotNull
        @PositiveOrZero
        private Integer chunkOverlapChars;

        /**
         * RAG 文档根目录，默认 rag；下面按 public/admin 子目录区分可见范围
         */
        @NotBlank
        private String docLocation;

        /**
         * Milvus 用户名，开启认证后必填
         */
        @NotBlank
        private String username;

        /**
         * Milvus 密码，开启认证后必填
         */
        @NotBlank
        private String password;

        // 1. 兼容现有 isXxx() 调用

        /**
         * 是否启用 RAG。
         *
         * @return 启用时为 true，否则为 false
         */
        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }

        /**
         * 启动时是否自动检查或重建索引。
         *
         * @return 需要自动处理时为 true，否则为 false
         */
        public boolean isIngestOnStartup() {
            return Boolean.TRUE.equals(ingestOnStartup);
        }
    }

    @Data
    public static class Conversation {
        /**
         * 会话/消息/图片元数据的 Redis 过期时间，滑动续期，当前 3 天
         */
        @NotNull
        private Duration ttl;

        /**
         * 模型记忆窗口保留多少条消息
         */
        @NotNull
        @Positive
        private Integer memoryMaxMessages;

        /**
         * 页面一次加载多少条 UI 消息
         */
        @NotNull
        @Positive
        private Integer uiPageSize;

        /**
         * 单条用户消息最大字符数
         */
        @NotNull
        @Positive
        private Integer maxMessageChars;

        /**
         * 自定义提示词最大字符数
         */
        @NotNull
        @Positive
        private Integer maxCustomPromptChars;

        /**
         * 每个身份最多保留多少个会话，超出淘汰最久未用的
         */
        @NotNull
        @Positive
        private Integer maxConversationsPerPrincipal;
    }

    @Data
    public static class Security {
        /**
         * 自定义模型的安全限制
         */
        @Valid
        @NotNull
        private CustomModel customModel;
    }

    @Data
    public static class CustomModel {
        /**
         * 是否允许用户添加自定义模型
         */
        @NotNull
        private Boolean enabled;

        /**
         * 每个身份最多添加几个自定义模型
         */
        @NotNull
        @Positive
        private Integer maxPerPrincipal;

        /**
         * 允许的模型协议白名单，当前只有 openai-compatible
         */
        @NotEmpty
        private List<String> allowedProviders;

        /**
         * 是否强制 baseUrl 使用 https
         */
        @NotNull
        private Boolean requireHttps;

        /**
         * 是否拒绝内网/本机地址，防止 SSRF
         */
        @NotNull
        private Boolean denyPrivateAddresses;

        // 1. 兼容现有 isXxx() 调用

        /**
         * 是否允许用户添加自定义模型。
         *
         * @return 允许时为 true，否则为 false
         */
        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }

        /**
         * 是否强制自定义模型的 baseUrl 使用 https。
         *
         * @return 强制时为 true，否则为 false
         */
        public boolean isRequireHttps() {
            return Boolean.TRUE.equals(requireHttps);
        }

        /**
         * 是否拒绝内网和本机地址。
         *
         * @return 拒绝时为 true，否则为 false
         */
        public boolean isDenyPrivateAddresses() {
            return Boolean.TRUE.equals(denyPrivateAddresses);
        }
    }
}
