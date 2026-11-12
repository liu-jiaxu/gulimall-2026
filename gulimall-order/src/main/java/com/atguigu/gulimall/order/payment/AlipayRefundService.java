package com.atguigu.gulimall.order.payment;

import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.atguigu.gulimall.order.dao.OrderReturnApplyDao;
import com.atguigu.gulimall.order.entity.OrderEntity;
import com.atguigu.gulimall.order.entity.OrderReturnApplyEntity;
import com.atguigu.gulimall.order.entity.RefundInfoEntity;
import com.atguigu.gulimall.order.service.OrderService;
import com.atguigu.gulimall.order.service.RefundInfoService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/**
 * 支付宝自动退款。
 *
 * <p>处理两类场景：
 * <ul>
 *   <li>订单已经超时关闭，但用户又付款成功了 —— 全额退款；</li>
 *   <li>售后退货单处理完成 —— 按退货申请金额原路退款（支持部分退款）。</li>
 * </ul>
 *
 * <p>退款记录写 oms_refund_info：
 * <ul>
 *   <li>refund_type=1：超时支付自动退款，order_return_id 存订单 id</li>
 *   <li>refund_type=2：退货退款，order_return_id 存退货申请 id</li>
 *   <li>refund_status=1：退款成功</li>
 *   <li>refund_status=2：退款失败/待重试，定时任务会继续调支付宝</li>
 * </ul>
 *
 * <p>幂等：每条退款记录使用固定的 out_request_no（refund_sn），
 * 重复调用支付宝不会重复退钱。
 */
@Slf4j
@Service
public class AlipayRefundService {

    private static final int ORDER_STATUS_PAID = 1;
    private static final int ORDER_STATUS_SHIPPED = 2;
    private static final int ORDER_STATUS_COMPLETED = 3;
    private static final int ORDER_STATUS_CLOSED = 4;

    private static final int REFUND_SUCCESS = 1;
    /** 失败但可重试（网络超时、支付宝临时异常等） */
    private static final int REFUND_FAILED = 2;
    /** 永久失败，重试也不会成功，需要人工处理 */
    public static final int REFUND_TERMINAL_FAILURE = 3;

    /**
     * 这些支付宝错误码代表“这笔交易在支付宝侧不存在 / 状态已终结”，
     * 再重试一百次也是同样结果，必须停止定时任务，避免刷日志。
     */
    private static final Set<String> TERMINAL_SUB_CODES = Set.of(
            "ACQ.TRADE_NOT_EXIST",
            "ACQ.TRADE_STATUS_ERROR",
            "ACQ.TRADE_HAS_FINISHED",
            // 退款金额超过交易实付金额，重试同一金额永远不会成功
            "ACQ.REASON_TRADE_REFUND_FEE_ERR"
    );

    /** 超时支付自动退款 */
    public static final int REFUND_TYPE_LATE_PAYMENT = 1;
    /** 售后退货退款 */
    public static final int REFUND_TYPE_RETURN = 2;

    @Autowired
    private AlipayClient alipayClient;
    @Autowired
    private OrderService orderService;
    @Autowired
    private RefundInfoService refundInfoService;
    @Autowired
    private OrderReturnApplyDao orderReturnApplyDao;

    /**
     * 订单已关闭但收到支付成功通知：按订单应付金额全额退款。
     */
    public void refundLatePayment(String orderSn, String tradeNo,
                                  BigDecimal paidAmount, String callbackContent) {
        OrderEntity order = orderService.getOne(new QueryWrapper<OrderEntity>()
                .eq("order_sn", orderSn)
                .last("LIMIT 1"), false);

        if (order == null) {
            log.error("[alipay-refund] 订单不存在，无法退款：orderSn={}", orderSn);
            return;
        }
        if (!Integer.valueOf(ORDER_STATUS_CLOSED).equals(order.getStatus())) {
            log.warn("[alipay-refund] 订单不是已关闭状态，不自动退款：orderSn={}, status={}",
                    orderSn, order.getStatus());
            return;
        }
        if (order.getPayAmount() == null || paidAmount == null
                || order.getPayAmount().compareTo(paidAmount) != 0) {
            log.error("[alipay-refund] 退款金额和订单金额不一致：orderSn={}, orderAmount={}, paidAmount={}",
                    orderSn, order.getPayAmount(), paidAmount);
            return;
        }

        RefundInfoEntity refund = getOrCreateLatePaymentRefund(order, tradeNo, callbackContent);
        if (Integer.valueOf(REFUND_SUCCESS).equals(refund.getRefundStatus())) {
            log.info("[alipay-refund] 已退款成功，跳过：orderSn={}", orderSn);
            return;
        }

        doRefund(refund, order, order.getPayAmount(), "订单超时关闭后支付，自动全额退款");
    }

    /**
     * 售后退货退款：按退货申请上的退款金额原路退回。
     *
     * @return true 表示退款成功；false 表示订单状态/金额不合法或支付宝退款失败
     *         （失败记录已落库，可由定时任务重试）
     */
    // 退款记录必须独立提交：调用方 completeReturn 在退款成功后还要回补库存，
    // 如果回补失败会回滚外层事务；退款记录若跟着回滚，定时任务就再也找不到这笔失败了。
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean refundReturn(OrderReturnApplyEntity apply, OrderEntity order) {
        if (apply == null || order == null) {
            log.error("[alipay-refund] 退货退款参数为空：apply={}, order={}", apply, order);
            return false;
        }

        Integer status = order.getStatus();
        if (!Integer.valueOf(ORDER_STATUS_PAID).equals(status)
                && !Integer.valueOf(ORDER_STATUS_SHIPPED).equals(status)
                && !Integer.valueOf(ORDER_STATUS_COMPLETED).equals(status)) {
            log.error("[alipay-refund] 订单未支付或状态不允许退款：orderSn={}, status={}",
                    order.getOrderSn(), status);
            return false;
        }

        BigDecimal amount = apply.getReturnAmount();
        if (amount == null || amount.signum() <= 0) {
            log.error("[alipay-refund] 退货退款金额异常：applyId={}, amount={}",
                    apply.getId(), amount);
            return false;
        }

        RefundInfoEntity refund = getOrCreateReturnRefund(apply, order);
        if (Integer.valueOf(REFUND_SUCCESS).equals(refund.getRefundStatus())) {
            log.info("[alipay-refund] 退货退款已成功，跳过：applyId={}", apply.getId());
            return true;
        }

        // 退款金额以退货单当前金额为准，同步回退款记录，
        // 避免之前用错误金额失败后，记录里一直留着旧金额
        refund.setRefund(amount);
        doRefund(refund, order, amount, "售后退货退款");
        return Integer.valueOf(REFUND_SUCCESS).equals(refund.getRefundStatus());
    }

    /**
     * 定时任务调用：重试 refund_status=2 的退款记录。
     *
     * <p>按 refund_type 区分 order_return_id 指向的是订单还是退货申请。
     */
    public int retryFailedRefunds() {
        List<RefundInfoEntity> refunds = refundInfoService.list(
                new QueryWrapper<RefundInfoEntity>()
                        .eq("refund_status", REFUND_FAILED)
                        .last("LIMIT 50"));

        int retried = 0;
        for (RefundInfoEntity refund : refunds) {
            OrderEntity order;
            BigDecimal amount;
            String reason;

            if (Integer.valueOf(REFUND_TYPE_RETURN).equals(refund.getRefundType())) {
                OrderReturnApplyEntity apply =
                        orderReturnApplyDao.selectById(refund.getOrderReturnId());
                if (apply == null) {
                    log.error("[alipay-refund] 退款记录对应的退货单不存在：refundId={}, applyId={}",
                            refund.getId(), refund.getOrderReturnId());
                    continue;
                }
                order = orderService.getById(apply.getOrderId());
                amount = apply.getReturnAmount();
                reason = "售后退货退款";
            } else {
                order = orderService.getById(refund.getOrderReturnId());
                amount = order == null ? null : order.getPayAmount();
                reason = "订单超时关闭后支付，自动全额退款";
            }

            if (order == null || amount == null || amount.signum() <= 0) {
                log.error("[alipay-refund] 退款记录关联数据异常：refundId={}, orderReturnId={}",
                        refund.getId(), refund.getOrderReturnId());
                continue;
            }

            doRefund(refund, order, amount, reason);
            retried++;
        }
        return retried;
    }

    private RefundInfoEntity getOrCreateLatePaymentRefund(OrderEntity order, String tradeNo,
                                                          String callbackContent) {
        RefundInfoEntity refund = refundInfoService.getOne(
                new QueryWrapper<RefundInfoEntity>()
                        .eq("refund_sn", order.getOrderSn() + "-refund")
                        .last("LIMIT 1"), false);

        if (refund == null) {
            refund = new RefundInfoEntity();
            // 超时支付退款：order_return_id 关联的是订单 id
            refund.setOrderReturnId(order.getId());
            refund.setRefund(order.getPayAmount());
            // 幂等请求号：同一个订单重复退款时，支付宝按同一个 out_request_no 处理
            refund.setRefundSn(order.getOrderSn() + "-refund");
            refund.setRefundStatus(REFUND_FAILED);
            refund.setRefundChannel(1);
            refund.setRefundType(REFUND_TYPE_LATE_PAYMENT);
            refund.setRefundContent(fit("等待退款：tradeNo=" + tradeNo
                    + ", callback=" + callbackContent));
            refundInfoService.save(refund);
        }
        return refund;
    }

    private RefundInfoEntity getOrCreateReturnRefund(OrderReturnApplyEntity apply,
                                                     OrderEntity order) {
        String refundSn = apply.getOrderSn() + "-R" + apply.getId();
        RefundInfoEntity refund = refundInfoService.getOne(
                new QueryWrapper<RefundInfoEntity>()
                        .eq("refund_sn", refundSn)
                        .last("LIMIT 1"), false);

        if (refund == null) {
            refund = new RefundInfoEntity();
            // 退货退款：order_return_id 关联的是退货申请 id
            refund.setOrderReturnId(apply.getId());
            refund.setRefund(apply.getReturnAmount());
            refund.setRefundSn(refundSn);
            refund.setRefundStatus(REFUND_FAILED);
            refund.setRefundChannel(refundChannel(order.getPayType()));
            refund.setRefundType(REFUND_TYPE_RETURN);
            refund.setRefundContent(fit("等待退款：退货单=" + apply.getId()
                    + ", 订单=" + order.getOrderSn()));
            refundInfoService.save(refund);
        }
        return refund;
    }

    /** oms_order.pay_type → oms_refund_info.refund_channel */
    private int refundChannel(Integer payType) {
        if (payType == null) {
            return 1;
        }
        return switch (payType) {
            case 1 -> 1; // 支付宝
            case 2 -> 2; // 微信
            case 3 -> 3; // 银联
            default -> 4; // 货到付款等走汇款
        };
    }

    private void doRefund(RefundInfoEntity refund, OrderEntity order,
                          BigDecimal amount, String reason) {
        AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
        AlipayTradeRefundModel model = new AlipayTradeRefundModel();
        model.setOutTradeNo(order.getOrderSn());
        model.setRefundAmount(amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
        model.setRefundReason(reason);
        model.setOutRequestNo(refund.getRefundSn());
        request.setBizModel(model);

        try {
            AlipayTradeRefundResponse response = alipayClient.execute(request);
            refund.setRefundContent(fit(response.getBody()));

            if (response.isSuccess()) {
                refund.setRefundStatus(REFUND_SUCCESS);
                log.info("[alipay-refund] 退款成功：orderSn={}, refundSn={}, amount={}",
                        order.getOrderSn(), refund.getRefundSn(), amount);
            } else if (TERMINAL_SUB_CODES.contains(response.getSubCode())) {
                refund.setRefundStatus(REFUND_TERMINAL_FAILURE);
                log.error("[alipay-refund] 退款永久失败，停止重试：orderSn={}, subCode={}, subMsg={}",
                        order.getOrderSn(), response.getSubCode(), response.getSubMsg());
            } else {
                refund.setRefundStatus(REFUND_FAILED);
                log.error("[alipay-refund] 退款失败（可重试）：orderSn={}, code={}, msg={}, subMsg={}",
                        order.getOrderSn(), response.getCode(),
                        response.getMsg(), response.getSubMsg());
            }
        } catch (Exception e) {
            refund.setRefundStatus(REFUND_FAILED);
            refund.setRefundContent(fit(e.getMessage()));
            log.error("[alipay-refund] 退款异常：orderSn={}", order.getOrderSn(), e);
        }

        refundInfoService.updateById(refund);
    }

    private String fit(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 5000 ? text.substring(0, 5000) : text;
    }
}
