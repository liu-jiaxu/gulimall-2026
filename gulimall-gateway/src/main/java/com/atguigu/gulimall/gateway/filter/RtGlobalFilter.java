package com.atguigu.gulimall.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 自定义全局过滤器：打印请求 URI 与耗时（仿照 cloud-demo/gateway 学习示例移植）
 *
 * <p>全局过滤器对所有路由自动生效，无需在 yaml 中配置；实现 {@link Ordered}
 * 控制执行优先级，数值越小越先执行。</p>
 */
@Component
public class RtGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RtGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String uri = request.getURI().toString();
        long start = System.currentTimeMillis();

        // 1. 打印进入网关的完整请求信息：
        //    method / uri / 远端地址 / 全部请求头。
        //    Cookie 只打印名字不打印值，token/authorization 只打印“存在”，避免泄露凭证。
        log.info("[gateway-request] method={} uri={} remote={} headers={}",
                request.getMethod(), uri, request.getRemoteAddress(), formatHeaders(request.getHeaders()));

        // 2. 响应式编程是异步执行，doFinally 在整条链路结束后回调；
        //    这时才能拿到最终响应状态、响应头、命中的路由和转发目标。
        return chain.filter(exchange)
                .doFinally(signal -> {
                    ServerHttpResponse response = exchange.getResponse();
                    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
                    URI target = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
                    long end = System.currentTimeMillis();
                    log.info("[gateway-response] signal={} method={} uri={} status={} route={} target={} headers={} cost={}ms",
                            signal, request.getMethod(), uri, response.getStatusCode(),
                            route == null ? null : route.getId(), target,
                            formatHeaders(response.getHeaders()), end - start);
                });
    }

    /**
     * 打印全部请求头/响应头。
     *
     * @param headers 请求头或响应头
     * @return 格式化后的头信息
     */
    private String formatHeaders(HttpHeaders headers) {
        // 1. Cookie、Authorization、token 属于敏感信息，只保留名字或是否存在
        StringBuilder result = new StringBuilder("{");
        headers.forEach((name, values) -> {
            if (result.length() > 1) {
                result.append(", ");
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if ("cookie".equals(lower) || "set-cookie".equals(lower)) {
                result.append(name).append("=<names:").append(cookieNames(values)).append(">");
            } else if ("authorization".equals(lower) || "token".equals(lower) || "x-token".equals(lower)) {
                result.append(name).append("=<redacted>");
            } else {
                result.append(name).append("=").append(values);
            }
        });
        return result.append("}").toString();
    }

    /**
     * 从 Cookie / Set-Cookie 中只提取 Cookie 名，不输出值。
     *
     * @param values Cookie 头的值列表
     * @return 逗号分隔的 Cookie 名
     */
    private String cookieNames(List<String> values) {
        Set<String> names = new LinkedHashSet<>();
        for (String value : values) {
            for (String part : value.split(";")) {
                int equals = part.indexOf('=');
                if (equals > 0) {
                    names.add(part.substring(0, equals).trim());
                }
            }
        }
        return String.join(",", names);
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
