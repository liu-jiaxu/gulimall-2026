package com.atguigu.gulimall.order.task;

import com.atguigu.gulimall.order.entity.OrderEntity;
import com.atguigu.gulimall.order.payment.AlipayService;
import com.atguigu.gulimall.order.service.OrderService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 订单超时关闭任务：把超过支付时间仍未付款的订单改成「已关闭」。
 *
 * <p>支付超时时间与库存解锁共用 {@code gulimall.ware.stock-release.delay-ms}，
 * 所以 PayController 的倒计时和这里关单用的是同一个时间点。
 *
 * <p>库存释放仍然由 ware 的延时消息负责，本任务只改订单状态。
 * 多实例部署时靠 status 条件更新保证同一订单只会被关一次。
 */
@Slf4j
@Component
public class OrderTimeoutTask {

    /** 每批处理数量，避免一次扫太多 */
    private static final int BATCH_SIZE = 100;

    /** oms_order.status：0-待付款 */
    private static final int STATUS_UNPAID = 0;

    /** oms_order.status：4-已关闭 */
    private static final int STATUS_CLOSED = 4;

    @Autowired
    private OrderService orderService;
    @Autowired
    private AlipayService alipayService;

    /** 支付超时（毫秒），配置在 common.yaml */
    @Value("${gulimall.ware.stock-release.delay-ms}")
    private long payTimeoutMs;

    /**
     * 每分钟跑一次
     * second
     * minute
     * hour
     * day of month
     * month
     * day of week
     */
    @Scheduled(cron = "0 * * * * ?")
    public void closeExpiredOrders() {
        Date deadline = new Date(System.currentTimeMillis() - payTimeoutMs);

        List<OrderEntity> orders = orderService.list(new QueryWrapper<OrderEntity>()
                .select("id", "order_sn", "source_type")
                .eq("status", STATUS_UNPAID)
                .le("create_time", deadline)
                .last("LIMIT " + BATCH_SIZE));

        if (orders.isEmpty()) {
            return;
        }

        int closed = 0;
        for (OrderEntity order : orders) {
            // 条件更新：只有仍是「待付款」的订单才会被关闭。
            // 和支付回调并发时，只有一方能更新成功。
            boolean updated = orderService.update(new UpdateWrapper<OrderEntity>()
                    .set("status", STATUS_CLOSED)
                    .eq("id", order.getId())
                    .eq("status", STATUS_UNPAID));

            if (updated) {
                closed++;
                // 关闭支付宝交易，尽量避免超时后继续付款；
                // 如果交易已经支付，后续 notify 会走到自动退款。
                alipayService.closeTradeQuietly(order.getOrderSn());
                // 秒杀订单：立即通知 seckill 归还库存；普通订单仍由 ware 延时消息释放
                if (Integer.valueOf(2).equals(order.getSourceType())) {
                    orderService.afterOrderClosed(order, "ORDER_TIMEOUT");
                }
                log.info("[order-timeout] 订单超时关闭：orderSn={}", order.getOrderSn());
            }
        }

        log.info("[order-timeout] 本轮扫描 {} 单，关闭 {} 单", orders.size(), closed);
    }

}
