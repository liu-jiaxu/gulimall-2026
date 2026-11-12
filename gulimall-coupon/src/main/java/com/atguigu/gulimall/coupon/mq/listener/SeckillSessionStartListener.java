package com.atguigu.gulimall.coupon.mq.listener;

import com.atguigu.gulimall.coupon.mq.message.SeckillSessionStartMessage;
import com.atguigu.gulimall.coupon.service.SeckillSessionService;
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
 * 秒杀场次到点开始消息消费者：把场次改成进行中
 */
@Slf4j
@Component
public class SeckillSessionStartListener {

    @Autowired
    private SeckillSessionService seckillSessionService;

    @Autowired
    private MqRetryService mqRetryService;

    @Autowired
    private ObjectMapper objectMapper;

    @RabbitListener(queues = MqConstants.SECKILL_START_QUEUE)
    public void onStart(Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            // 1. 解析场次 id 和发送时的开始时间
            SeckillSessionStartMessage payload = objectMapper.readValue(
                    message.getBody(), SeckillSessionStartMessage.class);

            // 2. 到点处理：场次状态改成进行中
            seckillSessionService.markRunning(payload.getSessionId(), payload.getStartTimeMillis());

            // 3. 成功签收
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 4. 失败交给共通模块延迟重试
            log.error("[seckill-start-listener] 场次到点开始处理失败，进入重试链路", e);
            mqRetryService.handleFailure(channel, message, e);
        }
    }
}
