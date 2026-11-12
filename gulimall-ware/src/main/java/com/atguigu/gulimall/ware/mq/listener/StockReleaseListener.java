package com.atguigu.gulimall.ware.mq.listener;

import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.atguigu.gulimall.rabbitmq.service.MqRetryService;
import com.atguigu.gulimall.ware.mq.message.StockReleaseMessage;
import com.atguigu.gulimall.ware.service.StockReleaseExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 库存解锁延时消息消费者。
 *
 * <p>到期时的判断完全看工作单状态：
 * <ul>
 *   <li>工作单仍是「已锁定」→ 订单没支付 / 订单根本没建成功 → 解锁库存</li>
 *   <li>工作单已是「已扣减」→ 支付成功，什么都不做</li>
 *   <li>工作单已是「已解锁」→ 别的线程处理过了，直接返回</li>
 * </ul>
 *
 * <p>消费成功才 basicAck；失败交给共通模块的 {@link MqRetryService}：
 * 它会把消息延迟 10s/30s/60s 重试，3 次仍失败就投死信队列并归档。
 * 所以 <b>本类不要自己 nack/nack</b>，否则会和重试链路抢着处理同一条消息。
 */
@Slf4j
@Component
public class StockReleaseListener {

    @Autowired
    private StockReleaseExecutor stockReleaseExecutor;

    @Autowired
    private MqRetryService mqRetryService;

    @Autowired
    private ObjectMapper objectMapper;

    @RabbitListener(queues = MqConstants.STOCK_RELEASE_QUEUE)
    public void onStockRelease(Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            StockReleaseMessage payload = objectMapper.readValue(
                    message.getBody(), StockReleaseMessage.class);

            log.info("[stock-release-listener] 收到库存解锁延时消息：{}", payload);
            stockReleaseExecutor.release(payload.getTaskId());

            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 统一交给重试链路：重发回延时交换机 / 重试用尽进死信归档
            log.error("[stock-release-listener] 库存解锁失败，进入重试链路", e);
            mqRetryService.handleFailure(channel, message, e);
        }
    }

}
