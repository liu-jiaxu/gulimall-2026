package com.atguigu.gulimall.seckill.flow;

import com.atguigu.gulimall.common.constant.SeckillConstant;
import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 流程 13：场次到点结束与结算。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos；gulimall-coupon、gulimall-ware。
 * <p>需要关闭：本机 gulimall-seckill。
 * 结束消息由测试直接投递；结算消息也直接投递，并把 stop_time 改到宽限期之外。
 */
@Tag("integration")
class Flow13SessionEndSettleTest extends SeckillIntegrationTestSupport {

    /**
     * 测试内容：投递与场次结束时间一致的结束消息，验证场次进入停止中（seckill_state 为 2）、settle_type 置为 1，并且秒杀库存 key 被删除。
     */
    @Test
    void endMessageMovesSessionToStoppingAndDeletesStockKey() throws Exception {
        // 1. 确认 coupon 服务可用，创建一个已到结束时间且处于进行中的场次，并准备 ware 的秒杀库存数据
        requireService("gulimall-coupon");
        Fixture fixture = createFixture(
                new Date(System.currentTimeMillis() - 2 * 60 * 60 * 1000L),
                new Date(System.currentTimeMillis() - 60 * 60 * 1000L),
                1, 10, 2);
        setWareStock(100, 0, 5);

        // 2. 投递结束时间与场次真实结束时间一致的结束消息，触发 coupon 的结束处理监听器
        sendEnd(fixture.sessionId(), fixture.end().getTime());

        // 3. 最多等 15 秒，轮询数据库直到场次进入停止中状态 2
        awaitUntil("场次进入停止中", Duration.ofSeconds(15),
                () -> Objects.equals(2, sessionState(fixture.sessionId())));
        // 4. 验证停止中已经删除秒杀库存 key，并且 settle_type 被标记为 1，后续结算才有依据
        assertEquals(Boolean.FALSE, redis.hasKey(
                SeckillConstant.stockKey(fixture.sessionId(), fixture.skuId())),
                "停止中应删除秒杀库存 key");
        assertEquals(1, ((Number) sessionRow(fixture.sessionId()).get("settle_type")).intValue());
    }

    /**
     * 测试内容：在场次处于停止中且已过结算宽限期时投递结算消息，验证场次进入已结束（seckill_state 为 3）、ware 剩余秒杀锁定清零、库存 key 删除，且重复结算消息不会改变状态。
     */
    @Test
    void settleMessageReleasesRemainingStockAndEndsSession() throws Exception {
        // 1. 确认 coupon 和 ware 都可用，创建一个已结束的场次，并准备 ware 的秒杀锁定库存
        requireService("gulimall-coupon");
        requireService("gulimall-ware");
        Fixture fixture = createFixture(
                new Date(System.currentTimeMillis() - 3 * 60 * 60 * 1000L),
                new Date(System.currentTimeMillis() - 2 * 60 * 60 * 1000L),
                1, 10, 2);
        setWareStock(100, 0, 5);
        // 2. 手动把场次改成停止中且已超过 30 分钟结算宽限期，让结算逻辑不会因宽限期而跳过
        smsJdbc.update("UPDATE sms_seckill_session SET seckill_state = 2, settle_type = 1, "
                + "stop_time = DATE_SUB(NOW(), INTERVAL 31 MINUTE) WHERE id = ?",
                fixture.sessionId());

        // 3. 投递结算消息，触发 ware 释放剩余秒杀锁定并把场次推进到已结束
        sendSettle(fixture.sessionId());

        // 4. 最多等 20 秒，轮询数据库直到场次状态变成 3
        awaitUntil("场次结算完成", Duration.ofSeconds(20),
                () -> Objects.equals(3, sessionState(fixture.sessionId())));
        // 5. 检查 ware 剩余秒杀锁定已清零，并且秒杀库存 key 已被删除，说明资源和缓存都清理干净
        Map<String, Object> ware = wareSkuRow();
        assertNotNull(ware);
        assertEquals(0, ((Number) ware.get("seckill_stock_locked")).intValue(),
                "结算应释放剩余秒杀锁定");
        assertEquals(Boolean.FALSE, redis.hasKey(
                SeckillConstant.stockKey(fixture.sessionId(), fixture.skuId())));

        // 重复结算消息应直接返回
        // 6. 再投一次相同的结算消息，验证幂等处理不会把已结束的场次改回其它状态
        sendSettle(fixture.sessionId());
        Thread.sleep(1000);
        assertEquals(3, sessionState(fixture.sessionId()));
    }

    private void sendEnd(long sessionId, long endTimeMillis) {
        // 1. 把场次 id 和结束时间包装成消息，投递到秒杀结束交换机，交给 coupon 消费
        rabbitTemplate.convertAndSend(MqConstants.SECKILL_END_EXCHANGE,
                MqConstants.SECKILL_END_ROUTING_KEY,
                Map.of("sessionId", sessionId, "endTimeMillis", endTimeMillis));
    }

    private void sendSettle(long sessionId) {
        // 1. 只把场次 id 投递到结算路由，触发消费端执行场次结算
        rabbitTemplate.convertAndSend(MqConstants.SECKILL_EXCHANGE,
                MqConstants.SECKILL_SETTLE_ROUTING_KEY,
                Map.of("sessionId", sessionId));
    }

    private Integer sessionState(long sessionId) {
        // 1. 查询场次状态字段，供轮询等待和最终断言使用
        return smsJdbc.queryForObject(
                "SELECT seckill_state FROM sms_seckill_session WHERE id = ?",
                Integer.class, sessionId);
    }

    private Map<String, Object> sessionRow(long sessionId) {
        // 1. 查询整行场次记录，便于断言 settle_type 等单个状态字段
        return smsJdbc.queryForMap("SELECT * FROM sms_seckill_session WHERE id = ?", sessionId);
    }
}
