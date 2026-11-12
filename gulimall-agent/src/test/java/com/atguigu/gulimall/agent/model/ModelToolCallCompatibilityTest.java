package com.atguigu.gulimall.agent.model;

import com.atguigu.gulimall.agent.support.AgentIntegrationTestSupport;
import com.atguigu.gulimall.agent.tool.ToolArgumentNormalizer;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐个真实调用内置模型，检查 function calling 返回的工具参数能否被解析。
 *
 * <p>这个测试会访问真实模型服务，默认由 surefire 的 integration 标签排除。
 * 目标是发现某家模型输出尾随逗号、中文标点、Markdown 代码块等非严格 JSON 的情况。
 */
@Tag("integration")
class ModelToolCallCompatibilityTest extends AgentIntegrationTestSupport {

    private static final String PROMPT =
            "请必须调用 search 工具查询 keyword=华为手机、page=1，不要直接回答，也不要省略工具调用。";

    @Autowired
    private ModelCatalog modelCatalog;

    @Autowired
    private ModelFactory modelFactory;

    /**
     * 遍历所有内置模型：支持工具的走流式工具调用，不支持工具的走普通对话。
     */
    @Test
    void allModelsProduceParseableToolArguments() throws Exception {
        // 1. 取出所有启用的内置模型
        List<AiModelDefinition> models = modelCatalog.list(TEST_USER);
        List<String> problems = new ArrayList<>();
        List<ProbeResult> results = new ArrayList<>();

        // 2. 逐个模型真实调用
        for (AiModelDefinition model : models) {
            ProbeResult result = probe(model);
            results.add(result);
            if ("INVALID".equals(result.status()) || "ERROR".equals(result.status())
                    || "TIMEOUT".equals(result.status())) {
                problems.add(result.toString());
            }
            System.out.println(format(result));
        }

        // 3. 汇总结果；无法修复的 JSON、模型异常、超时都算失败
        System.out.println("\n模型工具参数兼容性汇总：");
        System.out.println("MODEL | TOOLS | MODE | STATUS | DETAIL");
        results.forEach(result -> System.out.println(format(result)));
        assertTrue(problems.isEmpty(), "存在模型工具参数兼容问题：\n" + String.join("\n", problems));
    }

    private ProbeResult probe(AiModelDefinition source) {
        // 1. 复制一份短超时模型定义，避免单个不可用模型拖住整轮测试
        AiModelDefinition model = source.withTimeout(Duration.ofSeconds(30));
        model.setStreamingTimeout(Duration.ofSeconds(30));

        // 2. 不支持工具的模型只验证普通对话能完成
        if (!Boolean.TRUE.equals(model.getTools())) {
            try {
                ProbeAssistant assistant = AiServices.builder(ProbeAssistant.class)
                        .chatModel(modelFactory.chat(model))
                        .build();
                assistant.chat("请只回复：OK");
                return new ProbeResult(model.getId(), "false", "chat", "TOOLS_DISABLED", "模型不支持工具");
            } catch (Exception e) {
                return new ProbeResult(model.getId(), "false", "chat", "ERROR", rootMessage(e));
            }
        }

        // 3. 支持工具的模型走流式工具调用，和生产链路一致
        List<String> captured = new CopyOnWriteArrayList<>();
        ToolSpecification spec;
        try {
            Method method = ProbeTools.class.getMethod("search", String.class, Integer.class);
            spec = ToolSpecifications.toolSpecificationFrom(method);
        } catch (Exception e) {
            return new ProbeResult(model.getId(), "true", "stream", "ERROR", rootMessage(e));
        }

        Map<ToolSpecification, ToolExecutor> tools = Map.of(spec,
                (ToolExecutionRequest request, Object memoryId) -> {
                    captured.add(request.arguments());
                    return "{\"ok\":true}";
                });

        try {
            ProbeStreamingAssistant assistant = AiServices.builder(ProbeStreamingAssistant.class)
                    .streamingChatModel(modelFactory.streaming(model))
                    .tools(tools)
                    .build();

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            TokenStream stream = assistant.chat(PROMPT);
            stream.onPartialResponse(token -> {
            }).onCompleteResponse(response -> latch.countDown()).onError(ex -> {
                error.set(ex);
                latch.countDown();
            }).start();

            if (!latch.await(45, TimeUnit.SECONDS)) {
                return new ProbeResult(model.getId(), "true", "stream", "TIMEOUT", "45 秒内未完成");
            }
            if (error.get() != null) {
                return new ProbeResult(model.getId(), "true", "stream", "ERROR", rootMessage(error.get()));
            }
            if (captured.isEmpty()) {
                return new ProbeResult(model.getId(), "true", "stream", "NO_TOOL_CALL", "模型未返回工具调用");
            }
            return classify(model, captured);
        } catch (Exception e) {
            return new ProbeResult(model.getId(), "true", "stream", "ERROR", rootMessage(e));
        }
    }

    private ProbeResult classify(AiModelDefinition model, List<String> captured) {
        // 1. 逐个检查原始参数：先按严格 JSON 解析，失败再尝试兼容层修复
        boolean repaired = false;
        for (String raw : captured) {
            try {
                objectMapper.readTree(raw);
            } catch (Exception strictFailure) {
                try {
                    ToolArgumentNormalizer.normalize(raw);
                    repaired = true;
                } catch (Exception repairFailure) {
                    return new ProbeResult(model.getId(), "true", "stream", "INVALID",
                            "原始参数：" + raw + "；修复失败：" + rootMessage(repairFailure));
                }
            }
        }
        // 2. 能直接解析是 OK；只有兼容层能解析是 REPAIRED
        return new ProbeResult(model.getId(), "true", "stream",
                repaired ? "REPAIRED" : "OK", captured.toString());
    }

    private String format(ProbeResult result) {
        return result.model() + " | " + result.tools() + " | " + result.mode()
                + " | " + result.status() + " | " + result.detail();
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    /**
     * 普通对话探针。
     */
    interface ProbeAssistant {
        String chat(@UserMessage String message);
    }

    /**
     * 流式对话探针。
     */
    interface ProbeStreamingAssistant {
        TokenStream chat(@UserMessage String message);
    }

    /**
     * 测试工具：模型必须调用它才能完成探针。
     */
    static class ProbeTools {
        @Tool("搜索商品")
        public String search(String keyword, Integer page) {
            return "ok";
        }
    }

    /**
     * 单个模型的探测结果。
     */
    private record ProbeResult(String model, String tools, String mode, String status, String detail) {
    }
}
