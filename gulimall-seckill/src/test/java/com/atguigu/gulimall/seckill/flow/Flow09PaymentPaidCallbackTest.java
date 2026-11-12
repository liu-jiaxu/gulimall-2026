package com.atguigu.gulimall.seckill.flow;

import com.atguigu.gulimall.seckill.constant.SeckillOrderStatus;
import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 流程 09：支付成功回填。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos。
 * <p>需要关闭：本机 gulimall-seckill。
 *
 * <p>支付宝异步通知需要支付宝私钥签名，仓库只有应用私钥和支付宝公钥，
 * 无法构造能通过验签的 notify；本测试覆盖 seckill 收到 PAID 回调后的幂等状态推进。
 */
@Tag("integration")
class Flow09PaymentPaidCallbackTest extends SeckillIntegrationTestSupport {

    /**
     * 测试内容：对 BUILT 状态的秒杀单调用支付成功回调，验证状态推进为 PAID，并确认重复回调仍然成功且状态保持 PAID（幂等）。
     */
    @Test
    void paidCallbackMovesBuiltOrderToPaid() {
        // 1. 准备测试数据：创建场次并插入一条已建单状态的秒杀单
        Fixture fixture = createFixture();
        String orderSn = insertSeckillOrder(fixture, "IT-" + UUID.randomUUID(),
                SeckillOrderStatus.BUILT, 1);

        // 2. 发起调用：调用支付成功回调接口，确认接口返回成功
        Map<String, Object> response = postForm("/internal/seckill/order/paid",
                Map.of("orderSn", orderSn), null);
        assertOk(response);
        // 3. 断言结果：秒杀单状态应从已建单推进为已支付
        assertEquals(SeckillOrderStatus.PAID, seckillOrderStatus(orderSn));

        // 4. 验证副作用：重复发送回调，确认接口仍然成功且状态保持已支付
        // 重复回调保持幂等
        assertOk(postForm("/internal/seckill/order/paid", Map.of("orderSn", orderSn), null));
        assertEquals(SeckillOrderStatus.PAID, seckillOrderStatus(orderSn));
    }

    /**
     * 测试内容：分别对 WAIT_BUILD 和 RELEASED 状态的秒杀单发送支付成功回调，验证非 BUILT 状态的订单被忽略并保持原状态不变。
     */
    @Test
    void paidCallbackIgnoresNonBuiltOrders() {
        // 1. 准备测试数据：创建场次，分别插入排队建单中和已释放状态的秒杀单
        Fixture fixture = createFixture();
        String waiting = insertSeckillOrder(fixture, "IT-" + UUID.randomUUID(),
                SeckillOrderStatus.WAIT_BUILD, 1);
        String released = insertSeckillOrder(fixture, "IT-" + UUID.randomUUID(),
                SeckillOrderStatus.RELEASED, 1);

        // 2. 发起调用：对这两张非已建单状态的订单都发送支付成功回调
        assertOk(postForm("/internal/seckill/order/paid", Map.of("orderSn", waiting), null));
        assertOk(postForm("/internal/seckill/order/paid", Map.of("orderSn", released), null));

        // 3. 断言结果：两张单的状态保持原样，说明非已建单订单被安全忽略
        assertEquals(SeckillOrderStatus.WAIT_BUILD, seckillOrderStatus(waiting));
        assertEquals(SeckillOrderStatus.RELEASED, seckillOrderStatus(released));
    }

    /**
     * 测试内容：使用空的 orderSn 调用支付成功回调，验证接口仍返回成功且不抛异常，空单号回调被安全忽略。
     */
    @Test
    void blankOrderSnIsIgnored() {
        // 1. 发起调用：用空 orderSn 调用支付成功回调，确认接口仍返回成功、不会抛异常
        assertOk(postForm("/internal/seckill/order/paid", Map.of("orderSn", ""), null));
    }
}
