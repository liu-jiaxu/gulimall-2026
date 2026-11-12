package com.atguigu.gulimall.seckill.task;

import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.atguigu.gulimall.rabbitmq.service.MqMessageService;
import com.atguigu.gulimall.seckill.constant.SeckillOrderStatus;
import com.atguigu.gulimall.seckill.dao.SeckillOrderDao;
import com.atguigu.gulimall.seckill.entity.SeckillOrderEntity;
import com.atguigu.gulimall.seckill.feign.OrderFeignClient;
import com.atguigu.gulimall.seckill.mq.message.SeckillOrderCreateMessage;
import com.atguigu.gulimall.seckill.service.SeckillOrderService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 秒杀订单对账：
 * 1. 建单消息丢失/消费失败 → 重发或回补；
 * 2. 订单关闭但 seckill 没收到通知 → 补释放；
 * 3. 已支付订单回填 PAID。
 */
@Slf4j
@Component
public class SeckillOrderReconcileTask {

    private static final int BATCH_SIZE = 100;
    private static final int BUILD_RETRY_LIMIT = 5;
    private static final long BUILD_TIMEOUT_MS = 5L * 60 * 1000;
    private static final long RELEASE_AFTER_MS = 5L * 60 * 1000;

    @Autowired
    private SeckillOrderDao seckillOrderDao;

    @Autowired
    private SeckillOrderService seckillOrderService;

    @Autowired
    private OrderFeignClient orderFeignClient;

    @Autowired
    private MqMessageService mqMessageService;

    /** 支付超时，和订单服务保持一致 */
    @Value("${gulimall.seckill.order.pay-timeout-ms:1800000}")
    private long payTimeoutMs;

    /** 每分钟错开整点跑一次 */
    @Scheduled(cron = "15 * * * * ?")
    public void reconcile() {
        // 1. 先对账卡在待建单的订单：订单已建好就回填，没建好就重发消息或回补库存
        reconcileWaitingBuild();
        // 2. 再对账已建单但拖过支付超时的订单：订单已关闭的补释放，已支付的回填 PAID
        reconcileClosedOrders();
    }

    /**
     * 待建单超过 5 分钟：查 order，存在就回填；不存在重发消息，超过上限回补库存。
     */
    private void reconcileWaitingBuild() {
        Date deadline = new Date(System.currentTimeMillis() - BUILD_TIMEOUT_MS);
        List<SeckillOrderEntity> orders = seckillOrderDao.selectList(
                new QueryWrapper<SeckillOrderEntity>()
                        .eq("status", SeckillOrderStatus.WAIT_BUILD)
                        .le("create_time", deadline)
                        .orderByAsc("id")
                        .last("LIMIT " + BATCH_SIZE));

        for (SeckillOrderEntity order : orders) {
            try {
                R result = orderFeignClient.getSeckillOrderStatus(order.getOrderSn());
                if (result != null && Integer.valueOf(0).equals(result.getCode())) {
                    // 1. 订单其实已经建好，只是状态没回写
                    seckillOrderService.onBuildSuccess(order.getOrderSn(),
                            toLong(result.get("orderId")), toBigDecimal(result.get("payAmount")));
                    continue;
                }

                // 2. 确实没建：超过重试上限就回补，否则重发消息
                int retry = order.getBuildRetryCount() == null ? 0 : order.getBuildRetryCount();
                if (retry >= BUILD_RETRY_LIMIT) {
                    seckillOrderService.failAndRelease(order.getOrderSn(), "建单补偿超过上限");
                } else {
                    resendCreateMessage(order, retry + 1);
                }
            } catch (Exception e) {
                log.error("[seckill-reconcile] 建单对账失败 orderSn={}", order.getOrderSn(), e);
            }
        }
    }

    /**
     * 已建单且超过支付超时 + 5 分钟：订单已关闭就补释放，已支付就回填 PAID。
     */
    private void reconcileClosedOrders() {
        Date deadline = new Date(System.currentTimeMillis() - payTimeoutMs - RELEASE_AFTER_MS);
        List<SeckillOrderEntity> orders = seckillOrderDao.selectList(
                new QueryWrapper<SeckillOrderEntity>()
                        .eq("status", SeckillOrderStatus.BUILT)
                        .le("create_time", deadline)
                        .orderByAsc("id")
                        .last("LIMIT " + BATCH_SIZE));

        for (SeckillOrderEntity order : orders) {
            try {
                R result = orderFeignClient.getSeckillOrderStatus(order.getOrderSn());
                if (result == null || !Integer.valueOf(0).equals(result.getCode())
                        || result.get("status") == null) {
                    continue;
                }
                int orderStatus = Integer.parseInt(String.valueOf(result.get("status")));

                if (orderStatus == 4) {
                    // 1. 订单已关闭：补释放
                    seckillOrderService.releaseByOrderSn(order.getOrderSn(), "RECONCILE_CLOSED");
                } else if (orderStatus >= 1 && orderStatus <= 3) {
                    // 2. 已支付/已发货/已完成：回填 PAID
                    seckillOrderDao.update(null, new UpdateWrapper<SeckillOrderEntity>()
                            .set("status", SeckillOrderStatus.PAID)
                            .eq("id", order.getId())
                            .eq("status", SeckillOrderStatus.BUILT));
                }
                // 3. status=0 仍未支付，等订单超时任务关单后再处理
            } catch (Exception e) {
                log.error("[seckill-reconcile] 释放对账失败 orderSn={}", order.getOrderSn(), e);
            }
        }
    }

    /**
     * 重发建单消息；用 build_retry_count 做条件更新，多实例只有一个能发。
     */
    private void resendCreateMessage(SeckillOrderEntity order, int nextRetry) {
        // 1. 记下当前重试次数，用于下一步做乐观锁条件更新
        int currentRetry = order.getBuildRetryCount() == null ? 0 : order.getBuildRetryCount();
        // 2. 只有重试次数没被别人改过才更新成功，多实例并发时只有一个能继续往下发消息
        int updated = seckillOrderDao.update(null, new UpdateWrapper<SeckillOrderEntity>()
                .set("build_retry_count", nextRetry)
                .eq("id", order.getId())
                .eq("status", SeckillOrderStatus.WAIT_BUILD)
                .eq("build_retry_count", currentRetry));
        if (updated == 0) {
            return;
        }

        // 3. 更新成功才真正投递建单消息，避免同一单被多个实例重复发送
        SeckillOrderCreateMessage message = new SeckillOrderCreateMessage();
        BeanUtils.copyProperties(order, message);
        mqMessageService.sendReliable(MqConstants.SECKILL_ORDER_EXCHANGE,
                MqConstants.SECKILL_ORDER_CREATE_ROUTING_KEY, message);
        log.info("[seckill-reconcile] 重发建单消息 orderSn={} retry={}", order.getOrderSn(), nextRetry);
    }

    private Long toLong(Object value) {
        // 1. 空值直接返回 null，有值才按字符串转成 Long，避免类型转换报错
        return value == null ? null : Long.valueOf(String.valueOf(value));
    }

    private BigDecimal toBigDecimal(Object value) {
        // 1. 空值直接返回 null，有值才按字符串转成 BigDecimal，避免浮点精度丢失
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }
}
