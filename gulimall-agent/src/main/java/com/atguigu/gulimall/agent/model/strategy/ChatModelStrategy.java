package com.atguigu.gulimall.agent.model.strategy;

import com.atguigu.gulimall.agent.model.AiModelDefinition;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;

/**
 * 对话模型创建策略。
 *
 * <p>目前 closeai 上所有模型都是 OpenAI 兼容协议，所以只有一个实现；
 * 以后要接原生 Claude / Gemini 协议，只需再加一个 @Component 实现，工厂会自动收集。
 */
public interface ChatModelStrategy {

    /**
     * 对应 AiModelDefinition.provider，用于标识该策略支持的模型协议。
     *
     * @return 该策略对应的 provider 名称
     */
    String provider();

    /**
     * 创建普通对话模型。
     *
     * @param definition 目标模型的定义
     * @param listener   记录请求、响应和异常日志的模型监听器
     * @return 按该策略构建的普通对话模型
     */
    ChatModel createChatModel(AiModelDefinition definition, ChatModelListener listener);

    /**
     * 创建流式对话模型。
     *
     * @param definition 目标模型的定义
     * @param listener   记录请求、响应和异常日志的模型监听器
     * @return 按该策略构建的流式对话模型
     */
    StreamingChatModel createStreamingChatModel(AiModelDefinition definition, ChatModelListener listener);
}
