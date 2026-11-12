package com.atguigu.gulimall.seckill.flow;

import com.atguigu.gulimall.common.constant.SeckillConstant;
import com.atguigu.gulimall.seckill.constant.SeckillOrderStatus;
import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import com.atguigu.gulimall.seckill.task.SeckillLimitReconcileTask;
import com.atguigu.gulimall.seckill.task.SeckillOrderReconcileTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 15：秒杀对账任务与兜底补偿。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos；gulimall-order（两个建单补偿方法需要，限购重建方法不需要）。
 * <p>需要关闭：本机 gulimall-seckill。
 * <p>测试直接调用真实任务 Bean，不启动额外调度。
 */
@Tag("integration")
class Flow15ReconcileTaskTest extends SeckillIntegrationTestSupport {

    @Autowired
    private SeckillOrderReconcileTask orderReconcileTask;

    @Autowired
    private SeckillLimitReconcileTask limitReconcileTask;

    @AfterEach
    void purgeQueue() {
        // 1. 每个测试结束后清空建单队列，避免对账任务重发的消息影响下一个用例
        purgeSeckillOrderCreateQueue();
    }

    /**
     * 测试内容：限购 hash 被写成 9 且存在一条已建单订单时，限购对账任务应按订单事实把该用户限购计数重建为 1。
     */
    @Test
    void limitReconcileRebuildsHashFromOrders() {
        // 1. 创建一份测试场次和商品数据，作为后续订单和 Redis 限购的关联对象
        Fixture fixture = createFixture();
        // 2. 故意把 Redis 限购计数写成 9，并插入一条已建单订单，制造“缓存计数与订单事实不一致”的场景
        redis.opsForHash().put(SeckillConstant.userCountKey(fixture.sessionId(), fixture.skuId()),
                String.valueOf(TEST_MEMBER_ID), "9");
        insertSeckillOrder(fixture, "IT-" + UUID.randomUUID(),
                SeckillOrderStatus.BUILT, 1);

        // 3. 直接调用限购对账任务，让它按数据库订单重新计算用户的限购数量
        limitReconcileTask.reconcile();

        // 4. 读取重建后的限购计数，确认被改成订单事实对应的 1，而不是保留错误的 9
        Object value = redis.opsForHash().get(
                SeckillConstant.userCountKey(fixture.sessionId(), fixture.skuId()),
                String.valueOf(TEST_MEMBER_ID));
        assertEquals("1", String.valueOf(value), "限购 hash 应按订单事实重建");
    }

    /**
     * 测试内容：订单创建超过 5 分钟仍处于等待建单状态时，建单对账任务应递增 build_retry_count 并重发建单消息。
     */
    @Test
    void orderReconcileResendsMessageForOldWaitingOrder() {
        // 1. 确认订单服务可用，并创建测试场次和商品数据
        requireService("gulimall-order");
        Fixture fixture = createFixture();
        // 2. 插入一条等待建单的订单，并把创建时间改到 6 分钟前，让它超过 5 分钟的对账阈值
        String orderSn = insertSeckillOrder(fixture, "IT-" + UUID.randomUUID(),
                SeckillOrderStatus.WAIT_BUILD, 1);
        smsJdbc.update("UPDATE sms_seckill_order SET create_time = DATE_SUB(NOW(), INTERVAL 6 MINUTE) "
                + "WHERE order_sn = ?", orderSn);

        // 3. 执行建单对账任务，预期它会发现超时未建单的订单并重新投递建单消息
        orderReconcileTask.reconcile();

        // 4. 查询订单记录，确认 build_retry_count 至少加了 1，说明补偿重试次数已记录
        Map<String, Object> row = seckillOrderRow(orderSn);
        assertTrue(((Number) row.get("build_retry_count")).intValue() >= 1,
                "应递增建单补偿次数");
        // 5. 查询 mq_message，确认确实产生了包含该订单号的重发消息，而不是只改了重试次数
        List<Map<String, Object>> messages = smsJdbc.queryForList(
                "SELECT * FROM mq_message WHERE content LIKE ?", "%" + orderSn + "%");
        assertTrue(messages.size() >= 1, "应重发建单消息");
    }

    /**
     * 测试内容：等待建单订单重试次数已达上限时，对账任务应将订单置为建单失败并回滚 Redis 库存与已购计数。
     */
    @Test
    void orderReconcileFailsAndReleasesAfterRetryLimit() {
        // 1. 确认订单服务可用，并创建测试场次和商品数据
        requireService("gulimall-order");
        Fixture fixture = createFixture();
        // 2. 插入一条等待建单的订单，把创建时间改到 6 分钟前，并把补偿次数直接拉到上限 5
        String orderSn = insertSeckillOrder(fixture, "IT-" + UUID.randomUUID(),
                SeckillOrderStatus.WAIT_BUILD, 1);
        smsJdbc.update("UPDATE sms_seckill_order SET create_time = DATE_SUB(NOW(), INTERVAL 6 MINUTE), "
                + "build_retry_count = 5 WHERE order_sn = ?", orderSn);
        // 3. 准备 Redis 里的库存和已购计数，模拟下单时预扣了 1 件库存、计了 1 次购买
        redis.opsForValue().set(SeckillConstant.stockKey(fixture.sessionId(), fixture.skuId()),
                "99");
        redis.opsForHash().put(SeckillConstant.userCountKey(fixture.sessionId(), fixture.skuId()),
                String.valueOf(TEST_MEMBER_ID), "1");

        // 4. 执行建单对账任务，预期它会判定订单建单失败并回滚预占的库存和购买计数
        orderReconcileTask.reconcile();

        // 5. 分别确认订单状态变为建单失败、Redis 库存回到 100、用户已购计数回到 0
        assertEquals(SeckillOrderStatus.BUILD_FAILED, seckillOrderStatus(orderSn));
        assertEquals(100, redisStock(fixture.sessionId()));
        assertEquals(0, redisBought(fixture.sessionId()));
    }
}
