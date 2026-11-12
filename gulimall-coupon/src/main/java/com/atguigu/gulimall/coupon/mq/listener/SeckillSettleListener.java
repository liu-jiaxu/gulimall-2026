package com.atguigu.gulimall.coupon.mq.listener;

import com.atguigu.gulimall.coupon.mq.message.SeckillSettleMessage;
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
 * 秒杀结束结算延时消息消费者
 */
@Slf4j
@Component
public class SeckillSettleListener {

    @Autowired
    private SeckillSettleService seckillSettleService;

    @Autowired
    private MqRetryService mqRetryService;

    @Autowired
    private ObjectMapper objectMapper;

    @RabbitListener(queues = MqConstants.SECKILL_SETTLE_QUEUE)
    public void onSettle(Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            // 1. 解析消息，拿到场次 id
            SeckillSettleMessage payload = objectMapper.readValue(
                    message.getBody(), SeckillSettleMessage.class);

            // 2. 结算：内部有场次锁和幂等 operationId，重复投递不会重复扣
            seckillSettleService.settleSession(payload.getSessionId());

            // 3. 成功 ack
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 4. 失败交给共通模块延迟重试，重试用尽进死信归档
            log.error("[seckill-settle-listener] 秒杀结算失败，进入重试链路", e);
            mqRetryService.handleFailure(channel, message, e);
        }
    }
}
