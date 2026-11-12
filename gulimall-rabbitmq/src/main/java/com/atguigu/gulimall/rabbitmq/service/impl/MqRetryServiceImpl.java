package com.atguigu.gulimall.rabbitmq.service.impl;

import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.atguigu.gulimall.rabbitmq.service.MqRetryService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.UUID;

/**
 * 消费端失败重试：延迟重发回原队列，重试用尽后投死信交换机。
 */
@Slf4j
@Service("mqRetryService")
public class MqRetryServiceImpl implements MqRetryService {

    @Autowired
    @Qualifier("rabbitTemplate")
    private RabbitTemplate rabbitTemplate;

    @Autowired
    @Qualifier("delayedRabbitTemplate")
    private RabbitTemplate delayedRabbitTemplate;

    @Override
    public void handleFailure(Channel channel, Message message, Throwable cause) {
        MessageProperties props = message.getMessageProperties();
        long deliveryTag = props.getDeliveryTag();
        int retryCount = readRetryCount(props);
        String reason = buildReason(cause);
        Object messageId = props.getHeaders().get(MqConstants.HEADER_MESSAGE_ID);

        try {
            if (retryCount < MqConstants.MAX_RETRY) {
                // 还有重试机会：延迟重发回原队列
                int nextRetryCount = retryCount + 1;
                long delayMillis = MqConstants.delayFor(retryCount);
                republishWithDelay(message, nextRetryCount, delayMillis);
                log.warn("【MQ重试】第 {}/{} 次重试，{}ms 后重新投递。messageId={} queue={} 原因={}",
                        nextRetryCount, MqConstants.MAX_RETRY, delayMillis,
                        messageId, props.getConsumerQueue(), reason);
            } else {
                // 重试次数用尽：投到死信交换机 → 死信队列 → 归档消费者写 mq_dead_message
                publishToDeadLetter(message, reason, retryCount);
                log.error("【MQ重试】已重试 {} 次仍失败，转入死信队列等待归档。messageId={} queue={} 原因={}",
                        retryCount, messageId, props.getConsumerQueue(), reason);
            }

            // 责任已经转移（延迟重发已投出 / 已投进死信交换机），可以签收原消息了
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            // 重发/投死信本身失败（Broker 连不上、延迟插件没装）：
            // 不能只"不签收"（会永远挂在 unacked，占满 prefetch），
            // 也不能 basicNack(requeue=true)（毫秒级死循环）。
            // requeue=false 让消息走队列上配的 x-dead-letter-exchange 进死信队列。
            log.error("【MQ重试】重发/投递死信失败，降级为 nack(requeue=false) 交给队列的死信交换机。"
                    + "messageId={} deliveryTag={}", messageId, deliveryTag, e);
            nackToDeadLetter(channel, deliveryTag);
        }
    }

    private void nackToDeadLetter(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException e) {
            // channel 已关闭时 nack 也会失败；连接断开后 Broker 会把消息重新变回 Ready
            log.error("basicNack 也失败了（channel 可能已关闭），deliveryTag={}", deliveryTag, e);
        }
    }

    /**
     * 把原消息原样发到延时交换机，带 x-delay，到期后按原 routingKey 回到原队列。
     *
     * <p>用 {@code rabbitTemplate.send(Message)} 而不是 convertAndSend：
     * 手里已经是完整的二进制消息，再走一遍转换器会破坏 body 和 __TypeId__ 等头部。
     */
    private void republishWithDelay(Message original, int nextRetryCount, long delayMillis) {
        MessageProperties originalProps = original.getMessageProperties();
        String routingKey = originalProps.getReceivedRoutingKey();

        MessageProperties newProps = new MessageProperties();
        originalProps.getHeaders().forEach(newProps::setHeader);
        newProps.setContentType(originalProps.getContentType());
        newProps.setContentEncoding(originalProps.getContentEncoding());
        newProps.setHeader(MqConstants.HEADER_RETRY_COUNT, nextRetryCount);
        newProps.setHeader(MqConstants.HEADER_DELAY, delayMillis);

        // 延时重试同样走 mandatory=false 的模板，避免被延时插件误退回
        Object messageId = newProps.getHeaders().get(MqConstants.HEADER_MESSAGE_ID);
        // 没有 mq_message_id 时给随机 id，CorrelationData 不允许 null
        String correlationId = messageId == null ? UUID.randomUUID().toString() : messageId.toString();
        delayedRabbitTemplate.send(MqConstants.RETRY_EXCHANGE, routingKey,
                new Message(original.getBody(), newProps),
                new CorrelationData(correlationId));
    }

    /**
     * 投递到死信交换机。除了原样搬运，还带上失败原因、原交换机、原 routingKey ——
     * 消息进死信队列后 received* 指向死信交换机，原来的目的地信息已经丢了。
     */
    private void publishToDeadLetter(Message original, String reason, int retryCount) {
        MessageProperties originalProps = original.getMessageProperties();

        MessageProperties newProps = new MessageProperties();
        originalProps.getHeaders().forEach(newProps::setHeader);
        newProps.setContentType(originalProps.getContentType());
        newProps.setContentEncoding(originalProps.getContentEncoding());
        newProps.setHeader(MqConstants.HEADER_RETRY_COUNT, retryCount);
        newProps.setHeader(MqConstants.HEADER_FAIL_REASON, reason);
        newProps.setHeader(MqConstants.HEADER_ORIGIN_EXCHANGE, originalProps.getReceivedExchange());
        newProps.setHeader(MqConstants.HEADER_ORIGIN_ROUTING_KEY, originalProps.getReceivedRoutingKey());

        rabbitTemplate.send(MqConstants.DEAD_EXCHANGE, MqConstants.DEAD_ROUTING_KEY,
                new Message(original.getBody(), newProps));
    }

    private int readRetryCount(MessageProperties props) {
        Object value = props.getHeaders().get(MqConstants.HEADER_RETRY_COUNT);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            log.warn("重试次数头格式不对，按0处理：{}", value);
            return 0;
        }
    }

    private String buildReason(Throwable cause) {
        if (cause == null) {
            return "未知异常";
        }
        String message = cause.getMessage();
        return cause.getClass().getName() + (message == null ? "" : ": " + message);
    }

}
