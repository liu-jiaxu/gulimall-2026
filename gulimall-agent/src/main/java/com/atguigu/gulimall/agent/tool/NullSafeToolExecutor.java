package com.atguigu.gulimall.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 兼容 LangChain4j 1.0.1 的工具执行器。
 *
 * <p>模型对“可为空”的参数会显式返回 {@code null}，
 * 而 1.0.1 的 {@link DefaultToolExecutor#prepareArguments} 不判断 null，
 * 在 {@code getDoubleValue} 里直接抛 NPE，流式回答就此卡住、前端一直转圈。
 *
 * <p>这里在交给默认执行器之前把值为 null 的参数删掉：
 * “没有这个参数”在工具方法里等价于“不按这个条件筛选”，
 * 比抛异常或让模型重试更稳定。
 *
 * <p>模型返回的 JSON 参数也可能缺值或带多余逗号。默认执行器解析失败会直接抛异常，
 * 而 LangChain4J 1.0.1 的流式 SSE 监听器会吞掉这个异常，导致 onComplete/onError 都不触发、
 * 前端一直转圈且会话锁不释放；所以这里把执行异常转成工具错误结果交回模型。
 */
@Slf4j
public class NullSafeToolExecutor implements ToolExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolExecutor delegate;

    /**
     * 创建空值安全的工具执行器，内部委托给默认执行器完成实际调用。
     *
     * @param tool   工具实例，即声明了工具方法的对象
     * @param method 要执行的工具方法
     */
    public NullSafeToolExecutor(Object tool, Method method) {
        // 1. 默认执行器负责参数类型转换、调用方法和序列化返回值
        this.delegate = new DefaultToolExecutor(tool, method);
    }

    /**
     * 过滤掉模型显式传入的 null 参数后执行工具方法。
     *
     * @param request  工具执行请求，包含工具名和 JSON 参数
     * @param memoryId 会话记忆 id，用于区分不同的对话上下文
     * @return 工具方法的执行结果
     */
    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        // 1. 先过滤掉模型显式传的 null 参数
        ToolExecutionRequest safeRequest = dropNullArguments(request);

        try {
            // 2. 再交给默认执行器，正常调用工具方法
            return delegate.execute(safeRequest, memoryId);
        } catch (Exception ex) {
            // 3. 参数不是合法 JSON、类型转换失败等异常不能让流式回调直接抛出：
            //    LangChain4J 的 SSE 监听器会吞掉这个异常，onComplete/onError 都不触发，
            //    最终前端一直转圈、会话锁也不释放。这里改成把错误作为工具结果交回模型。
            log.warn("工具执行失败 tool={} arguments={}", request.name(), request.arguments(), ex);
            return errorResult(ex);
        }
    }

    /**
     * 把工具执行异常包装成模型可读的 JSON 工具结果。
     *
     * @param ex 工具执行异常
     * @return 包含 error 字段的 JSON 字符串
     */
    private String errorResult(Exception ex) {
        // 1. 取最底层异常信息，避免模型只看到一层包装
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        String reason = message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;

        // 2. 用 JSON 返回，模型能看懂这是工具失败，而不是工具返回了业务数据
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", "工具调用失败：" + reason);
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception ignored) {
            // 3. 极端情况下 JSON 也失败，返回纯文本，至少不让异常继续上抛
            return "工具调用失败：" + reason;
        }
    }

    /**
     * 删除工具请求参数中值为 null 的键，避免默认执行器转换参数时抛出空指针。
     *
     * @param request 原始工具执行请求
     * @return 过滤后的工具执行请求；没有参数、参数不是 JSON 或解析失败时返回原请求
     */
    private ToolExecutionRequest dropNullArguments(ToolExecutionRequest request) {
        // 1. 没有参数或参数不是 JSON 时，原样交给默认执行器
        String json = request.arguments();
        if (json == null || json.isBlank()) {
            return request;
        }

        try {
            // 2. 先兼容各模型的非严格 JSON：代码块、尾随逗号、中文标点、双重编码
            String normalized = ToolArgumentNormalizer.normalize(json);

            // 3. 解析成有序 Map，删掉 value 为 null 的键
            Map<String, Object> args = MAPPER.readValue(
                    normalized, new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            boolean removed = args.entrySet().removeIf(entry -> entry.getValue() == null);

            // 4. 没有 null 也要把“修复后的 JSON”交给默认执行器，不能退回原始非法参数
            String safeJson = removed ? MAPPER.writeValueAsString(args) : normalized;
            if (!removed && normalized.equals(json)) {
                return request;
            }

            // 5. 用过滤/修复后的参数重建请求
            return ToolExecutionRequest.builder()
                    .id(request.id())
                    .name(request.name())
                    .arguments(safeJson)
                    .build();
        } catch (Exception e) {
            // 5. 无法修复时保留原始请求，让 execute 统一转成模型可读的工具错误
            return request;
        }
    }
}
