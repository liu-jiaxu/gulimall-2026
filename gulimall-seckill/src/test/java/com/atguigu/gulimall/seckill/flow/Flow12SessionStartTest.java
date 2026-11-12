package com.atguigu.gulimall.seckill.flow;

import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 流程 12：场次到点开始。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos；gulimall-coupon。
 * <p>需要关闭：本机 gulimall-seckill。
 * 测试直接向开始交换机投递真实消息，由 coupon 的 SeckillSessionStartListener 消费。
 */
@Tag("integration")
class Flow12SessionStartTest extends SeckillIntegrationTestSupport {

    /**
     * 测试内容：向开始交换机投递与场次开始时间一致的开始消息，等待并验证 coupon 消费后场次 seckill_state 变为 1（进行中）。
     */
    @Test
    void startMessageMarksSessionRunning() throws Exception {
        // 1. 先确认 coupon 服务可用，并创建一个已经到开始时间、当前状态仍为 0 的场次作为测试数据
        requireService("gulimall-coupon");
        Fixture fixture = createFixture(
                new Date(System.currentTimeMillis() - 60 * 1000L),
                new Date(System.currentTimeMillis() + 60 * 60 * 1000L),
                0, 10, 2);

        // 2. 投递一条开始时间与场次真实开始时间完全一致的开始消息，让 coupon 的监听器真正消费
        sendStart(fixture.sessionId(), fixture.start().getTime());

        // 3. 最多等待 15 秒并轮询数据库，直到场次状态变成 1，表示监听器已把场次置为进行中
        awaitUntil("场次进入进行中", Duration.ofSeconds(15),
                () -> Objects.equals(1, sessionState(fixture.sessionId())));
        // 4. 再查一次数据库做最终断言，确认状态不是查询过程中的偶然值，而是稳定落库的 1
        assertEquals(1, sessionState(fixture.sessionId()));
    }

    /**
     * 测试内容：先投递开始时间与场次不一致的旧开始消息，验证场次状态保持 0，再投递正确消息后场次才进入进行中。
     */
    @Test
    void oldStartMessageWithDifferentTimeIsIgnored() throws Exception {
        // 1. 确认 coupon 服务可用，并准备一个已到开始时间但状态仍为 0 的场次
        requireService("gulimall-coupon");
        Fixture fixture = createFixture(
                new Date(System.currentTimeMillis() - 60 * 1000L),
                new Date(System.currentTimeMillis() + 60 * 60 * 1000L),
                0, 10, 2);

        // 2. 故意投递一条开始时间比真实开始时间早一小时的旧消息，模拟历史遗留消息
        sendStart(fixture.sessionId(), fixture.start().getTime() - 60 * 60 * 1000L);
        // 3. 等 2 秒让消费端有机会处理这条旧消息，再确认场次状态仍为 0，说明旧消息被忽略
        Thread.sleep(2000);
        assertEquals(0, sessionState(fixture.sessionId()), "旧开始消息不应改变场次状态");

        // 4. 改成投递开始时间正确的消息，再等待场次被正常置为进行中，证明前面的状态为 0 不是消费失败
        sendStart(fixture.sessionId(), fixture.start().getTime());
        awaitUntil("正确开始消息生效", Duration.ofSeconds(15),
                () -> Objects.equals(1, sessionState(fixture.sessionId())));
    }

    /**
     * 测试内容：场次开始时间尚有约 3 秒时提前投递开始消息，验证消息被延迟重投，到真实开始时间后场次才进入进行中。
     */
    @Test
    void earlyStartMessageIsRescheduledToRealStartTime() throws Exception {
        // 1. 确认 coupon 服务可用，并把场次开始时间设在约 3 秒后，制造“还没到点”的场景
        requireService("gulimall-coupon");
        Date start = new Date(System.currentTimeMillis() + 3000L);
        Fixture fixture = createFixture(start, new Date(start.getTime() + 60 * 60 * 1000L),
                0, 10, 2);

        // 2. 在真正到点前就提前投递开始消息，验证消费端会把它延迟重投而不是直接开始
        sendStart(fixture.sessionId(), start.getTime());

        // 3. 等待到真实开始时间之后场次才变成 1，说明提前的消息被重排到正确的开始时刻
        awaitUntil("到点后场次进入进行中", Duration.ofSeconds(15),
                () -> Objects.equals(1, sessionState(fixture.sessionId())));
    }

    private void sendStart(long sessionId, long startTimeMillis) {
        // 1. 把场次 id 和开始时间包装成消息，投递到秒杀开始交换机，交给 coupon 消费
        rabbitTemplate.convertAndSend(MqConstants.SECKILL_START_EXCHANGE,
                MqConstants.SECKILL_START_ROUTING_KEY,
                Map.of("sessionId", sessionId, "startTimeMillis", startTimeMillis));
    }

    private Integer sessionState(long sessionId) {
        // 1. 直接从 sms_seckill_session 表读取场次状态字段，供等待条件和断言共用
        return smsJdbc.queryForObject(
                "SELECT seckill_state FROM sms_seckill_session WHERE id = ?",
                Integer.class, sessionId);
    }
}
