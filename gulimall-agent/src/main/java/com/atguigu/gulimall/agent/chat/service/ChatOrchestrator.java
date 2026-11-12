package com.atguigu.gulimall.agent.chat.service;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.chat.dto.ChatMessageDto;
import com.atguigu.gulimall.agent.chat.dto.ChatRequest;
import com.atguigu.gulimall.agent.chat.dto.ChatResponseDto;
import com.atguigu.gulimall.agent.chat.memory.ConversationService;
import com.atguigu.gulimall.agent.config.AiProperties;
import com.atguigu.gulimall.agent.exception.AgentErrorCode;
import com.atguigu.gulimall.agent.exception.AgentException;
import com.atguigu.gulimall.agent.image.ImageMetadataService;
import com.atguigu.gulimall.agent.image.ImageStorageService;
import com.atguigu.gulimall.agent.image.VisionAnalysisService;
import com.atguigu.gulimall.agent.model.AiModelDefinition;
import com.atguigu.gulimall.agent.model.ModelCatalog;
import com.atguigu.gulimall.agent.settings.entity.AiUserSetting;
import com.atguigu.gulimall.agent.settings.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天编排：校验 -> 取模型 -> 取/建会话 -> 存用户消息 -> 调模型 -> 存 AI 回复。
 *
 * <p>普通对话和流式对话共用同一套会话、记忆、提示词；
 * 区别只是流式把 token 逐段通过 SSE 发给浏览器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatOrchestrator {

    private static final String LOCK_PREFIX = "gulimall:ai:lock:conv:";
    private static final String STREAM_PREFIX = "gulimall:ai:stream:";
    private static final String STOP_PREFIX = "gulimall:ai:stop:";

    private final ConversationService conversationService;
    private final SettingsService settingsService;
    private final ModelCatalog modelCatalog;
    private final AssistantFactory assistantFactory;
    private final AiProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ImageMetadataService imageMetadataService;
    private final ImageStorageService imageStorageService;
    private final VisionAnalysisService visionAnalysisService;

    /**
     * 执行一次普通（非流式）对话，串行处理同一会话并保存用户消息与 AI 回复。
     *
     * @param principal 当前登录身份
     * @param request   聊天请求，包含消息、图片、模型和会话 id
     * @return 包含 AI 回复及 token 用量的响应
     */
    public ChatResponseDto chat(Principal principal, ChatRequest request) {
        // 1. 校验消息并准备会话、模型、提示词
        Prepared prepared = prepare(principal, request);

        // 2. 同一个会话串行处理，避免两条消息同时写乱记忆
        String lockKey = acquireLock(prepared.conversationId());
        try {
            appendUserMessage(prepared);

            // 3. 调非流式模型
            ShopAssistant assistant = assistantFactory.assistant(
                    principal, prepared.model(), prepared.customPrompt(), prepared.conversationId());
            String answer = assistant.chat(prepared.conversationId(), prepared.userText());

            // 4. 保存 AI 回复并返回
            return saveAssistant(prepared, answer, null);
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("AI 普通对话失败 conversationId={}", prepared.conversationId(), ex);
            throw new AgentException(AgentErrorCode.MODEL_CALL_FAILED,
                    "AI 调用失败：" + rootMessage(ex), ex);
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * 强制结束当前会话正在进行的流式回答。
     *
     * <p>只给当前流的 streamId 打停止标记，不影响之后新发起的对话。
     *
     * @param principal      当前登录身份
     * @param conversationId 需要停止流式回答的会话 id
     */
    public void stop(Principal principal, String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            throw new AgentException(AgentErrorCode.MESSAGE_INVALID, "conversationId 不能为空");
        }

        // 1. 校验会话归属，不能停别人的会话
        conversationService.getOrCreate(principal, conversationId, null);

        // 2. 只停当前正在跑的流；没有流时什么也不做
        String streamId = redisTemplate.opsForValue().get(STREAM_PREFIX + conversationId);
        if (StringUtils.hasText(streamId)) {
            redisTemplate.opsForValue().set(
                    STOP_PREFIX + conversationId, streamId, Duration.ofSeconds(120));
        }
    }

    /**
     * 执行一次流式对话，通过 SSE 推送 meta、delta、done 或 error 事件。
     *
     * @param principal 当前登录身份
     * @param request   聊天请求，包含消息、图片、模型和会话 id
     * @return SSE 连接对象，用于向前端推送流式事件
     */
    public SseEmitter stream(Principal principal, ChatRequest request) {
        // 1. 先做校验；失败也要用 SSE 的 error 事件返回，前端才能统一处理
        Prepared prepared;
        try {
            prepared = prepare(principal, request);
        } catch (AgentException ex) {
            SseEmitter emitter = new SseEmitter(300_000L);
            sendEvent(emitter, "error", Map.of("code", ex.getCode(), "message", ex.getMessage()));
            emitter.complete();
            return emitter;
        }

        // 2. 创建长连接（5 分钟），前端收到 meta 后开始展示
        SseEmitter emitter = new SseEmitter(300_000L);
        String lockKey;
        try {
            lockKey = acquireLock(prepared.conversationId());
        } catch (AgentException ex) {
            // 会话忙等错误也要用 SSE 事件返回，避免前端拿到 JSON 解析失败
            sendEvent(emitter, "error", Map.of("code", ex.getCode(), "message", ex.getMessage()));
            emitter.complete();
            return emitter;
        }
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                redisTemplate.delete(lockKey);
            }
        };
        emitter.onCompletion(release);
        emitter.onTimeout(release);
        emitter.onError(error -> release.run());

        // 记录当前流 id，停止接口只认这一个 id，不影响后续新对话
        String streamId = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(
                STREAM_PREFIX + prepared.conversationId(), streamId, Duration.ofMinutes(5));

        try {
            appendUserMessage(prepared);
        } catch (Exception ex) {
            clearStream(prepared.conversationId(), streamId);
            release.run();
            sendEvent(emitter, "error", Map.of("code", 15010, "message", "保存消息失败"));
            emitter.complete();
            return emitter;
        }

        sendEvent(emitter, "meta", Map.of(
                "conversationId", prepared.conversationId(),
                "messageId", prepared.assistantMessageId(),
                "modelId", prepared.model().getId()
        ));

        // 3. 构建流式服务并注册回调
        try {
            ShopStreamingAssistant assistant = assistantFactory.streamingAssistant(
                    principal, prepared.model(), prepared.customPrompt(), prepared.conversationId());
            TokenStream tokenStream = assistant.chatStream(prepared.conversationId(), prepared.userText());
            StringBuilder fullText = new StringBuilder();
            AtomicBoolean completed = new AtomicBoolean(false);
            AtomicBoolean stopped = new AtomicBoolean(false);

            // 1. 兜底超时：LangChain4J 1.0.1 的 SSE 监听器会吞掉工具执行异常，
            //    onComplete/onError 可能永远不触发；超过流式超时 + 30 秒仍没完成时主动收尾
            long watchdogSeconds = Math.max(30,
                    properties.getDefaults().getStreamingTimeout().getSeconds() + 30);
            CompletableFuture.delayedExecutor(watchdogSeconds, TimeUnit.SECONDS).execute(() -> {
                if (completed.compareAndSet(false, true)) {
                    log.warn("AI 流式对话超时 conversationId={} streamId={}",
                            prepared.conversationId(), streamId);
                    // 2. 通知前端、清理当前流并释放会话锁，避免一直提示“上一条消息还在处理中”
                    sendEvent(emitter, "error", Map.of(
                            "code", 15010,
                            "message", "AI 回复超时，请重试"
                    ));
                    clearStream(prepared.conversationId(), streamId);
                    release.run();
                    emitter.complete();
                }
            });

            tokenStream.onPartialResponse(token -> {
                // 1. 用户点了停止：后续 token 不再追加、不再推给浏览器
                if (isStopped(prepared.conversationId(), streamId)) {
                    stopped.set(true);
                    return;
                }
                // 2. 正常 token 立即推给浏览器
                fullText.append(token);
                sendEvent(emitter, "delta", Map.of("content", token));
            }).onCompleteResponse(response -> {
                // 2. 完成后把完整回复落 Redis，并通知前端结束
                if (!completed.compareAndSet(false, true)) {
                    return;
                }
                try {
                    if (stopped.get() || isStopped(prepared.conversationId(), streamId)) {
                        // 被强制结束：只保存已经推给浏览器的部分，刷新后也能看到
                        Map<String, Object> done = new LinkedHashMap<>();
                        done.put("conversationId", prepared.conversationId());
                        done.put("messageId", prepared.assistantMessageId());
                        done.put("modelId", prepared.model().getId());
                        String partial = fullText.toString();
                        if (StringUtils.hasText(partial)) {
                            ChatResponseDto saved = saveAssistant(prepared, partial, response.tokenUsage());
                            done.put("inputTokens", saved.getInputTokens());
                            done.put("outputTokens", saved.getOutputTokens());
                        }
                        done.put("stopped", true);
                        sendEvent(emitter, "done", done);
                        return;
                    }
                    String text = extractText(response, fullText.toString());
                    ChatResponseDto saved = saveAssistant(prepared, text, response.tokenUsage());
                    Map<String, Object> done = new LinkedHashMap<>();
                    done.put("conversationId", saved.getConversationId());
                    done.put("messageId", saved.getMessageId());
                    done.put("modelId", saved.getModelId());
                    done.put("inputTokens", saved.getInputTokens());
                    done.put("outputTokens", saved.getOutputTokens());
                    sendEvent(emitter, "done", done);
                } finally {
                    clearStream(prepared.conversationId(), streamId);
                    release.run();
                    emitter.complete();
                }
            }).onError(error -> {
                // 3. 模型异常：推 error 事件并释放锁
                log.error("AI 流式对话失败 conversationId={}", prepared.conversationId(), error);
                if (completed.compareAndSet(false, true)
                        && !stopped.get()
                        && !isStopped(prepared.conversationId(), streamId)) {
                    sendEvent(emitter, "error", Map.of(
                            "code", 15010,
                            "message", "AI 调用失败：" + rootMessage(error)
                    ));
                }
                clearStream(prepared.conversationId(), streamId);
                release.run();
                emitter.complete();
            }).start();
        } catch (Exception ex) {
            log.error("启动流式对话失败 conversationId={}", prepared.conversationId(), ex);
            sendEvent(emitter, "error", Map.of("code", 15010, "message", rootMessage(ex)));
            clearStream(prepared.conversationId(), streamId);
            release.run();
            emitter.complete();
        }
        return emitter;
    }

    /**
     * 校验请求并准备会话、模型、用户消息等本次对话所需的中间数据。
     *
     * @param principal 当前登录身份
     * @param request   聊天请求
     * @return 本次请求的预处理结果
     */
    private Prepared prepare(Principal principal, ChatRequest request) {
        // 1. 消息不能为空（只发图片时允许文字为空），且限制长度
        String message = request.getMessage() == null ? "" : request.getMessage().trim();
        List<String> imageIds = request.getImageIds() == null ? List.of() : request.getImageIds();
        int maxChars = properties.getConversation().getMaxMessageChars();
        if (!StringUtils.hasText(message) && imageIds.isEmpty()) {
            throw new AgentException(AgentErrorCode.MESSAGE_INVALID, "消息不能为空");
        }
        if (message.length() > maxChars) {
            throw new AgentException(AgentErrorCode.MESSAGE_INVALID, "消息不能超过 " + maxChars + " 字");
        }

        // 2. 有图片时先用视觉模型识别，把结果拼进文本再交给用户选择的模型
        List<String> imageUrls = new ArrayList<>();
        String userText = message;
        if (!imageIds.isEmpty()) {
            int maxImages = properties.getImage().getMaxImagesPerMessage();
            if (imageIds.size() > maxImages) {
                throw new AgentException(AgentErrorCode.IMAGE_INVALID,
                        "一条消息最多上传 " + maxImages + " 张图片");
            }
            StringBuilder analyses = new StringBuilder();
            for (String imageId : imageIds) {
                // 1. 元数据校验归属：只能识别当前用户自己上传的图片
                ImageMetadataService.ImageMeta meta = imageMetadataService.get(principal, imageId);
                // 2. 从 MinIO 取原始字节，交给视觉模型识别
                byte[] bytes = imageStorageService.download(meta.objectKey());
                String analysis = visionAnalysisService.analyze(principal, bytes, meta.mime(), message);
                // 3. 识别结果按图片拼起来，后面一起交给对话模型
                analyses.append(analysis).append("\n");
                // 4. 同时保留图片 URL，前端聊天记录里要展示缩略图
                imageUrls.add(meta.url());
            }
            String question = StringUtils.hasText(message) ? message : "请描述这张图片的内容";
            userText = "用户上传了图片，视觉识别结果如下：\n" + analyses
                    + "\n用户问题：" + question + "\n请结合识别结果回答。";
        }

        // 3. 读取用户设置：默认模型、自定义提示词
        AiUserSetting setting = settingsService.getOrCreate(principal);
        String modelId = StringUtils.hasText(request.getModelId()) ? request.getModelId() : setting.getDefaultModel();
        AiModelDefinition model = modelCatalog.find(principal, modelId);

        // 4. 取已有会话或新建
        String conversationId = conversationService.getOrCreate(
                principal, request.getConversationId(), model.getId());

        // 5. 准备两条消息的 id（用户消息现在存，AI 消息完成后存）
        long now = System.currentTimeMillis();
        ChatMessageDto userMessage = new ChatMessageDto();
        // 1. id：前端渲染/回显时定位这条消息
        userMessage.setId(UUID.randomUUID().toString().replace("-", ""));
        // 2. role=user，前端据此决定气泡在左边还是右边
        userMessage.setRole("user");
        // 3. 纯图片消息没有文字，用 [图片] 占位，避免气泡为空
        userMessage.setContent(StringUtils.hasText(message) ? message : "[图片]");
        // 4. imageUrls 用于展示缩略图
        userMessage.setImageUrls(imageUrls);
        // 5. imageIds 用于按上方向键回显后再次发送图片
        userMessage.setImageIds(new ArrayList<>(imageIds));
        userMessage.setCreatedAt(now);
        userMessage.setStatus("ok");

        return new Prepared(principal, conversationId, model, userText,
                setting.getCustomPrompt(), userMessage,
                UUID.randomUUID().toString().replace("-", ""));
    }

    /**
     * 把用户的这条消息写入会话历史。
     *
     * @param prepared 本次请求的预处理结果
     */
    private void appendUserMessage(Prepared prepared) {
        conversationService.append(prepared.principal(), prepared.conversationId(), prepared.userMessage());
    }

    /**
     * 保存 AI 回复到会话历史，并组装返回给前端的响应。
     *
     * @param prepared 本次请求的预处理结果
     * @param answer   模型返回的原始文本
     * @param usage    token 用量，可为空
     * @return 包含 AI 回复、模型标识和 token 用量的响应
     */
    private ChatResponseDto saveAssistant(Prepared prepared, String answer, TokenUsage usage) {
        // 1. 模型偶尔返回空内容，给用户一句可读提示而不是空白
        String content = StringUtils.hasText(answer) ? answer : "（AI 没有返回内容，请稍后重试）";

        // 2. 组装 AI 消息并写入 Redis
        ChatMessageDto assistantMessage = new ChatMessageDto();
        // 1. 流式/普通对话共用 prepare 时预生成的 AI 消息 id
        assistantMessage.setId(prepared.assistantMessageId());
        // 2. role=assistant，前端据此渲染 AI 气泡
        assistantMessage.setRole("assistant");
        // 3. 正文：模型返回空时给一句可读提示，不留空白气泡
        assistantMessage.setContent(content);
        // 4. 记录实际模型，页面切换模型时能看出这条是谁答的
        assistantMessage.setModelId(prepared.model().getId());
        assistantMessage.setCreatedAt(System.currentTimeMillis());
        assistantMessage.setStatus("ok");
        if (usage != null) {
            assistantMessage.setInputTokens(usage.inputTokenCount());
            assistantMessage.setOutputTokens(usage.outputTokenCount());
        }
        conversationService.append(prepared.principal(), prepared.conversationId(), assistantMessage);

        // 3. 转成接口返回
        ChatResponseDto response = new ChatResponseDto();
        response.setConversationId(prepared.conversationId());
        response.setMessageId(assistantMessage.getId());
        response.setContent(content);
        response.setModelId(prepared.model().getId());
        response.setCreatedAt(assistantMessage.getCreatedAt());
        response.setInputTokens(assistantMessage.getInputTokens());
        response.setOutputTokens(assistantMessage.getOutputTokens());
        return response;
    }

    /**
     * 判断给定的流是否已被用户强制停止。
     *
     * @param conversationId 会话 id
     * @param streamId       当前流 id
     * @return 停止标记与当前流 id 一致时为 true
     */
    private boolean isStopped(String conversationId, String streamId) {
        // 1. 只有停止标记等于当前 streamId 才算停；新对话的 streamId 不同，不受影响
        String stopId = redisTemplate.opsForValue().get(STOP_PREFIX + conversationId);
        return streamId.equals(stopId);
    }

    /**
     * 清理属于当前流的 stream 与 stop 标记，不影响后续新流。
     *
     * @param conversationId 会话 id
     * @param streamId       当前流 id
     */
    private void clearStream(String conversationId, String streamId) {
        // 1. 只清理自己这条流；如果已经开了新流（stream key 被换掉），不要动新流
        String current = redisTemplate.opsForValue().get(STREAM_PREFIX + conversationId);
        if (streamId.equals(current)) {
            redisTemplate.delete(List.of(
                    STREAM_PREFIX + conversationId,
                    STOP_PREFIX + conversationId
            ));
        }
    }

    /**
     * 为会话获取处理锁，保证同一会话同一时间只有一个请求。
     *
     * @param conversationId 会话 id
     * @return 获得的锁 key，用于结束时释放
     */
    private String acquireLock(String conversationId) {
        // 1. 同一会话同一时间只允许一个请求，避免记忆被并发覆盖
        String lockKey = LOCK_PREFIX + conversationId;
        Boolean acquired = redisTemplate.opsForValue()
                // 与 SSE 最长 5 分钟对齐，避免长回答/图片生成时锁提前过期
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(300));
        if (!Boolean.TRUE.equals(acquired)) {
            throw new AgentException(AgentErrorCode.CONVERSATION_BUSY, "上一条消息还在处理中，请稍候");
        }
        return lockKey;
    }

    /**
     * 从模型响应中提取完整回复文本，取不到时回退到累积的 token 文本。
     *
     * @param response 模型响应
     * @param fallback 响应为空时使用的回退文本
     * @return 最终回复文本
     */
    private String extractText(ChatResponse response, String fallback) {
        // 1. 优先用官方响应里的完整文本，拿不到就用累积的 token
        if (response == null) {
            return fallback;
        }
        AiMessage aiMessage = response.aiMessage();
        if (aiMessage == null || !StringUtils.hasText(aiMessage.text())) {
            return fallback;
        }
        return aiMessage.text();
    }

    /**
     * 取异常最底层的错误信息，用于返回给前端。
     *
     * @param error 原始异常
     * @return 最底层的错误信息，无信息时返回异常类名
     */
    private String rootMessage(Throwable error) {
        // 1. 取最底层异常信息，避免前端只看到一层包装
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String msg = current.getMessage();
        return StringUtils.hasText(msg) ? msg : current.getClass().getSimpleName();
    }

    /**
     * 向 SSE 连接推送一个指定名称的事件。
     *
     * @param emitter SSE 连接
     * @param event   事件名，如 meta、delta、done、error
     * @param data    事件数据，会被序列化为 JSON
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
     * 一次请求里所有不变的中间数据。
     *
     * @param principal          当前登录身份
     * @param conversationId     会话 id
     * @param model              本次使用的模型定义
     * @param userText           追加图片识别结果后的用户文本
     * @param customPrompt       用户自定义提示词
     * @param userMessage        已保存的用户消息
     * @param assistantMessageId 预生成的 AI 消息 id
     */
    private record Prepared(
            Principal principal,
            String conversationId,
            AiModelDefinition model,
            String userText,
            String customPrompt,
            ChatMessageDto userMessage,
            String assistantMessageId
    ) {
    }
}
