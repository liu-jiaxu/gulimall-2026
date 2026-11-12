package com.atguigu.gulimall.order.task;

import com.atguigu.gulimall.order.payment.AlipayRefundService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 退款重试任务。
 *
 * <p>只处理 oms_refund_info.refund_status=2（失败/待重试）的记录。
 * 退款请求使用固定的 out_request_no，重复调用不会重复退款。
 */
@Slf4j
@Component
public class RefundRetryTask {

    @Autowired
    private AlipayRefundService alipayRefundService;

    @Scheduled(cron = "0 * * * * ?")
    public void retryRefund() {
        try {
            int count = alipayRefundService.retryFailedRefunds();
            if (count > 0) {
                log.info("[alipay-refund-task] 本轮重试 {} 条退款", count);
            }
        } catch (Exception e) {
            log.error("[alipay-refund-task] 退款重试任务异常", e);
        }
    }
}
