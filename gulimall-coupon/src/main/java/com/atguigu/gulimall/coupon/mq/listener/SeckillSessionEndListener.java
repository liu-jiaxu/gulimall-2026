package com.atguigu.gulimall.coupon.mq.listener;

import com.atguigu.gulimall.coupon.mq.message.SeckillSessionEndMessage;
import com.atguigu.gulimall.coupon.service.SeckillSettleService;
import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.atguigu.gulimall.rabbitmq.service.MqRetryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 秒杀场次到点结束消息消费者：把场次改成停止中
 */
@Slf4j
@Component
public class SeckillSessionEndListener {

    @Autowired
    private SeckillSettleService seckillSettleService;

    @Autowired
    private MqRetryService mqRetryService;

    @Autowired
    private ObjectMapper objectMapper;

    @RabbitListener(queues = MqConstants.SECKILL_END_QUEUE)
    public void onEnd(Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            // 1. 解析消息，拿到场次 id 和发送时的结束时间
            SeckillSessionEndMessage payload = objectMapper.readValue(
                    message.getBody(), SeckillSessionEndMessage.class);

            // 2. 到点处理：场次变停止中，并投递 30 分钟后的结算消息
            seckillSettleService.markEnding(payload.getSessionId(), payload.getEndTimeMillis());

            // 3. 成功 ack
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 4. 失败交给共通模块延迟重试
            log.error("[seckill-end-listener] 场次到点处理失败，进入重试链路", e);
            mqRetryService.handleFailure(channel, message, e);
        }
    }
}
