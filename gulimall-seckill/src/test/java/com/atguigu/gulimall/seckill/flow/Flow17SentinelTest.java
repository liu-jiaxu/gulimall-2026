package com.atguigu.gulimall.seckill.flow;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.atguigu.gulimall.seckill.controller.SeckillController;
import com.atguigu.gulimall.seckill.controller.SeckillOrderController;
import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 17：Sentinel 限流、熔断、热点参数与 fallback。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos。
 * <p>需要关闭：本机 gulimall-seckill。
 * 网关 100 QPS 和 order 侧 Feign fallback 需要网关/订单服务，本测试不驱动。
 */
@Tag("integration")
class Flow17SentinelTest extends SeckillIntegrationTestSupport {

    @Autowired
    private SeckillController seckillController;

    @Autowired
    private SeckillOrderController seckillOrderController;

    @AfterEach
    void clearRules() {
        // 1. 清空本用例加载的流控规则，避免 QPS=1 的规则泄漏到其它测试
        FlowRuleManager.loadRules(List.of());
        // 2. 清空熔断规则，避免异常比例规则继续影响后续请求
        DegradeRuleManager.loadRules(List.of());
        // 3. 清空热点参数规则，避免热点参数计数残留
        ParamFlowRuleManager.loadRules(List.of());
        // 4. 清空建单队列，避免下单测试产生的消息影响其它流程
        purgeSeckillOrderCreateQueue();
    }

    /**
     * 测试内容：为 seckillSkuInfo 配置 QPS=1 的流控规则后，同一秒内的第二次商品详情请求应被限流并返回 14013。
     */
    @Test
    void flowRuleBlocksSecondSkuInfoRequest() throws Exception {
        // 1. 创建测试场次和商品数据，并等 1.1 秒跨过一个统计窗口，避免受前一个请求影响
        Fixture fixture = createFixture();
        Thread.sleep(1100);
        // 2. 给 seckillSkuInfo 资源加载 QPS=1 的流控规则，只允许每秒一次请求
        FlowRule rule = new FlowRule("seckillSkuInfo");
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(1);
        FlowRuleManager.loadRules(List.of(rule));

        // 3. 拼出商品详情请求路径，第一次请求应该正常通过
        String path = "/seckill/sku/info?sessionId=" + fixture.sessionId()
                + "&skuId=" + fixture.skuId();
        assertOk(getJson(path, null));
        // 4. 同一秒内再请求一次，预期被 Sentinel 限流并返回错误码 14013
        assertCode(getJson(path, null), 14013);
    }

    /**
     * 测试内容：为 seckillOrderSubmit 配置 QPS=1 的流控规则后，同一秒内的第二次秒杀下单请求应被限流并返回 14013。
     */
    @Test
    void flowRuleBlocksSecondSubmitRequest() throws Exception {
        // 1. 创建测试场次和商品数据，并登录测试会员拿到后续下单要用的 token
        Fixture fixture = createFixture();
        String session = loginAsTestMember();
        // 2. 等 1.1 秒跨过一个统计窗口，再给 seckillOrderSubmit 加载 QPS=1 的流控规则
        Thread.sleep(1100);
        FlowRule rule = new FlowRule("seckillOrderSubmit");
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(1);
        FlowRuleManager.loadRules(List.of(rule));

        // 3. 组装一次完整的秒杀下单请求体，带上 requestId、场次、商品、地址和随机码
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "IT-" + UUID.randomUUID());
        body.put("sessionId", fixture.sessionId());
        body.put("skuId", fixture.skuId());
        body.put("quantity", 1);
        body.put("addrId", fixture.addressId());
        body.put("payType", 1);
        body.put("randomCode", fixture.randomCode());

        // 4. 第一次提交应该成功建单，并记录订单号供测试结束后清理
        Map<String, Object> first = postJson("/seckill/order/submit", body, session);
        assertOk(first);
        trackOrderSn(String.valueOf(orderResult(first).get("orderSn")));
        // 5. 同一秒内重复提交同一请求，预期被限流并返回错误码 14013
        assertCode(postJson("/seckill/order/submit", body, session), 14013);
    }

    /**
     * 测试内容：为探测资源加载异常比例熔断规则后，连续抛出异常应触发熔断器打开并抛出 BlockException。
     */
    @Test
    void degradeRuleOpensAfterExceptions() {
        // 1. 为一个探测资源加载异常比例熔断规则，异常比例超过 10% 且至少 1 次请求就熔断 10 秒
        String resource = "itSentinelDegradeProbe";
        DegradeRule rule = new DegradeRule(resource)
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.1)
                .setMinRequestAmount(1)
                .setTimeWindow(10);
        DegradeRuleManager.loadRules(List.of(rule));

        // 2. 连续进入该资源并主动抛异常，同时用 Tracer 记录异常，观察熔断器是否被打开
        boolean blocked = false;
        for (int i = 0; i < 5; i++) {
            try (Entry entry = SphU.entry(resource)) {
                try {
                    throw new IllegalStateException("模拟下游持续失败");
                } catch (RuntimeException ex) {
                    Tracer.trace(ex);
                }
            } catch (BlockException ex) {
                // 3. 一旦捕获到 BlockException 就说明熔断器已打开，记下结果并结束循环
                blocked = true;
                break;
            }
        }
        // 4. 断言循环中确实触发过熔断，否则说明异常比例规则没有生效
        assertTrue(blocked, "异常比例达到阈值后熔断器应打开");
    }

    /**
     * 测试内容：为热点参数资源配置参数 0 的 QPS=1 规则后，第一次相同热点参数放行，第二次应抛出 BlockException。
     */
    @Test
    void paramFlowRuleBlocksSameHotParam() {
        // 1. 为探测资源配置热点参数规则，只对参数 0 做 QPS=1 的限制
        String resource = "itSentinelParamProbe";
        ParamFlowRule rule = new ParamFlowRule(resource)
                .setParamIdx(0)
                .setCount(1);
        ParamFlowRuleManager.loadRules(List.of(rule));

        // 2. 第一次带热点参数 123L 进入资源，按理应放行；若被拦则直接让测试失败
        try (Entry first = SphU.entry(resource, EntryType.IN, 1, 123L)) {
            assertEquals(1, 1);
        } catch (BlockException e) {
            throw new AssertionError("第一次热点参数请求不应被拦", e);
        }
        // 3. 第二次带相同热点参数进入，预期因超过 QPS=1 抛出 BlockException
        assertThrows(BlockException.class,
                () -> SphU.entry(resource, EntryType.IN, 1, 123L));
    }

    /**
     * 测试内容：商品详情 fallback 方法在收到下游异常时应返回固定错误码 14009。
     */
    @Test
    void fallbackMethodReturnsStableError() {
        // 1. 直接调用商品详情 fallback 方法，模拟下游抛异常时的兜底处理
        Map<String, Object> response = seckillController.skuInfoFallback(
                1L, 1L, null, new RuntimeException("下游异常"));
        // 2. 断言 fallback 返回固定错误码 14009，保证降级响应稳定可预期
        assertCode(response, 14009);
    }

    /**
     * 测试内容：下单接口的 block handler 收到 FlowException 时应返回限流错误码 14013。
     */
    @Test
    void submitBlockHandlerReturnsFlowLimitCode() {
        // 1. 直接调用下单接口的 block handler，模拟订单提交被流控拦截时传入 FlowException
        Map<String, Object> response = seckillOrderController.submitBlocked(
                null, new com.alibaba.csp.sentinel.slots.block.flow.FlowException("test"));
        // 2. 断言 block handler 返回限流错误码 14013，确认前端能识别为“请求过于频繁”
        assertCode(response, 14013);
    }
}
