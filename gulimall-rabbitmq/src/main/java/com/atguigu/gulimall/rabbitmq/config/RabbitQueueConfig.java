package com.atguigu.gulimall.rabbitmq.config;

import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 交换机 / 队列 / 绑定声明：整个项目的 RabbitMQ 基础设施都放在共通模块里统一创建。
 *
 * <p>应用启动、RabbitAdmin 建立连接时会自动 declare（声明是幂等的）。
 * 后续新增业务队列：在 {@link MqConstants} 补名字，在本类补 Queue + Binding。
 *
 * <p>每个业务队列都要绑两次：
 * <ul>
 *   <li>绑业务交换机 —— 正常消息入口</li>
 *   <li>绑延迟重试交换机 —— 首次延时消息、消费失败重试后能回到队列。
 *       少了这条，重试消息在交换机上匹配不到队列，会被静默丢弃</li>
 * </ul>
 */
@Configuration
public class RabbitQueueConfig {

    // ==================== 延时重试交换机 ====================

    /**
     * 延时交换机：消息进来时不会立刻投递，等 {@code x-delay} 指定的毫秒数过去后才路由。
     * {@code x-delayed-type=direct} 表示到期后按 direct 规则走。
     */
    @Bean
    public CustomExchange retryExchange() {
        Map<String, Object> args = new HashMap<>();
        args.put(MqConstants.RETRY_EXCHANGE_DELAYED_TYPE_ARG,
                MqConstants.RETRY_EXCHANGE_DELAYED_TYPE_VALUE);
        return new CustomExchange(MqConstants.RETRY_EXCHANGE,
                MqConstants.RETRY_EXCHANGE_TYPE, true, false, args);
    }

    // ==================== 死信交换机 / 队列 ====================

    @Bean
    public DirectExchange deadExchange() {
        return new DirectExchange(MqConstants.DEAD_EXCHANGE, true, false);
    }

    /**
     * 归档队列：重试到头的消息最终躺在这里，由共通模块的
     * {@code MqDeadMessageListener} 写进 mq_dead_message 表。
     * 它自己不再配死信 —— 死信的尽头应该是人，而不是另一个队列。
     */
    @Bean
    public Queue deadQueue() {
        return QueueBuilder.durable(MqConstants.DEAD_QUEUE).build();
    }

    @Bean
    public Binding deadBinding() {
        return BindingBuilder.bind(deadQueue())
                .to(deadExchange())
                .with(MqConstants.DEAD_ROUTING_KEY);
    }

    // ==================== 业务：库存解锁 ====================

    /** 库存业务交换机（direct） */
    @Bean
    public DirectExchange stockExchange() {
        return new DirectExchange(MqConstants.STOCK_EXCHANGE, true, false);
    }

    /**
     * 库存解锁队列。
     *
     * <p>配了死信兜底：消费端 basicNack(requeue=false) 时，消息会经死信交换机进归档队列，
     * 不至于凭空消失。正常失败重试走的是 MqRetryService 主动投递延迟交换机。
     */
    @Bean
    public Queue stockReleaseQueue() {
        return QueueBuilder.durable(MqConstants.STOCK_RELEASE_QUEUE)
                .deadLetterExchange(MqConstants.DEAD_EXCHANGE)
                .deadLetterRoutingKey(MqConstants.DEAD_ROUTING_KEY)
                .build();
    }

    /** 正常入口（以后有不延时的库存消息走这里） */
    @Bean
    public Binding stockReleaseBinding() {
        return BindingBuilder.bind(stockReleaseQueue())
                .to(stockExchange())
                .with(MqConstants.STOCK_RELEASE_ROUTING_KEY);
    }

    /**
     * 延时/重试回路：库存解锁消息首次就发到重试交换机，带 30 分钟 x-delay；
     * 消费失败后的 10s/30s/60s 重试也回到这里。
     */
    @Bean
    public Binding stockReleaseRetryBinding(CustomExchange retryExchange) {
        return BindingBuilder.bind(stockReleaseQueue())
                .to(retryExchange)
                .with(MqConstants.STOCK_RELEASE_ROUTING_KEY)
                .noargs();
    }

    // ==================== 业务：秒杀结束结算 ====================

    /** 秒杀结算业务交换机 */
    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(MqConstants.SECKILL_EXCHANGE, true, false);
    }

    /** 秒杀结算队列，配死信兜底 */
    @Bean
    public Queue seckillSettleQueue() {
        return QueueBuilder.durable(MqConstants.SECKILL_SETTLE_QUEUE)
                .deadLetterExchange(MqConstants.DEAD_EXCHANGE)
                .deadLetterRoutingKey(MqConstants.DEAD_ROUTING_KEY)
                .build();
    }

    /** 正常入口（以后不延时的秒杀消息走这里） */
    @Bean
    public Binding seckillSettleBinding() {
        return BindingBuilder.bind(seckillSettleQueue())
                .to(seckillExchange())
                .with(MqConstants.SECKILL_SETTLE_ROUTING_KEY);
    }

    /** 延时/重试回路：秒杀结算消息首次发到重试交换机，带 30 分钟 x-delay */
    @Bean
    public Binding seckillSettleRetryBinding(CustomExchange retryExchange) {
        return BindingBuilder.bind(seckillSettleQueue())
                .to(retryExchange)
                .with(MqConstants.SECKILL_SETTLE_ROUTING_KEY)
                .noargs();
    }

    // ==================== 业务：秒杀场次到点结束 ====================

    /** 秒杀场次结束业务交换机 */
    @Bean
    public DirectExchange seckillEndExchange() {
        return new DirectExchange(MqConstants.SECKILL_END_EXCHANGE, true, false);
    }

    /** 秒杀场次结束队列，配死信兜底 */
    @Bean
    public Queue seckillEndQueue() {
        return QueueBuilder.durable(MqConstants.SECKILL_END_QUEUE)
                .deadLetterExchange(MqConstants.DEAD_EXCHANGE)
                .deadLetterRoutingKey(MqConstants.DEAD_ROUTING_KEY)
                .build();
    }

    /** 正常入口（以后不延时的场次结束消息走这里） */
    @Bean
    public Binding seckillEndBinding() {
        return BindingBuilder.bind(seckillEndQueue())
                .to(seckillEndExchange())
                .with(MqConstants.SECKILL_END_ROUTING_KEY);
    }

    /** 延时/重试回路：场次结束消息首次发到重试交换机，带 end_time - now 的 x-delay */
    @Bean
    public Binding seckillEndRetryBinding(CustomExchange retryExchange) {
        return BindingBuilder.bind(seckillEndQueue())
                .to(retryExchange)
                .with(MqConstants.SECKILL_END_ROUTING_KEY)
                .noargs();
    }

    // ==================== 业务：秒杀场次到点开始 ====================

    /** 秒杀场次开始业务交换机 */
    @Bean
    public DirectExchange seckillStartExchange() {
        return new DirectExchange(MqConstants.SECKILL_START_EXCHANGE, true, false);
    }

    /** 秒杀场次开始队列，配死信兜底 */
    @Bean
    public Queue seckillStartQueue() {
        return QueueBuilder.durable(MqConstants.SECKILL_START_QUEUE)
                .deadLetterExchange(MqConstants.DEAD_EXCHANGE)
                .deadLetterRoutingKey(MqConstants.DEAD_ROUTING_KEY)
                .build();
    }

    /** 正常入口 */
    @Bean
    public Binding seckillStartBinding() {
        return BindingBuilder.bind(seckillStartQueue())
                .to(seckillStartExchange())
                .with(MqConstants.SECKILL_START_ROUTING_KEY);
    }

    /** 延时/重试回路：场次开始消息发到重试交换机，带 start_time - now 的 x-delay */
    @Bean
    public Binding seckillStartRetryBinding(CustomExchange retryExchange) {
        return BindingBuilder.bind(seckillStartQueue())
                .to(retryExchange)
                .with(MqConstants.SECKILL_START_ROUTING_KEY)
                .noargs();
    }

    // ==================== 业务：秒杀订单异步建单 ====================

    /** 秒杀下单业务交换机 */
    @Bean
    public DirectExchange seckillOrderExchange() {
        return new DirectExchange(MqConstants.SECKILL_ORDER_EXCHANGE, true, false);
    }

    /** 秒杀订单建单队列，配死信兜底 */
    @Bean
    public Queue seckillOrderCreateQueue() {
        return QueueBuilder.durable(MqConstants.SECKILL_ORDER_CREATE_QUEUE)
                .deadLetterExchange(MqConstants.DEAD_EXCHANGE)
                .deadLetterRoutingKey(MqConstants.DEAD_ROUTING_KEY)
                .build();
    }

    /** 正常入口 */
    @Bean
    public Binding seckillOrderCreateBinding() {
        return BindingBuilder.bind(seckillOrderCreateQueue())
                .to(seckillOrderExchange())
                .with(MqConstants.SECKILL_ORDER_CREATE_ROUTING_KEY);
    }

    /** 重试回路：消费失败后回到同一个队列 */
    @Bean
    public Binding seckillOrderCreateRetryBinding(CustomExchange retryExchange) {
        return BindingBuilder.bind(seckillOrderCreateQueue())
                .to(retryExchange)
                .with(MqConstants.SECKILL_ORDER_CREATE_ROUTING_KEY)
                .noargs();
    }

}
