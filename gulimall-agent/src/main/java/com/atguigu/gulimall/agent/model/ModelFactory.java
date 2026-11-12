package com.atguigu.gulimall.agent.model;

import com.atguigu.gulimall.agent.config.AiProperties;
import com.atguigu.gulimall.agent.exception.AgentErrorCode;
import com.atguigu.gulimall.agent.exception.AgentException;
import com.atguigu.gulimall.agent.model.listener.TracingChatModelListener;
import com.atguigu.gulimall.agent.model.strategy.ChatModelStrategy;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiImageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型工厂：按“策略 + 缓存”创建普通对话、流式对话、图片、向量模型。
 *
 * <p>为什么必须缓存：
 * 1. 每次请求 new 一个模型会重复建 HTTP 连接池，开销很大；
 * 2. LangChain4J 的模型对象本身是线程安全的；
 * 3. 自定义模型修改/删除时调用 evict 立即失效，不会继续用旧 key。
 */
@Slf4j
@Component
public class ModelFactory {

    private final AiProperties properties;
    private final TracingChatModelListener listener;
    private final Map<String, ChatModelStrategy> strategyMap = new HashMap<>();

    private final Map<String, ChatModel> chatCache = new ConcurrentHashMap<>();
    private final Map<String, StreamingChatModel> streamingCache = new ConcurrentHashMap<>();
    private volatile EmbeddingModel embeddingModel;
    /**
     * 图片尺寸/质量可不同，按组合缓存
     */
    private final Map<String, ImageModel> imageCache = new ConcurrentHashMap<>();

    /**
     * 创建模型工厂，并把 Spring 收集到的所有模型策略按 provider 注册。
     *
     * @param properties AI 相关配置
     * @param listener   记录请求、响应和异常日志的模型监听器
     * @param strategies 所有可用的模型创建策略
     */
    public ModelFactory(AiProperties properties,
                        TracingChatModelListener listener,
                        List<ChatModelStrategy> strategies) {
        this.properties = properties;
        this.listener = listener;
        // 1. 把所有策略按 provider 注册，后续工厂只认 provider
        for (ChatModelStrategy strategy : strategies) {
            strategyMap.put(strategy.provider(), strategy);
        }
    }

    /**
     * 获取普通对话模型，优先返回缓存实例，未命中时按 provider 策略创建并缓存。
     *
     * @param definition 目标模型的定义
     * @return 可复用的普通对话模型
     */
    public ChatModel chat(AiModelDefinition definition) {
        // 1. key 没配时提前报错
        requireApiKey(definition.getApiKey());

        // 2. 先查缓存，命中直接返回
        String cacheKey = cacheKey(definition);
        ChatModel cached = chatCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 2. 找对应策略并创建
        ChatModel created = strategy(definition).createChatModel(definition, listener);
        chatCache.put(cacheKey, created);
        log.info("创建对话模型 id={} model={}", definition.getId(), definition.getModelName());
        return created;
    }

    /**
     * 获取流式对话模型，优先返回缓存实例，未命中时按 provider 策略创建并缓存。
     *
     * @param definition 目标模型的定义
     * @return 可复用的流式对话模型
     */
    public StreamingChatModel streaming(AiModelDefinition definition) {
        // 1. key 没配时提前报错
        requireApiKey(definition.getApiKey());

        // 2. 流式模型单独缓存
        String cacheKey = cacheKey(definition);
        StreamingChatModel cached = streamingCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 2. 找对应策略并创建
        StreamingChatModel created = strategy(definition).createStreamingChatModel(definition, listener);
        streamingCache.put(cacheKey, created);
        log.info("创建流式模型 id={} model={}", definition.getId(), definition.getModelName());
        return created;
    }

    /**
     * 获取全局唯一的向量模型，首次调用时按配置创建并缓存。
     *
     * @return 用于文本向量化的向量模型
     */
    public EmbeddingModel embedding() {
        // 1. 向量模型全局只需要一个（text-embedding-3-small）
        if (embeddingModel != null) {
            return embeddingModel;
        }
        AiProperties.Embedding config = properties.getEmbedding();
        requireApiKey(config.getApiKey());

        // 2. 1536 维是 text-embedding-3-small 的默认维度，也是 Milvus collection 的维度
        embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(config.getApiKey())
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .dimensions(config.getDimension())
                .timeout(config.getTimeout())
                .maxRetries(config.getMaxRetries())
                .build();
        return embeddingModel;
    }

    /**
     * 使用配置中的默认尺寸和质量获取图片生成模型。
     *
     * @return 图片生成模型
     */
    public ImageModel image() {
        // 1. 默认尺寸/质量
        AiProperties.Generation config = properties.getImage().getGeneration();
        return image(config.getSize(), config.getQuality());
    }

    /**
     * 按指定尺寸和质量获取图片生成模型，相同组合只创建一次并缓存。
     *
     * @param size    图片尺寸，为空时使用配置默认值
     * @param quality 图片质量，为空时使用配置默认值
     * @return 图片生成模型
     */
    public ImageModel image(String size, String quality) {
        // 1. 参数为空时回退到配置默认值
        AiProperties.Generation config = properties.getImage().getGeneration();
        String finalSize = StringUtils.hasText(size) ? size : config.getSize();
        String finalQuality = StringUtils.hasText(quality) ? quality : config.getQuality();
        String cacheKey = finalSize + "|" + finalQuality;

        // 2. 同一尺寸/质量只建一次
        ImageModel cached = imageCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 3. gpt-image-1-mini 默认返回 b64_json；
        //    ⚠️ closeai 代理不接受 response_format 参数，传了会报 Unknown parameter，
        //    所以这里不能调用 .responseFormat(...)。
        requireApiKey(config.getApiKey());
        ImageModel created = OpenAiImageModel.builder()
                .apiKey(config.getApiKey())
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .size(finalSize)
                .quality(finalQuality)
                .timeout(config.getTimeout())
                .maxRetries(config.getMaxRetries())
                .build();
        imageCache.put(cacheKey, created);
        return created;
    }

    /**
     * 自定义模型改动后清掉缓存。
     *
     * @param modelId 发生改动的模型 id
     */
    public void evict(String modelId) {
        // 1. 缓存 key 以模型 id 开头，按前缀清理
        evictPrefix(modelId + "|");
    }

    /**
     * 按缓存 key 前缀批量清理对话模型与流式模型缓存。
     *
     * @param prefix 要清理的缓存 key 前缀
     */
    public void evictPrefix(String prefix) {
        // 1. 自定义模型删除时按 custom: 前缀批量清缓存
        chatCache.keySet().removeIf(key -> key.startsWith(prefix));
        streamingCache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * 根据模型定义中的 provider 找到对应的创建策略。
     *
     * @param definition 目标模型的定义
     * @return 匹配到的模型创建策略
     */
    private ChatModelStrategy strategy(AiModelDefinition definition) {
        // 1. provider 写错时给出可读错误，而不是空指针
        ChatModelStrategy strategy = strategyMap.get(definition.getProvider());
        if (strategy == null) {
            throw new AgentException(AgentErrorCode.MODEL_NOT_FOUND,
                    "不支持的模型协议：" + definition.getProvider());
        }
        return strategy;
    }

    /**
     * 生成模型缓存的 key，包含所有影响连接建立的字段，apiKey 只保留哈希值。
     *
     * @param definition 目标模型的定义
     * @return 用于缓存查找的 key
     */
    private String cacheKey(AiModelDefinition definition) {
        // 1. key 包含所有影响连接的字段；apiKey 只放哈希，避免明文出现在日志/内存 dump 里
        int keyHash = definition.getApiKey() == null ? 0 : definition.getApiKey().hashCode();
        return definition.getId() + "|"
                + definition.getBaseUrl() + "|"
                + definition.getModelName() + "|"
                + definition.getTemperature() + "|"
                + definition.getTimeout() + "|"
                + definition.getStreamingTimeout() + "|"
                + keyHash;
    }

    /**
     * 校验模型密钥是否已配置，未配置时在调用前抛出明确异常。
     *
     * @param apiKey 模型服务密钥
     */
    private void requireApiKey(String apiKey) {
        // 1. key 没配时在调用前就报清楚，避免发出去收到 401 再猜
        if (apiKey == null || apiKey.isBlank()) {
            throw new AgentException(AgentErrorCode.AI_NOT_CONFIGURED,
                    "AI 未配置：请设置环境变量 LangChain4J_CloseAI 或在 config/common.yaml 填 AI_CLOSEAI_API_KEY");
        }
    }
}
