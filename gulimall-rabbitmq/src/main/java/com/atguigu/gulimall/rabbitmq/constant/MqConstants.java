package com.atguigu.gulimall.rabbitmq.constant;

/**
 * RabbitMQ 共通模块的交换机、队列、路由键、消息头、重试策略。
 *
 * <p>所有名字集中在这里，声明侧和使用侧引用同一份常量，拼错就编译不过。
 * 业务模块新增队列时，在这里补常量，再到
 * {@link com.atguigu.gulimall.rabbitmq.config.RabbitQueueConfig} 里声明。
 */
public final class MqConstants {

    private MqConstants() {
    }

    // ==================== 消息头 ====================

    /** 业务消息 id：发送时塞进消息头，confirm/return 回调和消费端靠它定位同一条消息 */
    public static final String HEADER_MESSAGE_ID = "mq_message_id";

    /** 已重试次数：延迟插件重发是新消息，不能依赖 x-death，只能自己维护 */
    public static final String HEADER_RETRY_COUNT = "mq_retry_count";

    /** 失败原因：重试到头时带进死信队列，归档消费者写进 mq_dead_message */
    public static final String HEADER_FAIL_REASON = "mq_fail_reason";

    /** 原始交换机和路由键：消息进死信队列后 received* 已指向死信交换机，重放必须靠它 */
    public static final String HEADER_ORIGIN_EXCHANGE = "mq_origin_exchange";
    public static final String HEADER_ORIGIN_ROUTING_KEY = "mq_origin_routing_key";

    // ==================== 延时重试交换机 ====================

    /**
     * 延时重试交换机：类型 {@code x-delayed-message}，依赖 broker 安装
     * {@code rabbitmq_delayed_message_exchange} 插件。
     *
     * <p>首次延时投递（比如库存解锁等 30 分钟）和消费失败后的重试都发到它，
     * 消息头 {@code x-delay} 指定延迟毫秒数，到期后按 routingKey 路由到业务队列。
     */
    public static final String RETRY_EXCHANGE = "gulimall-rabbitmq-retry-exchange";

    /** 延迟插件注册的交换机类型 */
    public static final String RETRY_EXCHANGE_TYPE = "x-delayed-message";

    /** 延迟交换机到期后真正使用的路由类型（direct） */
    public static final String RETRY_EXCHANGE_DELAYED_TYPE_ARG = "x-delayed-type";
    public static final String RETRY_EXCHANGE_DELAYED_TYPE_VALUE = "direct";

    /** 延迟毫秒数消息头 */
    public static final String HEADER_DELAY = "x-delay";

    // ==================== 死信归档 ====================

    /**
     * 死信交换机：重试次数用尽的消息、以及 basicNack(requeue=false) 的消息最终到这里。
     * 不能复用业务交换机或重试交换机，否则会形成回环。
     */
    public static final String DEAD_EXCHANGE = "gulimall-rabbitmq-dead-exchange";
    public static final String DEAD_QUEUE = "gulimall-rabbitmq-dead-queue";
    public static final String DEAD_ROUTING_KEY = "gulimall.rabbitmq.dead";

    // ==================== 业务：库存解锁 ====================

    /** 库存业务交换机（direct）：给以后正常的、不延时的库存消息留入口 */
    public static final String STOCK_EXCHANGE = "gulimall-stock-exchange";

    /** 库存解锁队列：延时到期后真正被消费的队列 */
    public static final String STOCK_RELEASE_QUEUE = "gulimall-stock-release-queue";

    /** 库存解锁路由键：业务交换机、重试交换机到该队列都用它 */
    public static final String STOCK_RELEASE_ROUTING_KEY = "stock.release";

    // ==================== 业务：秒杀结束结算 ====================

    /** 秒杀结算业务交换机 */
    public static final String SECKILL_EXCHANGE = "gulimall-seckill-exchange";

    /** 秒杀结束结算队列：宽限期到期后消费者在这里结算 */
    public static final String SECKILL_SETTLE_QUEUE = "gulimall-seckill-settle-queue";

    /** 秒杀结束结算路由键 */
    public static final String SECKILL_SETTLE_ROUTING_KEY = "seckill.settle";

    /** 秒杀场次到点结束业务交换机 */
    public static final String SECKILL_END_EXCHANGE = "gulimall-seckill-end-exchange";

    /** 秒杀场次到点结束队列：到 end_time 后把场次变停止中 */
    public static final String SECKILL_END_QUEUE = "gulimall-seckill-end-queue";

    /** 秒杀场次到点结束路由键 */
    public static final String SECKILL_END_ROUTING_KEY = "seckill.end";

    /** 秒杀场次到点开始业务交换机 */
    public static final String SECKILL_START_EXCHANGE = "gulimall-seckill-start-exchange";

    /** 秒杀场次到点开始队列：到 start_time 后把场次变进行中 */
    public static final String SECKILL_START_QUEUE = "gulimall-seckill-start-queue";

    /** 秒杀场次到点开始路由键 */
    public static final String SECKILL_START_ROUTING_KEY = "seckill.start";

    // ==================== 业务：秒杀订单异步建单 ====================

    /** 秒杀下单业务交换机 */
    public static final String SECKILL_ORDER_EXCHANGE = "gulimall-seckill-order-exchange";

    /** 秒杀订单建单队列：抢购预扣成功后，异步消费建单 */
    public static final String SECKILL_ORDER_CREATE_QUEUE = "gulimall-seckill-order-create-queue";

    /** 秒杀订单建单路由键 */
    public static final String SECKILL_ORDER_CREATE_ROUTING_KEY = "seckill.order.create";

    // ==================== 重试策略 ====================

    /** 消费失败最多重试几次，超过就进死信归档、转人工 */
    public static final int MAX_RETRY = 3;

    /** 第 N 次重试的延迟毫秒数（下标 0 = 第 1 次重试），递增给下游恢复时间 */
    public static final long[] RETRY_DELAY_MILLIS = {10_000L, 30_000L, 60_000L};

    public static long delayFor(int retryCount) {
        if (retryCount < 0) {
            return RETRY_DELAY_MILLIS[0];
        }
        int index = Math.min(retryCount, RETRY_DELAY_MILLIS.length - 1);
        return RETRY_DELAY_MILLIS[index];
    }

}
