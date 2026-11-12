package com.atguigu.gulimall.agent.model.strategy;

import com.atguigu.gulimall.agent.model.AiModelDefinition;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OpenAI 兼容协议的模型策略。
 *
 * <p>closeai 平台（https://api.openai-proxy.org/v1）把 GPT / Claude / Gemini /
 * DeepSeek / Qwen / GLM / Kimi 都包装成 OpenAI 协议，所以这里只改 baseUrl、
 * apiKey、modelName 三个值，其它参数全部按需求统一设置。
 */
@Component
public class OpenAiCompatibleChatModelStrategy implements ChatModelStrategy {

    public static final String PROVIDER = "openai-compatible";

    /**
     * 返回该策略对应的 provider 名称。
     *
     * @return OpenAI 兼容协议的 provider 名称
     */
    @Override
    public String provider() {
        return PROVIDER;
    }

    /**
     * 按 OpenAI 兼容协议创建普通对话模型，并按配置设置超时、重试、温度和监听器。
     *
     * @param definition 目标模型的定义
     * @param listener   记录请求、响应和异常日志的模型监听器
     * @return 构建完成的普通对话模型
     */
    @Override
    public ChatModel createChatModel(AiModelDefinition definition, ChatModelListener listener) {
        // 1. 普通对话：使用配置的超时（当前 60 秒）、失败重试 2 次、温度 0.7
        var builder = OpenAiChatModel.builder()
                .apiKey(definition.getApiKey())
                .baseUrl(definition.getBaseUrl())
                .modelName(definition.getModelName())
                .maxRetries(definition.getMaxRetries())
                .timeout(definition.getTimeout())
                .logRequests(definition.getLogRequests())
                .logResponses(definition.getLogResponses());

        // 2. 只有模型支持时才传 temperature；GPT-6.1 系列/Claude/Kimi 传 0.7 会 400
        if (!Boolean.FALSE.equals(definition.getTemperatureSupported())) {
            builder.temperature(definition.getTemperature());
        }

        // 3. 监听器用于记录 TraceID、耗时和 token 用量
        if (listener != null) {
            builder.listeners(List.of(listener));
        }
        return builder.build();
    }

    /**
     * 按 OpenAI 兼容协议创建流式对话模型，使用更长的流式超时并挂载监听器。
     *
     * @param definition 目标模型的定义
     * @param listener   记录请求、响应和异常日志的模型监听器
     * @return 构建完成的流式对话模型
     */
    @Override
    public StreamingChatModel createStreamingChatModel(AiModelDefinition definition, ChatModelListener listener) {
        // 1. 流式对话单独给更长超时，避免首 token 慢一点就被截断
        var builder = OpenAiStreamingChatModel.builder()
                .apiKey(definition.getApiKey())
                .baseUrl(definition.getBaseUrl())
                .modelName(definition.getModelName())
                // 注意：1.0.1 的 StreamingChatModel 构建器没有 maxRetries
                .timeout(definition.getStreamingTimeout())
                .logRequests(definition.getLogRequests())
                .logResponses(definition.getLogResponses());

        // 2. 只有模型支持时才传 temperature
        if (!Boolean.FALSE.equals(definition.getTemperatureSupported())) {
            builder.temperature(definition.getTemperature());
        }

        // 3. 与普通对话共用同一个监听器实现
        if (listener != null) {
            builder.listeners(List.of(listener));
        }
        return builder.build();
    }
}
