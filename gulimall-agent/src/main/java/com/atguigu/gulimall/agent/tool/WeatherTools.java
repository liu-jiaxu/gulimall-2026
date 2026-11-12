package com.atguigu.gulimall.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * 天气工具：优先调用阿里云市场的 24 小时天气接口，失败时回退到 wttr.in。
 *
 * <p>2026-11-05 实测：阿里云 jmweather 对济南、北京等城市可以正常返回。
 * 早期把中文直接拼进 String body 且没有指定 UTF-8，服务端收到乱码后会返回
 * {@code 702 未找到该地区}；现在改用表单对象提交，由 FormHttpMessageConverter 按 UTF-8 编码。
 * 城市不支持或接口异常时仍回退到无需 Key 的 wttr.in。
 */
public class WeatherTools {

    private final RestClient restClient;
    private final String appCode;
    private final RestClient fallbackClient;
    private final ObjectMapper mapper;

    /**
     * 创建天气工具实例，同时初始化主接口和兜底接口的客户端。
     *
     * @param baseUrl         阿里云天气接口基础地址
     * @param appCode         阿里云天气接口的 AppCode，为空时不调用主接口
     * @param fallbackBaseUrl 天气兜底接口基础地址，例如 wttr.in
     * @param mapper          JSON 序列化器
     */
    public WeatherTools(String baseUrl, String appCode, String fallbackBaseUrl, ObjectMapper mapper) {
        // 1. 主接口：阿里云云市场，连接和读取都必须有超时，避免工具调用被无限挂起
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(3000, 8000))
                .build();
        this.appCode = appCode;

        // 2. 兜底接口：wttr.in，无需 AppCode，给稍长一点的读取超时
        this.fallbackClient = RestClient.builder()
                .baseUrl(fallbackBaseUrl)
                .requestFactory(requestFactory(5000, 12000))
                .build();
        this.mapper = mapper;
    }

    /**
     * 创建带连接超时和读取超时的同步请求工厂。
     *
     * <p>不要使用默认的异步 JDK HttpClient。工具调用被模型超时或用户停止取消时，
     * 异步请求会抛 {@link CancellationException}；同步请求只会抛普通的 IO 超时异常。
     *
     * @param connectTimeoutMs 连接超时毫秒数
     * @param readTimeoutMs    读取超时毫秒数
     * @return 同步 HTTP 请求工厂
     */
    private static SimpleClientHttpRequestFactory requestFactory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }

    /**
     * 查询指定城市或地区未来 24 小时的天气，主接口失败时自动回退到 wttr.in。
     *
     * @param city 城市或地区名，例如 济南、北京
     * @return 天气数据的 JSON 文本；未配置 AppCode、城市为空或查询失败时返回中文提示
     */
    @Tool("查询某个城市/地区未来 24 小时天气")
    public String queryWeather(@P("城市或地区名，例如 济南、北京") String city) {
        // 1. 没配 AppCode 时明确告诉用户，不编造天气
        if (!StringUtils.hasText(appCode)) {
            return "天气服务未配置：请设置环境变量 ALIYUN_WEATHER_APP_CODE";
        }
        if (city == null || city.isBlank()) {
            return "查询失败：城市不能为空";
        }

        try {
            // 2. 用 MultiValueMap 提交表单，FormHttpMessageConverter 会按 UTF-8 编码中文城市名
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("area", city.trim());
            String result = restClient.post()
                    .uri("/weather/query/24h")
                    .header("Authorization", "APPCODE " + appCode)
                    .contentType(new MediaType(MediaType.APPLICATION_FORM_URLENCODED, StandardCharsets.UTF_8))
                    .body(form)
                    // 3. 使用 exchange 读取任意状态码的响应体；阿里云 400 也带 success=false，不抛异常
                    .exchange((request, response) -> response.bodyTo(String.class));

            // 4. 阿里云商品失效时会返回 success=false，这时走兜底
            if (result != null && result.contains("\"success\":true")) {
                return ToolResult.raw(result);
            }
            return queryWttr(city, "阿里云天气返回失败");
        } catch (CancellationException ex) {
            // 5. 模型超时或用户停止流式回答会取消工具调用，此时不要再发起兜底请求
            if (Thread.currentThread().isInterrupted()) {
                return "天气查询已取消";
            }
            return queryWttr(city, "阿里云天气请求被取消");
        } catch (Exception ex) {
            // 6. 网络/鉴权异常也走兜底
            return queryWttr(city, "阿里云天气调用异常："
                    + ex.getClass().getSimpleName() + "：" + ex.getMessage());
        }
    }

    /**
     * wttr.in 兜底：返回当前天气 + 未来 24 小时（3 小时间隔）。
     *
     * @param city   城市或地区名，调用时会去除首尾空白
     * @param reason 主接口失败的原因，会一并返回给模型便于说明
     * @return 精简后的天气 JSON 文本；兜底接口失败时返回中文错误提示
     */
    private String queryWttr(String city, String reason) {
        try {
            // 1. 请求 JSON：中文城市名走 URL 编码
            String json = fallbackClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/{city}")
                            .queryParam("format", "j1")
                            .queryParam("lang", "zh")
                            .build(city.trim()))
                    .header("Accept-Language", "zh-CN")
                    .retrieve()
                    .body(String.class);
            if (!StringUtils.hasText(json)) {
                return "天气查询失败：" + reason + "；兜底接口也没有返回数据";
            }

            // 2. 只挑模型需要的字段，避免把整份 25KB JSON 塞进上下文
            JsonNode root = mapper.readTree(json);
            JsonNode current = root.path("current_condition").path(0);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", "wttr.in");
            data.put("city", city.trim());
            data.put("reason", reason);
            data.put("current", Map.of(
                    "tempC", current.path("temp_C").asText(),
                    "feelsLikeC", current.path("FeelsLikeC").asText(),
                    "humidity", current.path("humidity").asText(),
                    "windKmph", current.path("windspeedKmph").asText(),
                    "desc", current.path("weatherDesc").path(0).path("value").asText(),
                    "observedAt", current.path("observation_time").asText()
            ));

            // 3. 取今天 8 个 3 小时节点，正好覆盖未来 24 小时
            List<Map<String, String>> hourly = new ArrayList<>();
            JsonNode hours = root.path("weather").path(0).path("hourly");
            for (int i = 0; i < hours.size() && i < 8; i++) {
                JsonNode hour = hours.get(i);
                Map<String, String> item = new LinkedHashMap<>();
                item.put("time", hour.path("time").asText());
                item.put("tempC", hour.path("tempC").asText());
                item.put("desc", hour.path("weatherDesc").path(0).path("value").asText());
                item.put("chanceOfRain", hour.path("chanceofrain").asText());
                hourly.add(item);
            }
            data.put("hourly", hourly);
            return ToolResult.raw(mapper.writeValueAsString(data));
        } catch (CancellationException ex) {
            // 4. 工具调用被取消时不再继续等待兜底接口
            if (Thread.currentThread().isInterrupted()) {
                return "天气查询已取消";
            }
            return "天气查询失败：" + reason + "；兜底接口请求被取消";
        } catch (Exception ex) {
            return "天气查询失败：" + reason + "；兜底接口也失败："
                    + ex.getClass().getSimpleName() + "：" + ex.getMessage();
        }
    }
}
