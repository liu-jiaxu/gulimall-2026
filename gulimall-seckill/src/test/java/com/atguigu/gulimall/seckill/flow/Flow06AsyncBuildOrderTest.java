package com.atguigu.gulimall.seckill.flow;

import com.atguigu.gulimall.seckill.service.SeckillOrderService;
import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 06：MQ 消费者异步建单。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos；gulimall-order、gulimall-ware、gulimall-member。
 * <p>需要关闭：本机 gulimall-seckill，必须停止，避免抢建单消息。
 */
@Tag("integration")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.rabbitmq.listener.simple.auto-startup=true")
class Flow06AsyncBuildOrderTest extends SeckillIntegrationTestSupport {

    @Autowired
    private SeckillOrderService seckillOrderService;

    @BeforeEach
    void purgeQueue() {
        // 1. 清空秒杀建单队列，确保本用例只消费自己提交的消息，不被上一条残留消息干扰
        purgeSeckillOrderCreateQueue();
    }

    /**
     * 测试内容：MQ 消费者建单成功后应回填 order_id，gulimall-order 生成 source_type=2 的订单，gulimall-ware 生成工作单并完成秒杀库存锁定转换，且重复成功回写保持幂等。
     */
    @Test
    void listenerCreatesOrderAndConvertsSeckillStock() throws Exception {
        // 1. 准备测试数据：确认 order、ware、member 服务可用，创建场次，设置 ware 库存并登录会员
        requireService("gulimall-order");
        requireService("gulimall-ware");
        requireService("gulimall-member");

        Fixture fixture = createFixture();
        setWareStock(100, 0, 1);
        String session = loginAsTestMember();
        // 2. 发起调用：提交抢购订单，让 MQ 消费者异步去建单
        String orderSn = submit(fixture, fixture.addressId(), session);

        // 3. 等待结果：最多等 30 秒，直到秒杀单状态变成建单成功（1）
        awaitUntil("秒杀单建单成功", Duration.ofSeconds(30),
                () -> Objects.equals(1, seckillOrderStatus(orderSn)));

        // 4. 断言结果：秒杀单回填了 order_id，order 服务生成了 source_type=2、未支付的订单
        Map<String, Object> seckillOrder = seckillOrderRow(orderSn);
        assertNotNull(seckillOrder.get("order_id"), "建单成功后应回填 order_id");

        Map<String, Object> omsOrder = omsOrderRow(orderSn);
        assertNotNull(omsOrder, "order 服务没有创建订单");
        assertEquals(2, ((Number) omsOrder.get("source_type")).intValue());
        assertEquals(0, ((Number) omsOrder.get("status")).intValue());

        // 5. 断言结果：ware 生成了秒杀工作单，且普通锁定和秒杀锁定库存都已清零
        List<Map<String, Object>> tasks = jdbc("gulimall_wms").queryForList(
                "SELECT * FROM wms_ware_order_task WHERE order_sn = ?", orderSn);
        assertFalse(tasks.isEmpty(), "ware 没有创建秒杀工作单");
        assertEquals(1, ((Number) tasks.get(0).get("biz_type")).intValue());

        Map<String, Object> ware = wareSkuRow();
        assertEquals(0, ((Number) ware.get("stock_locked")).intValue());
        assertEquals(0, ((Number) ware.get("seckill_stock_locked")).intValue());

        // 6. 验证副作用：重复发送成功回写，确认订单 id 保持不变
        // 重复回写必须是幂等的，不会把订单 id 改掉
        Object orderId = seckillOrder.get("order_id");
        seckillOrderService.onBuildSuccess(orderSn, 999999L, null);
        assertEquals(orderId, seckillOrderRow(orderSn).get("order_id"));
    }

    /**
     * 测试内容：收货地址非法导致建单失败时，秒杀单应标记为建单失败并记录原因，同时把 Redis 库存和限购计数回补。
     */
    @Test
    void buildFailureMarksFailedAndReleasesReservation() throws Exception {
        // 1. 准备测试数据：确认 order、ware、member 服务可用，创建场次，设置 ware 库存并登录会员
        requireService("gulimall-order");
        requireService("gulimall-ware");
        requireService("gulimall-member");

        Fixture fixture = createFixture();
        setWareStock(100, 0, 1);
        String session = loginAsTestMember();
        // 2. 发起调用：故意用一个不存在的收货地址提交订单，让异步建单注定失败
        String orderSn = submit(fixture, 999999L, session);

        // 3. 等待结果：最多等 30 秒，直到秒杀单状态变成建单失败（3）
        awaitUntil("建单失败回补", Duration.ofSeconds(30),
                () -> Objects.equals(3, seckillOrderStatus(orderSn)));

        // 4. 断言结果：记录了失败原因，Redis 库存回到 100、限购计数回到 0
        assertTrue(seckillOrderRow(orderSn).get("fail_reason") != null);
        assertEquals(100, redisStock(fixture.sessionId()), "建单失败后库存应回到 100");
        assertEquals(0, redisBought(fixture.sessionId()), "建单失败后限购计数应回退");
    }

    private String submit(Fixture fixture, long addrId, String session) {
        // 1. 准备并组装提交订单需要的请求体字段
        String orderSn;
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", "IT-" + UUID.randomUUID());
        body.put("sessionId", fixture.sessionId());
        body.put("skuId", fixture.skuId());
        body.put("quantity", 1);
        body.put("addrId", addrId);
        body.put("payType", 1);
        body.put("randomCode", fixture.randomCode());

        // 2. 发起调用：提交抢购订单并确认接口成功
        Map<String, Object> response = postJson("/seckill/order/submit", body, session);
        assertOk(response);
        // 3. 取出订单号并登记到清理列表，然后把订单号返回给用例继续轮询
        orderSn = String.valueOf(orderResult(response).get("orderSn"));
        trackOrderSn(orderSn);
        return orderSn;
    }
}
