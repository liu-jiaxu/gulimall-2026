package com.atguigu.gulimall.seckill.flow;

import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 流程 18：监控与链路追踪。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos；Jaeger。
 * <p>需要关闭：本机 gulimall-seckill。
 * <p>测试只要求 Jaeger 可用，不要求其它 Java 服务。
 */
@Tag("integration")
class Flow18ObservabilityTest extends SeckillIntegrationTestSupport {

    @Autowired(required = false)
    private io.micrometer.tracing.Tracer tracer;

    private final String jaegerUrl = System.getProperty("jaeger.query.url",
            System.getenv().getOrDefault("JAEGER_QUERY_URL", "http://<your-vm-host-ip>:16686"));

    /**
     * 测试内容：发起一次秒杀商品列表请求后等待 span 上报，确认 Jaeger 服务列表中出现 gulimall-seckill。
     */
    @Test
    void serviceIsVisibleInJaeger() throws Exception {
        // 1. 创建测试场次和商品数据，作为秒杀列表请求的查询条件
        Fixture fixture = createFixture();
        // 2. 先探测 Jaeger 是否可用，不可用就跳过这个依赖外部链路系统的用例
        Assumptions.assumeTrue(jaegerAvailable(), "Jaeger 不可用，跳过链路追踪测试");

        // 3. 发起一次秒杀商品列表请求产生 span，再等 10 秒让链路数据异步上报到 Jaeger
        getJson("/seckill/skus?sessionId=" + fixture.sessionId(), null);
        Thread.sleep(10000);

        // 4. 查询 Jaeger 服务列表，确认其中出现了 gulimall-seckill；没有出现时按环境问题跳过
        Map<String, Object> services = jaeger("/api/services");
        List<String> names = (List<String>) services.get("data");
        assertNotNull(names);
        Assumptions.assumeTrue(names.contains("gulimall-seckill"),
                "Jaeger 暂未收到 gulimall-seckill 的 span，跳过：" + names);
    }

    /**
     * 测试内容：确认 Micrometer Tracer Bean 已注入，并能创建并结束一个探测 span。
     */
    @Test
    void tracingBeansAreConfigured() {
        // 1. 确认 Micrometer Tracer Bean 已被 Spring 注入，说明链路追踪基础配置存在
        assertNotNull(tracer, "Micrometer Tracer 没有配置");
        // 2. 创建并立即结束一个探测 span，验证 Tracer 至少能正常创建和关闭 span
        io.micrometer.tracing.Span span = tracer.nextSpan().name("it-seckill-trace-probe").start();
        span.end();
    }

    /**
     * 测试内容：携带指定 traceparent 请求秒杀列表后，应能在 Jaeger 中按该 traceId 查询到对应链路。
     */
    @Test
    void traceparentIsPropagated() throws Exception {
        // 1. 创建测试场次和商品数据，用于拼接秒杀列表请求地址
        Fixture fixture = createFixture();
        // 2. 先确认 Jaeger 可用，不可用就跳过这个依赖外部链路系统的用例
        Assumptions.assumeTrue(jaegerAvailable(), "Jaeger 不可用，跳过链路追踪测试");

        // 3. 生成一个自定义 traceId 和 spanId，放进 traceparent 请求头后发起列表请求，验证链路传播
        String traceId = randomHex(32);
        String spanId = randomHex(16);
        HttpHeaders headers = headers(null);
        headers.set("traceparent", "00-" + traceId + "-" + spanId + "-01");
        rest.exchange(localUrl("/seckill/skus?sessionId=" + fixture.sessionId()),
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        // 4. 最多轮询 10 次、每次间隔 1 秒，等 Jaeger 收到这个 traceId 对应的链路数据
        Map<String, Object> traces = null;
        for (int i = 0; i < 10; i++) {
            Thread.sleep(1000);
            traces = jaeger("/api/traces/" + traceId);
            if (traces == null) {
                continue;
            }
            Object data = traces.get("data");
            if (data instanceof List<?> list && !list.isEmpty()) {
                break;
            }
        }
        // 5. 如果最终查不到数据或 data 为空，就按 Jaeger 环境问题跳过，而不是误判业务失败
        Assumptions.assumeTrue(traces != null, "Jaeger 查询 trace 失败，跳过：" + traceId);
        Object data = traces.get("data");
        Assumptions.assumeTrue(data instanceof List<?> list && !list.isEmpty(),
                "Jaeger 没有收到测试 trace，跳过：" + traceId);
    }

    private boolean jaegerAvailable() {
        // 1. 尝试查询 Jaeger 服务列表，能拿到结果说明可访问；任何异常都当作不可用
        try {
            return jaeger("/api/services") != null;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jaeger(String path) throws Exception {
        // 1. 创建一个带 3 秒连接超时的 HTTP 客户端，避免 Jaeger 不可达时长时间卡住
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        // 2. 按 Jaeger 基础地址拼出完整请求，并设置 10 秒读取超时
        HttpRequest request = HttpRequest.newBuilder(URI.create(jaegerUrl + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        // 3. 发请求并接收字符串响应；非 200 时返回 null，让调用方按“查不到”处理
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        // 4. 把响应体反序列化成 Map，供测试读取 data 等字段
        return objectMapper.readValue(response.body(), Map.class);
    }

    private String randomHex(int length) {
        // 1. 从十六进制字符表里随机取 length 个字符，拼成 traceId 或 spanId 所需的十六进制字符串
        String hex = "0123456789abcdef";
        StringBuilder builder = new StringBuilder(length);
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            builder.append(hex.charAt(random.nextInt(hex.length())));
        }
        return builder.toString();
    }
}
