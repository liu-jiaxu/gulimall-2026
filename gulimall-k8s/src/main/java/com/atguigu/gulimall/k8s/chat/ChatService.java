package com.atguigu.gulimall.k8s.chat;

import com.atguigu.gulimall.k8s.ai.ModelCatalog;
import com.atguigu.gulimall.k8s.ai.ModelFactory;
import com.atguigu.gulimall.k8s.common.ApiException;
import com.atguigu.gulimall.k8s.config.AiConfig;
import com.atguigu.gulimall.k8s.config.AiConfigRepository;
import com.atguigu.gulimall.k8s.config.AiProperties;
import com.atguigu.gulimall.k8s.config.ConversationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天编排：校验 -> 取模型 -> 取/建会话 -> 存用户消息 -> 调模型 -> 存 AI 回复。
 *
 * <p>普通对话和 SSE 流式对话共用同一套 Redis 历史、模型记忆和系统提示词；
 * 区别只是流式把 token 逐段发给浏览器。
 *
 * <p>没有工具调用、没有 RAG、没有图片生成，只做纯文本问答。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /**
     * 同一会话的处理锁前缀，避免两条消息同时写乱记忆
     */
    private static final String LOCK_PREFIX = "gulimall:k8s:ai:lock:";

    /**
     * 数据库没配系统提示词时的兜底
     */
    private static final String FALLBACK_PROMPT = "你是一个乐于助人的AI助手。请直接、准确地回答用户问题。";

    private final ConversationService conversationService;
    private final RedisChatMemoryStore memoryStore;
    private final ModelCatalog modelCatalog;
    private final ModelFactory modelFactory;
    private final AiConfigRepository aiConfigRepository;
    private final ConversationProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 执行一次普通对话。
     *
     * @param userId  当前用户 id
     * @param request 聊天请求
     * @return AI 回复
     */
    public ChatResult chat(Long userId, ChatRequest request) {
        // 1. 校验消息并准备会话、模型、系统提示词
        Prepared prepared = prepare(userId, request);

        // 2. 同一会话串行处理，避免记忆被并发覆盖
        String lockKey = acquireLock(prepared.conversationId());
        try {
            // 3. 先存用户消息，再调模型
            appendUser(prepared);
            Assistant assistant = buildAssistant(prepared.model(), prepared.systemPrompt());
            String answer = assistant.chat(prepared.conversationId(), prepared.userText());

            // 4. 保存 AI 回复并返回
            return saveAssistant(prepared, answer, newMessageId(), null);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("AI 普通对话失败 conversationId={}", prepared.conversationId(), ex);
            throw new ApiException(500, "AI 调用失败：" + rootMessage(ex), ex);
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * 执行一次流式对话，通过 SSE 推送 meta、delta、done 或 error。
     *
     * @param userId  当前用户 id
     * @param request 聊天请求
     * @return SSE 连接
     */
    public SseEmitter stream(Long userId, ChatRequest request) {
        // 1. 先做校验；失败也用 SSE error 事件返回，前端才能统一处理
        Prepared prepared;
        try {
            prepared = prepare(userId, request);
        } catch (ApiException ex) {
            return errorEmitter(ex.getStatus(), ex.getMessage());
        }

        // 2. 建 5 分钟长连接
        SseEmitter emitter = new SseEmitter(300_000L);
        String lockKey;
        try {
            lockKey = acquireLock(prepared.conversationId());
        } catch (ApiException ex) {
            sendEvent(emitter, "error", Map.of("code", ex.getStatus(), "message", ex.getMessage()));
            emitter.complete();
            return emitter;
        }

        // 3. 连接结束/超时/异常时释放锁，只释放一次
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                redisTemplate.delete(lockKey);
            }
        };
        emitter.onCompletion(release);
        emitter.onTimeout(release);
        emitter.onError(error -> release.run());

        // 4. 先存用户消息，失败时直接结束
        try {
            appendUser(prepared);
        } catch (Exception ex) {
            release.run();
            sendEvent(emitter, "error", Map.of("code", 500, "message", "保存消息失败"));
            emitter.complete();
            return emitter;
        }

        // 5. 通知前端本次会话、消息和模型信息
        String assistantMessageId = newMessageId();
        sendEvent(emitter, "meta", Map.of(
                "conversationId", prepared.conversationId(),
                "messageId", assistantMessageId,
                "modelId", prepared.model().getId()
        ));

        try {
            // 6. 构建流式模型并注册回调
            StreamingAssistant assistant = buildStreamingAssistant(prepared.model(), prepared.systemPrompt());
            TokenStream tokenStream = assistant.chatStream(prepared.conversationId(), prepared.userText());
            StringBuilder fullText = new StringBuilder();
            AtomicBoolean completed = new AtomicBoolean(false);

            tokenStream.onPartialResponse(token -> {
                // 1. 每个 token 立即推给浏览器
                fullText.append(token);
                sendEvent(emitter, "delta", Map.of("content", token));
            }).onCompleteResponse(response -> {
                // 2. 完成后保存完整回复并通知前端结束
                if (!completed.compareAndSet(false, true)) {
                    return;
                }
                try {
                    String text = extractText(response, fullText.toString());
                    TokenUsage usage = response == null ? null : response.tokenUsage();
                    ChatResult saved = saveAssistant(prepared, text, assistantMessageId, usage);
                    Map<String, Object> done = new LinkedHashMap<>();
                    done.put("conversationId", saved.conversationId());
                    done.put("messageId", saved.messageId());
                    done.put("modelId", saved.modelId());
                    done.put("inputTokens", saved.inputTokens());
                    done.put("outputTokens", saved.outputTokens());
                    sendEvent(emitter, "done", done);
                } finally {
                    release.run();
                    emitter.complete();
                }
            }).onError(error -> {
                // 3. 模型异常：推 error 事件并释放锁
                log.error("AI 流式对话失败 conversationId={}", prepared.conversationId(), error);
                if (completed.compareAndSet(false, true)) {
                    sendEvent(emitter, "error", Map.of(
                            "code", 500,
                            "message", "AI 调用失败：" + rootMessage(error)
                    ));
                }
                release.run();
                emitter.complete();
            }).start();
        } catch (Exception ex) {
            log.error("启动流式对话失败 conversationId={}", prepared.conversationId(), ex);
            sendEvent(emitter, "error", Map.of("code", 500, "message", rootMessage(ex)));
            release.run();
            emitter.complete();
        }
        return emitter;
    }

    /**
     * 校验请求并准备本次对话所需的数据。
     *
     * @param userId  当前用户 id
     * @param request 聊天请求
     * @return 预处理结果
     */
    private Prepared prepare(Long userId, ChatRequest request) {
        // 1. 消息不能为空，也不能超过配置的最大长度
        String message = request.message() == null ? "" : request.message().trim();
        if (!StringUtils.hasText(message)) {
            throw new ApiException(400, "消息不能为空");
        }
        int maxChars = properties.getMaxMessageChars();
        if (message.length() > maxChars) {
            throw new ApiException(400, "消息不能超过 " + maxChars + " 字");
        }

        // 2. 解析模型；不传时用数据库/YAML 的默认模型
        AiProperties.ModelDefinition model = modelCatalog.find(request.modelId());

        // 3. 取已有会话或新建
        String conversationId = conversationService.getOrCreate(
                userId, request.conversationId(), model.getId());

        // 4. 系统提示词从数据库读，改库不用重启
        AiConfig config = aiConfigRepository.load();
        String systemPrompt = StringUtils.hasText(config.systemPrompt())
                ? config.systemPrompt()
                : FALLBACK_PROMPT;

        return new Prepared(userId, conversationId, model, message, systemPrompt);
    }

    /**
     * 把用户消息写入 Redis 历史。
     *
     * @param prepared 预处理结果
     */
    private void appendUser(Prepared prepared) {
        // 1. 用户消息只存正文；模型和 token 留给 AI 消息
        ChatMessage userMessage = ChatMessage.user(
                newMessageId(),
                prepared.userText(),
                System.currentTimeMillis()
        );
        conversationService.append(prepared.userId(), prepared.conversationId(), userMessage);
    }

    /**
     * 构建普通对话 AI Service：绑定 Redis 记忆和系统提示词，不挂任何工具/RAG。
     *
     * @param model        模型定义
     * @param systemPrompt 系统提示词
     * @return 普通对话接口
     */
    private Assistant buildAssistant(AiProperties.ModelDefinition model, String systemPrompt) {
        // 1. 每个会话一个记忆窗口，窗口只保留最近 N 条
        ChatMemoryProvider memoryProvider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(properties.getMemoryMaxMessages())
                .chatMemoryStore(memoryStore)
                .build();

        // 2. 只挂模型、记忆和系统提示词
        return AiServices.builder(Assistant.class)
                .chatModel(modelFactory.chat(model))
                .chatMemoryProvider(memoryProvider)
                .systemMessageProvider(memoryId -> systemPrompt)
                .build();
    }

    /**
     * 构建流式对话 AI Service。
     *
     * @param model        模型定义
     * @param systemPrompt 系统提示词
     * @return 流式对话接口
     */
    private StreamingAssistant buildStreamingAssistant(AiProperties.ModelDefinition model, String systemPrompt) {
        // 1. 流式版记忆规则与普通版完全一致
        ChatMemoryProvider memoryProvider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(properties.getMemoryMaxMessages())
                .chatMemoryStore(memoryStore)
                .build();

        // 2. 只换流式模型，其余配置不变
        return AiServices.builder(StreamingAssistant.class)
                .streamingChatModel(modelFactory.streaming(model))
                .chatMemoryProvider(memoryProvider)
                .systemMessageProvider(memoryId -> systemPrompt)
                .build();
    }

    /**
     * 保存 AI 回复并组装返回结果。
     *
     * @param prepared  预处理结果
     * @param answer    模型原始回复
     * @param messageId AI 消息 id
     * @param usage     token 用量，可为 null
     * @return 返回给前端的对话结果
     */
    private ChatResult saveAssistant(Prepared prepared, String answer, String messageId, TokenUsage usage) {
        // 1. 模型偶尔返回空内容，给一句可读提示而不是空白气泡
        String content = StringUtils.hasText(answer) ? answer : "（AI 没有返回内容，请稍后重试）";

        // 2. token 用量拿不到就存 null
        Integer inputTokens = usage == null ? null : usage.inputTokenCount();
        Integer outputTokens = usage == null ? null : usage.outputTokenCount();

        // 3. 写入 Redis 完整历史，并续期 3 天
        ChatMessage assistantMessage = ChatMessage.assistant(
                messageId,
                content,
                prepared.model().getId(),
                System.currentTimeMillis(),
                inputTokens,
                outputTokens
        );
        conversationService.append(prepared.userId(), prepared.conversationId(), assistantMessage);

        // 4. 组装普通对话的响应
        return new ChatResult(
                prepared.conversationId(),
                messageId,
                content,
                prepared.model().getId(),
                assistantMessage.createdAt(),
                inputTokens,
                outputTokens
        );
    }

    /**
     * 从模型完整响应里取文本，取不到时回退到累积的 token。
     *
     * @param response 模型响应
     * @param fallback 回退文本
     * @return 最终文本
     */
    private String extractText(ChatResponse response, String fallback) {
        // 1. 响应为空直接用累积 token
        if (response == null) {
            return fallback;
        }

        // 2. 优先用官方完整文本
        AiMessage aiMessage = response.aiMessage();
        if (aiMessage == null || !StringUtils.hasText(aiMessage.text())) {
            return fallback;
        }
        return aiMessage.text();
    }

    /**
     * 为会话获取处理锁，同一会话同一时间只允许一个请求。
     *
     * @param conversationId 会话 id
     * @return 锁 key
     */
    private String acquireLock(String conversationId) {
        // 1. 5 分钟自动过期，防止进程崩溃后锁一直不释放
        String lockKey = LOCK_PREFIX + conversationId;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofMinutes(5));
        if (!Boolean.TRUE.equals(acquired)) {
            throw new ApiException(429, "上一条消息还在处理中，请稍候");
        }
        return lockKey;
    }

    /**
     * 生成不带横线的 UUID，用作消息 id。
     *
     * @return 消息 id
     */
    private String newMessageId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 取异常最底层的错误信息。
     *
     * @param error 原始异常
     * @return 最底层错误信息
     */
    private String rootMessage(Throwable error) {
        // 1. 逐层取 cause，避免前端只看到一层包装
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return StringUtils.hasText(message) ? message : current.getClass().getSimpleName();
    }

    /**
     * 向 SSE 连接推送一个事件。
     *
     * @param emitter SSE 连接
     * @param event   事件名
     * @param data    事件数据
     */
    private void sendEvent(SseEmitter emitter, String event, Map<String, Object> data) {
        try {
            // 1. 事件名交给前端区分 meta/delta/done/error
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(objectMapper.writeValueAsString(data), MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            // 2. 浏览器断开是正常现象，不再抛异常
            log.debug("SSE 客户端已断开：{}", ex.getMessage());
        } catch (Exception ex) {
            log.warn("SSE 发送失败 event={}：{}", event, ex.getMessage());
        }
    }

    /**
     * 构造一个只推送一条 error 事件的 SSE 响应。
     *
     * @param code    错误码
     * @param message 错误提示
     * @return 已完成的 SSE 连接
     */
    private SseEmitter errorEmitter(int code, String message) {
        // 1. 未登录/参数错误也要用 SSE 事件返回，前端才能统一解析
        SseEmitter emitter = new SseEmitter(30_000L);
        sendEvent(emitter, "error", Map.of("code", code, "message", message));
        emitter.complete();
        return emitter;
    }

    /**
     * 一次请求里所有不变的中间数据。
     *
     * @param userId         当前用户 id
     * @param conversationId 会话 id
     * @param model          本次使用的模型
     * @param userText       用户消息
     * @param systemPrompt   系统提示词
     */
    private record Prepared(
            Long userId,
            String conversationId,
            AiProperties.ModelDefinition model,
            String userText,
            String systemPrompt
    ) {
    }
}
