package com.atguigu.gulimall.rabbitmq.component;

import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.atguigu.gulimall.rabbitmq.service.MqDeadMessageService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 死信归档消费者：重试链路的最后一站。
 *
 * <p>它不关心消息是什么业务，只做"原样存库"这一件事，所以放在共通模块。
 * 业务模块以后可以监听同一个死信队列做额外告警，但归档这件事框架已经包了。
 *
 * <p>归档成功才 ack；归档失败（比如数据库挂了）不 ack，消息留在队列里等下次重投。
 * 绝不先 ack 再落库 —— 落库那一刻宕机，消息就彻底没了。
 */
@Slf4j
@Component
public class MqDeadMessageListener {

    @Autowired
    private MqDeadMessageService mqDeadMessageService;

    @RabbitListener(queues = MqConstants.DEAD_QUEUE)
    public void onDeadMessage(Message message, Channel channel) {
        MessageProperties props = message.getMessageProperties();
        long deliveryTag = props.getDeliveryTag();
        Object messageId = props.getHeaders().get(MqConstants.HEADER_MESSAGE_ID);

        try {
            int retryCount = readRetryCount(props);
            String reason = readHeaderAsString(props, MqConstants.HEADER_FAIL_REASON);

            mqDeadMessageService.archive(message, reason, retryCount);
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            // 不签收，让消息留在死信队列下次重投。不丢、不循环，但会卡住；
            // 管理台上死信队列堆积量上升就是告警信号
            log.error("【MQ归档】死信归档失败，不签收，消息会留在队列里等下次处理。messageId={} deliveryTag={}",
                    messageId, deliveryTag, e);
        }
    }

    private int readRetryCount(MessageProperties props) {
        String value = readHeaderAsString(props, MqConstants.HEADER_RETRY_COUNT);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("归档时重试次数头格式不对，按0处理：{}", value);
            return 0;
        }
    }

    private String readHeaderAsString(MessageProperties props, String headerName) {
        Object value = props.getHeaders().get(headerName);
        return value == null ? null : value.toString();
    }

}
