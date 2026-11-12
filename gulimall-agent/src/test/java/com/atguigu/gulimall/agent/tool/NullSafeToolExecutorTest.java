package com.atguigu.gulimall.agent.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NullSafeToolExecutorTest {

    static class Demo {
        @Tool("demo")
        public String search(Long id, String name) {
            return "id=" + id + ",name=" + name;
        }
    }

    /**
     * 测试工具执行器会过滤模型显式传入的 null 参数。
     */
    @Test
    void shouldDropNullArguments() throws Exception {
        // 1. 模拟模型显式传 null 的数字参数
        Demo demo = new Demo();
        Method method = Demo.class.getMethod("search", Long.class, String.class);
        NullSafeToolExecutor executor = new NullSafeToolExecutor(demo, method);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1")
                .name("search")
                .arguments("{\"id\":null,\"name\":\"x\"}")
                .build();

        // 2. 过滤后应正常调用，而不是抛 NPE
        assertEquals("id=null,name=x", executor.execute(request, "memory"));
    }

    /**
     * 测试模型返回非法 JSON 参数时，执行器返回错误结果而不是把异常抛给流式回调。
     */
    @Test
    void shouldReturnErrorResultWhenArgumentsAreInvalidJson() throws Exception {
        // 1. 构造一段模型可能返回的非法 JSON 参数，模拟逗号前缺少字段值
        Demo demo = new Demo();
        Method method = Demo.class.getMethod("search", Long.class, String.class);
        NullSafeToolExecutor executor = new NullSafeToolExecutor(demo, method);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("2")
                .name("search")
                .arguments("{\"id\":1,\"name\":}")
                .build();

        // 2. 执行器必须捕获解析异常，并返回带 error 字段的工具结果
        String result = executor.execute(request, "memory");
        assertTrue(result.contains("工具调用失败"), "非法参数应返回工具错误结果");
    }

    /**
     * 测试模型返回尾随逗号时，兼容层能修成合法 JSON 并正常调用工具。
     */
    @Test
    void shouldRepairTrailingComma() throws Exception {
        // 1. 模拟 Qwen 常见的 {"id":1,"name":"x",}
        Demo demo = new Demo();
        Method method = Demo.class.getMethod("search", Long.class, String.class);
        NullSafeToolExecutor executor = new NullSafeToolExecutor(demo, method);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("3")
                .name("search")
                .arguments("{\"id\":1,\"name\":\"x\",}")
                .build();

        // 2. 修复后应正常返回工具结果
        assertEquals("id=1,name=x", executor.execute(request, "memory"));
    }

    /**
     * 测试模型把参数包在 Markdown 代码块里时，兼容层能去掉代码块标记。
     */
    @Test
    void shouldStripMarkdownCodeFence() throws Exception {
        // 1. 模拟 ```json ... ``` 格式
        Demo demo = new Demo();
        Method method = Demo.class.getMethod("search", Long.class, String.class);
        NullSafeToolExecutor executor = new NullSafeToolExecutor(demo, method);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("4")
                .name("search")
                .arguments("```json\n{\"id\":1,\"name\":\"x\"}\n```")
                .build();

        // 2. 去代码块后应正常执行
        assertEquals("id=1,name=x", executor.execute(request, "memory"));
    }

    /**
     * 测试模型使用中文冒号、中文逗号时，兼容层能修成标准 JSON。
     */
    @Test
    void shouldRepairChinesePunctuation() throws Exception {
        // 1. 模拟 {"id"：1，"name"："x"}
        Demo demo = new Demo();
        Method method = Demo.class.getMethod("search", Long.class, String.class);
        NullSafeToolExecutor executor = new NullSafeToolExecutor(demo, method);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("5")
                .name("search")
                .arguments("{\"id\"：1，\"name\"：\"x\"}")
                .build();

        // 2. 中文标点修完后应正常执行
        assertEquals("id=1,name=x", executor.execute(request, "memory"));
    }

    /**
     * 测试参数被模型再包一层字符串时，兼容层能拆开并成功调用。
     */
    @Test
    void shouldUnwrapDoubleEncodedJson() throws Exception {
        // 1. 模拟 "\"{\\\"id\\\":1,\\\"name\\\":\\\"x\\\"}\""
        Demo demo = new Demo();
        Method method = Demo.class.getMethod("search", Long.class, String.class);
        NullSafeToolExecutor executor = new NullSafeToolExecutor(demo, method);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("6")
                .name("search")
                .arguments("\"{\\\"id\\\":1,\\\"name\\\":\\\"x\\\"}\"")
                .build();

        // 2. 拆开双层编码后应正常执行
        assertEquals("id=1,name=x", executor.execute(request, "memory"));
    }
}
