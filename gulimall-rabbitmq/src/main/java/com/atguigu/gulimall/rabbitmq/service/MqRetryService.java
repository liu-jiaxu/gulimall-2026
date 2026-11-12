package com.atguigu.gulimall.rabbitmq.service;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;

/**
 * 消费端失败重试的统一收口。
 *
 * <p>业务监听器的 catch 块只需要调
 * {@link #handleFailure(Channel, Message, Throwable)}，不要自己 ack/nack：
 * <ul>
 *   <li>重试次数 &lt; MAX_RETRY → 发到延时交换机，延迟后回到原队列</li>
 *   <li>重试次数用尽 → 投死信交换机，归档消费者写 mq_dead_message</li>
 * </ul>
 *
 * <p>方法内部会自行签收/拒绝当前消息。
 */
public interface MqRetryService {

    /**
     * 消费失败时的统一处理。
     *
     * @param channel 当前投递通道（内部要拿 deliveryTag 签收）
     * @param message 原始消息
     * @param cause   失败原因（异常会被记日志/归档）
     */
    void handleFailure(Channel channel, Message message, Throwable cause);

}
