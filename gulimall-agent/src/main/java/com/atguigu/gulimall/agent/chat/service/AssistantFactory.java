package com.atguigu.gulimall.agent.chat.service;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.chat.memory.RedisChatMemoryStore;
import com.atguigu.gulimall.agent.config.AiProperties;
import com.atguigu.gulimall.agent.model.AiModelDefinition;
import com.atguigu.gulimall.agent.model.ModelFactory;
import com.atguigu.gulimall.agent.rag.RoleAwareContentRetriever;
import com.atguigu.gulimall.agent.tool.NullSafeToolExecutor;
import com.atguigu.gulimall.agent.tool.ToolBundleFactory;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每次请求按“当前用户 + 当前模型 + 自定义提示词”构建 AI Service。
 *
 * <p>为什么不提前做成单例 Bean：
 * 1. system prompt 随 USER/ADMIN 和用户自定义提示词变化；
 * 2. 每个用户只能看到自己的工具和会话记忆；
 * 3. 构建本身很轻，真正重的模型对象由 ModelFactory 缓存。
 */
@Service
@RequiredArgsConstructor
public class AssistantFactory {

    private final ModelFactory modelFactory;
    private final RedisChatMemoryStore redisChatMemoryStore;
    private final PromptService promptService;
    private final ToolBundleFactory toolBundleFactory;
    private final RoleAwareContentRetriever roleAwareContentRetriever;
    private final AiProperties properties;

    /**
     * 为当前用户构建非流式对话的 AI Service，绑定记忆窗口、系统提示词、工具和知识库检索。
     *
     * @param principal      当前登录身份
     * @param model          本次对话使用的模型定义
     * @param customPrompt   用户自定义提示词，可为空
     * @param conversationId 会话 id，用于隔离记忆和绑定会话级工具
     * @return 配置完成的普通对话 AI Service
     */
    public ShopAssistant assistant(Principal principal, AiModelDefinition model,
                                   String customPrompt, String conversationId) {
        // 1. 按会话 id 隔离记忆；窗口只保留最近 40 条
        ChatMemoryProvider memoryProvider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(properties.getConversation().getMemoryMaxMessages())
                .chatMemoryStore(redisChatMemoryStore)
                .build();

        // 2. system prompt 在每次请求时生成，包含身份、安全规则和用户偏好
        AiServices<ShopAssistant> builder = AiServices.builder(ShopAssistant.class)
                .chatModel(modelFactory.chat(model))
                .chatMemoryProvider(memoryProvider)
                .systemMessageProvider(memoryId -> systemPrompt(principal, customPrompt, model));

        // 3. 模型声明支持工具时才挂工具集，避免不支持 function calling 的模型报错
        if (Boolean.TRUE.equals(model.getTools())) {
            builder.tools(toolExecutors(toolBundleFactory.create(principal, conversationId)));
        }

        // 4. RAG 可用时挂知识库检索；Milvus 不可用则自动降级为纯对话
        ContentRetriever retriever = roleAwareContentRetriever.forPrincipal(principal);
        if (retriever != null) {
            builder.contentRetriever(retriever);
        }
        return builder.build();
    }

    /**
     * 为当前用户构建流式对话的 AI Service，配置与普通对话一致，仅模型改用流式模型。
     *
     * @param principal      当前登录身份
     * @param model          本次对话使用的模型定义
     * @param customPrompt   用户自定义提示词，可为空
     * @param conversationId 会话 id，用于隔离记忆和绑定会话级工具
     * @return 配置完成的流式对话 AI Service
     */
    public ShopStreamingAssistant streamingAssistant(Principal principal, AiModelDefinition model,
                                                     String customPrompt, String conversationId) {
        // 1. 流式版本使用独立的 StreamingChatModel，但记忆、提示词、会话 id 规则完全一致
        ChatMemoryProvider memoryProvider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(properties.getConversation().getMemoryMaxMessages())
                .chatMemoryStore(redisChatMemoryStore)
                .build();

        AiServices<ShopStreamingAssistant> builder = AiServices.builder(ShopStreamingAssistant.class)
                .streamingChatModel(modelFactory.streaming(model))
                .chatMemoryProvider(memoryProvider)
                .systemMessageProvider(memoryId -> systemPrompt(principal, customPrompt, model));

        // 2. 流式同样挂工具，模型可以在流式过程中调用查库工具
        if (Boolean.TRUE.equals(model.getTools())) {
            builder.tools(toolExecutors(toolBundleFactory.create(principal, conversationId)));
        }

        // 3. 流式同样挂 RAG 检索器
        ContentRetriever retriever = roleAwareContentRetriever.forPrincipal(principal);
        if (retriever != null) {
            builder.contentRetriever(retriever);
        }
        return builder.build();
    }

    /**
     * 把工具对象转成“规格 -> 执行器”映射。
     *
     * <p>不能直接用 {@code builder.tools(objects)}：LangChain4j 1.0.1 的默认执行器
     * 遇到模型显式传 null 的数字参数会 NPE。这里统一换成能过滤 null 的执行器。
     *
     * @param tools 已按当前身份构建的工具对象列表
     * @return 工具规格到安全执行器的映射
     */
    private Map<ToolSpecification, ToolExecutor> toolExecutors(List<Object> tools) {
        // 1. 按 @Tool 方法生成规格
        Map<ToolSpecification, ToolExecutor> executors = new LinkedHashMap<>();
        for (Object tool : tools) {
            for (Method method : tool.getClass().getMethods()) {
                if (!method.isAnnotationPresent(Tool.class)) {
                    continue;
                }
                // 2. 每个方法包一层 NullSafeToolExecutor
                executors.put(ToolSpecifications.toolSpecificationFrom(method),
                        new NullSafeToolExecutor(tool, method));
            }
        }
        return executors;
    }

    /**
     * 组装系统提示词，在基础提示词上补充模型能力说明。
     *
     * @param principal    当前登录身份
     * @param customPrompt 用户自定义提示词，可为空
     * @param model        本次对话使用的模型定义
     * @return 最终使用的系统提示词
     */
    private String systemPrompt(Principal principal, String customPrompt, AiModelDefinition model) {
        // 1. 基础提示词 + 用户偏好
        String prompt = promptService.build(principal, customPrompt);

        // 2. 模型不支持工具时明确告诉模型，避免它编造库存/订单等实时数据
        if (!Boolean.TRUE.equals(model.getTools())) {
            prompt += "\n\n【能力提示】当前模型不支持工具查询，无法获取实时库存/订单/购物车数据。"
                    + "遇到这类问题，请如实告诉用户“当前模型不能查询实时数据，请切换到支持查库的模型（如 DeepSeek）”。";
        } else {
            // 3. 支持工具的模型统一约束参数格式，减少尾随逗号、中文标点等非法 JSON
            prompt += "\n\n【工具参数格式】调用工具时，arguments 必须是严格 JSON："
                    + "字段名和字符串值必须用英文双引号，不能有尾随逗号，不能使用中文逗号、中文冒号或 Markdown 代码块。";
        }
        return prompt;
    }
}
