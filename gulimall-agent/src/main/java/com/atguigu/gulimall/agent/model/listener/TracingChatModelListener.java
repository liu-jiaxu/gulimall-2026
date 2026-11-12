package com.atguigu.gulimall.agent.model.listener;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 每个模型的统一监听器。
 *
 * <p>做三件事：
 * 1. 为每次请求生成 TraceID，串起 request / response / error 三段日志；
 * 2. 记录耗时和 token 用量，便于估算成本和排查慢请求；
 * 3. 不打印用户消息正文，避免把隐私写进日志。
 */
@Slf4j
@Component
public class TracingChatModelListener implements ChatModelListener {

    private static final String TRACE_ID = "aiTraceId";
    private static final String START_TIME = "aiStartTime";

    /**
     * 请求发出前生成 TraceID 和开始时间，并记录请求消息条数。
     *
     * @param requestContext 本次请求的上下文，用于读写属性和获取请求内容
     */
    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        // 1. 生成 TraceID 放进 attributes，同一请求的响应/异常都能取到
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        requestContext.attributes().put(TRACE_ID, traceId);
        requestContext.attributes().put(START_TIME, System.currentTimeMillis());

        // 2. 只记录消息条数，不记录正文
        int messageCount = requestContext.chatRequest().messages() == null
                ? 0 : requestContext.chatRequest().messages().size();
        log.info("[AI-REQ] traceId={} provider={} messages={}",
                traceId, requestContext.modelProvider(), messageCount);
    }

    /**
     * 响应返回后按 TraceID 记录本次调用耗时和 token 用量。
     *
     * @param responseContext 本次响应的上下文，用于取回 TraceID、开始时间和 token 用量
     */
    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        // 1. 取回同一次请求的 TraceID 和开始时间
        Object traceId = responseContext.attributes().get(TRACE_ID);
        long cost = costMillis(responseContext.attributes().get(START_TIME));

        // 2. token 用量可能为空（部分代理不返回），空指针要防
        TokenUsage usage = responseContext.chatResponse().tokenUsage();
        if (usage == null) {
            log.info("[AI-RESP] traceId={} cost={}ms tokens=unknown", traceId, cost);
        } else {
            log.info("[AI-RESP] traceId={} cost={}ms input={} output={} total={}",
                    traceId, cost, usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());
        }
    }

    /**
     * 调用异常时按 TraceID 记录 provider 和错误信息。
     *
     * @param errorContext 本次错误的上下文，用于取回 TraceID 和异常详情
     */
    @Override
    public void onError(ChatModelErrorContext errorContext) {
        // 1. 异常日志保留 traceId 和堆栈，方便和上游请求对上
        Object traceId = errorContext.attributes().get(TRACE_ID);
        log.error("[AI-ERR] traceId={} provider={} error={}",
                traceId, errorContext.modelProvider(), errorContext.error().getMessage(), errorContext.error());
    }

    /**
     * 计算从开始时间到当前时刻的耗时毫秒数。
     *
     * @param start 请求开始时间，非 Long 类型时视为无效
     * @return 耗时毫秒数，开始时间无效时返回 -1
     */
    private long costMillis(Object start) {
        if (!(start instanceof Long startTime)) {
            return -1;
        }
        return System.currentTimeMillis() - startTime;
    }
}
