package com.atguigu.gulimall.gateway.predicate;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.cloud.gateway.handler.predicate.AbstractRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.GatewayPredicate;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ServerWebExchange;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * 自定义断言工厂：Token（WebFlux 版网关，用于 coupon 路由）
 *
 * <p>命名规则：类名去掉 {@code RoutePredicateFactory} 前缀即为配置中使用的名字，
 * 本类 {@code TokenRoutePredicateFactory} → 配置名 {@code Token}：</p>
 * <pre>
 *   yaml配置文件全部写法：
 *   predicates:
 *     - Token=X-Token                    # 短写法：请求头 X-Token 存在即可
 *     - Token=X-Token,admin              # 短写法：请求头 X-Token 必须等于 admin
 *     - name: Token                      # 全写法
 *       args:
 *         headerName: X-Token
 *         headerValue: admin
 * </pre>
 *
 * <p>断言规则：请求必须携带名为 {@code headerName} 的请求头；若配置了 {@code headerValue}，
 * 则该请求头的值必须精确匹配。</p>
 */
@Component
public class TokenRoutePredicateFactory extends AbstractRoutePredicateFactory<TokenRoutePredicateFactory.Config> {

    public TokenRoutePredicateFactory() {
        super(Config.class);
    }

    // 短写法属性顺序：Token=X-Token,admin → headerName=X-Token, headerValue=admin
    @Override
    public List<String> shortcutFieldOrder() {
        return Arrays.asList("headerName", "headerValue");
    }

    @Override
    public Predicate<ServerWebExchange> apply(Config config) {
        return new GatewayPredicate() {
            @Override
            public boolean test(ServerWebExchange exchange) {
                List<String> values = exchange.getRequest().getHeaders().get(config.getHeaderName());
                if (values == null || values.isEmpty()) {
                    return false;
                }
                // 未配置期望值：只要有该请求头即放行
                if (config.getHeaderValue() == null || config.getHeaderValue().isBlank()) {
                    return true;
                }
                return values.stream().anyMatch(config.getHeaderValue()::equals);
            }

            @Override
            public Object getConfig() {
                return config;
            }

            @Override
            public String toString() {
                return String.format("Token: headerName=%s headerValue=%s",
                        config.getHeaderName(), config.getHeaderValue());
            }
        };
    }

    @Validated
    public static class Config {

        @NotEmpty
        private String headerName;
        private String headerValue;

        public String getHeaderName() {
            return headerName;
        }

        public Config setHeaderName(String headerName) {
            this.headerName = headerName;
            return this;
        }

        public String getHeaderValue() {
            return headerValue;
        }

        public Config setHeaderValue(String headerValue) {
            this.headerValue = headerValue;
            return this;
        }
    }
}
