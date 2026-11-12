package com.atguigu.gulimall.seckill.flow;

import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.atguigu.gulimall.seckill.support.SeckillIntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 流程 19：MQ 重试、死信归档。
 *
 * <p>需要启动：MySQL、Redis、RabbitMQ、Nacos。
 * <p>需要关闭：本机 gulimall-seckill，必须停止，避免抢测试消息。
 * 测试把 10s/30s/60s 的重试间隔临时改成 1s/3s/6s，结束后恢复。
 */
@Tag("integration")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.rabbitmq.listener.simple.auto-startup=true")
class Flow19MqRetryDeadLetterTest extends SeckillIntegrationTestSupport {

    @BeforeEach
    void shortenRetryDelays() {
        // 1. 把原本 10s/30s/60s 的三次重试间隔临时缩短为 1s/3s/6s，让死信归档能在测试超时内完成
        MqConstants.RETRY_DELAY_MILLIS[0] = 1000L;
        MqConstants.RETRY_DELAY_MILLIS[1] = 3000L;
        MqConstants.RETRY_DELAY_MILLIS[2] = 6000L;
        // 2. 先清空建单队列，确保后续观察到的重试和死信都来自本用例发送的消息
        purgeSeckillOrderCreateQueue();
    }

    @AfterEach
    void restoreRetryDelays() {
        // 1. 把重试间隔恢复成生产使用的 10s/30s/60s，避免影响后续测试或同 JVM 的其它用例
        MqConstants.RETRY_DELAY_MILLIS[0] = 10_000L;
        MqConstants.RETRY_DELAY_MILLIS[1] = 30_000L;
        MqConstants.RETRY_DELAY_MILLIS[2] = 60_000L;
    }

    /**
     * 测试内容：发送非法 JSON 建单消息后，消费端应重试 3 次并归档到 mq_dead_message，且 retry_count 与 routing_key 正确。
     */
    @Test
    void malformedMessageRetriesThenArchivesToDeadTable() throws Exception {
        // 1. 向建单交换机投递一段非法 JSON，让消费端解析失败并进入重试流程
        rabbitTemplate.convertAndSend(MqConstants.SECKILL_ORDER_EXCHANGE,
                MqConstants.SECKILL_ORDER_CREATE_ROUTING_KEY, "bad-json");

        // 2. 最多等 30 秒，轮询死信表直到出现这条坏消息，说明三次重试已经耗尽并归档
        awaitUntil("死信归档", Duration.ofSeconds(30),
                () -> !deadMessages().isEmpty());

        // 3. 查询死信记录并断言重试次数为 3、routing_key 仍是原建单路由，保证归档信息准确
        List<Map<String, Object>> dead = deadMessages();
        assertFalse(dead.isEmpty());
        assertEquals(3, ((Number) dead.get(0).get("retry_count")).intValue(),
                "重试用尽后归档的 retry_count 应为 3");
        assertEquals(MqConstants.SECKILL_ORDER_CREATE_ROUTING_KEY,
                dead.get(0).get("routing_key"));
    }

    /**
     * 测试内容：非法消息完成死信归档后应被 ack，建单队列消息数最终为 0，不残留未确认消息。
     */
    @Test
    void malformedMessageDoesNotLeaveUnackedQueue() throws Exception {
        // 1. 向建单交换机投递非法 JSON，复现消费端解析失败后重试并最终归档的流程
        rabbitTemplate.convertAndSend(MqConstants.SECKILL_ORDER_EXCHANGE,
                MqConstants.SECKILL_ORDER_CREATE_ROUTING_KEY, "bad-json");

        // 2. 最多等 30 秒，轮询死信表确认坏消息已经完成归档
        awaitUntil("死信归档", Duration.ofSeconds(30),
                () -> !deadMessages().isEmpty());

        // 消费端最终 ack 原消息，队列不应长期堆积测试消息
        // 3. 读取建单队列的统计信息，准备检查队列中是否还残留未确认或未消费的消息
        java.util.Properties queueInfo = amqpAdmin.getQueueProperties(
                MqConstants.SECKILL_ORDER_CREATE_QUEUE);
        Object count = queueInfo == null ? null : queueInfo.get("QUEUE_MESSAGE_COUNT");
        // 4. 只有 RabbitMQ 返回了消息数时才断言，避免管理接口不返回该字段导致误报
        if (count != null) {
            assertEquals(0, ((Number) count).intValue());
        }
    }

    private List<Map<String, Object>> deadMessages() {
        // 1. 按内容包含 bad-json 查询死信表，按 id 倒序保证取到最新一条归档记录
        return smsJdbc.queryForList(
                "SELECT * FROM mq_dead_message WHERE content LIKE ? ORDER BY id DESC",
                "%bad-json%");
    }
}
