package com.atguigu.gulimall.k8s.ai;

import com.atguigu.gulimall.k8s.config.AiProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按模型配置创建并缓存 LangChain4J 模型对象。
 *
 * <p>模型对象本身线程安全，创建一次可以重复用；
 * 每次请求都 new 会重复建 HTTP 连接池，没必要。
 */
@Component
@RequiredArgsConstructor
public class ModelFactory {

    private final AiProperties properties;

    /**
     * 普通对话模型缓存，key 为模型 id
     */
    private final Map<String, ChatModel> chatCache = new ConcurrentHashMap<>();

    /**
     * 流式对话模型缓存，key 为模型 id
     */
    private final Map<String, StreamingChatModel> streamingCache = new ConcurrentHashMap<>();

    /**
     * 获取普通对话模型。
     *
     * @param definition 模型定义
     * @return 可复用的对话模型
     */
    public ChatModel chat(AiProperties.ModelDefinition definition) {
        // 1. 同一个模型只创建一次
        return chatCache.computeIfAbsent(definition.getId(), id -> buildChat(definition));
    }

    /**
     * 获取流式对话模型。
     *
     * @param definition 模型定义
     * @return 可复用的流式模型
     */
    public StreamingChatModel streaming(AiProperties.ModelDefinition definition) {
        // 1. 流式模型单独缓存
        return streamingCache.computeIfAbsent(definition.getId(), id -> buildStreaming(definition));
    }

    /**
     * 构建普通对话模型。
     *
     * @param definition 模型定义
     * @return 对话模型
     */
    private ChatModel buildChat(AiProperties.ModelDefinition definition) {
        // 1. 统一使用 OpenAI 兼容协议，只改 baseUrl/apiKey/modelName
        var builder = OpenAiChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(definition.modelNameOrId())
                .maxRetries(properties.getMaxRetries())
                .timeout(properties.getTimeout())
                .logRequests(false)
                .logResponses(false);

        // 2. 只有模型允许时才传 temperature；GPT/Claude/Kimi 传了会 400
        if (definition.supportsTemperature()) {
            builder.temperature(properties.getTemperature());
        }
        return builder.build();
    }

    /**
     * 构建流式对话模型。
     *
     * @param definition 模型定义
     * @return 流式模型
     */
    private StreamingChatModel buildStreaming(AiProperties.ModelDefinition definition) {
        // 1. 流式模型使用更长的超时；1.0.1 的流式 builder 没有 maxRetries
        var builder = OpenAiStreamingChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(definition.modelNameOrId())
                .timeout(properties.getStreamingTimeout())
                .logRequests(false)
                .logResponses(false);

        // 2. 同样只在模型允许时传 temperature
        if (definition.supportsTemperature()) {
            builder.temperature(properties.getTemperature());
        }
        return builder.build();
    }
}
