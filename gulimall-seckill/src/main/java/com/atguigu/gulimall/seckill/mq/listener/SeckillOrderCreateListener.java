package com.atguigu.gulimall.seckill.mq.listener;

import com.atguigu.gulimall.api.order.vo.SeckillOrderCreateVo;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.atguigu.gulimall.rabbitmq.service.MqRetryService;
import com.atguigu.gulimall.seckill.constant.SeckillOrderStatus;
import com.atguigu.gulimall.seckill.dao.SeckillOrderDao;
import com.atguigu.gulimall.seckill.entity.SeckillOrderEntity;
import com.atguigu.gulimall.seckill.feign.OrderFeignClient;
import com.atguigu.gulimall.seckill.mq.message.SeckillOrderCreateMessage;
import com.atguigu.gulimall.seckill.service.SeckillOrderService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 秒杀订单异步建单消费者
 */
@Slf4j
@Component
public class SeckillOrderCreateListener {

    @Autowired
    private SeckillOrderService seckillOrderService;

    @Autowired
    private SeckillOrderDao seckillOrderDao;

    @Autowired
    private OrderFeignClient orderFeignClient;

    @Autowired
    private MqRetryService mqRetryService;

    @Autowired
    private ObjectMapper objectMapper;

    @RabbitListener(queues = MqConstants.SECKILL_ORDER_CREATE_QUEUE)
    public void onCreate(Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            // 1. 解析消息
            SeckillOrderCreateMessage payload = objectMapper.readValue(
                    message.getBody(), SeckillOrderCreateMessage.class);

            // 2. 预扣记录必须存在且仍是待建单；重复投递直接签收
            SeckillOrderEntity order = seckillOrderDao.selectOne(
                    new QueryWrapper<SeckillOrderEntity>()
                            .eq("order_sn", payload.getOrderSn())
                            .last("LIMIT 1"));
            if (order == null) {
                // 记录被人工删除时继续重试也没有意义，签收并告警
                log.error("[seckill-order-create] 预扣记录不存在，丢弃消息 orderSn={}", payload.getOrderSn());
                channel.basicAck(deliveryTag, false);
                return;
            }
            if (!Integer.valueOf(SeckillOrderStatus.WAIT_BUILD).equals(order.getStatus())) {
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 3. 调 order 建单
            SeckillOrderCreateVo createVo = objectMapper.convertValue(payload, SeckillOrderCreateVo.class);
            R result = orderFeignClient.createSeckillOrder(createVo);

            // 4. 成功：推进到已建单
            if (result != null && Integer.valueOf(0).equals(result.getCode())) {
                seckillOrderService.onBuildSuccess(payload.getOrderSn(),
                        toLong(result.get("orderId")), toBigDecimal(result.get("payAmount")));
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 5. 返回失败时再查一次订单：可能订单已经建好，只是响应超时
            if (orderExists(payload.getOrderSn())) {
                seckillOrderService.onBuildSuccess(payload.getOrderSn(), null, null);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 6. 明确业务失败：回补 Redis，标记建单失败
            String reason = result == null ? "建单返回空" : String.valueOf(result.get("msg"));
            log.error("[seckill-order-create] 建单失败 orderSn={} reason={}", payload.getOrderSn(), reason);
            seckillOrderService.failAndRelease(payload.getOrderSn(), reason);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 7. 网络/超时/未知异常交给共通重试链路
            log.error("[seckill-order-create] 建单消息处理异常，进入重试", e);
            mqRetryService.handleFailure(channel, message, e);
        }
    }

    /**
     * 订单是否已经存在（响应超时、重复消息时用来避免误回补）
     */
    private boolean orderExists(String orderSn) {
        try {
            // 1. 查订单服务，返回成功且带状态就说明订单其实已经建好了，避免超时被误当失败回补
            R result = orderFeignClient.getSeckillOrderStatus(orderSn);
            return result != null
                    && Integer.valueOf(0).equals(result.getCode())
                    && result.get("status") != null;
        } catch (Exception e) {
            // 2. 查询异常时按订单不存在处理，交给上层重试或补偿流程继续兜底
            log.warn("[seckill-order-create] 查询订单状态失败 orderSn={}", orderSn, e);
            return false;
        }
    }

    private Long toLong(Object value) {
        // 1. 空值直接返回 null，避免转换时抛空指针
        if (value == null) {
            return null;
        }
        // 2. 有值时按字符串转成 Long
        return Long.valueOf(String.valueOf(value));
    }

    private BigDecimal toBigDecimal(Object value) {
        // 1. 空值直接返回 null，避免转换时抛空指针
        if (value == null) {
            return null;
        }
        // 2. 有值时按字符串转成 BigDecimal，避免浮点精度丢失
        return new BigDecimal(String.valueOf(value));
    }
}
