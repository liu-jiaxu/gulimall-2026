package com.atguigu.gulimall.order.payment;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradeCloseModel;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.order.entity.OrderEntity;
import com.atguigu.gulimall.order.feign.CartFeignClient;
import com.atguigu.gulimall.order.feign.SeckillFeignClient;
import com.atguigu.gulimall.order.feign.WareFeignClient;
import com.atguigu.gulimall.order.service.OrderService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 支付宝电脑网站支付。
 *
 * <p>执行顺序：
 * <ol>
 *   <li>用户在支付页点“立即支付”或“支付宝”图标，浏览器打开 GET /pay/alipay；</li>
 *   <li>createPagePayForm 生成支付宝自动提交表单，浏览器跳到支付宝收银台；</li>
 *   <li>支付宝服务器异步 POST /pay/notify，handleNotify 更新订单（支付结果以它为准）；</li>
 *   <li>支付宝把浏览器同步跳回 GET /pay/return，handleReturn 主动查单兜底；</li>
 *   <li>订单 DB 更新成功后，调 ware 的 markPaid 把锁定库存转真实扣减。</li>
 * </ol>
 *
 * <p>支付结果以异步通知 notify 为准；同步 return 只用于浏览器跳回，
 * 会再主动查一次交易状态，避免把“跳回”误当成“支付成功”。
 */
@Slf4j
@Service
public class AlipayService {

    @Autowired
    private AlipayClient alipayClient;
    @Autowired
    private AlipayProperties properties;
    @Autowired
    private OrderService orderService;
    @Autowired
    private WareFeignClient wareFeignClient;
    @Autowired
    private CartFeignClient cartFeignClient;
    @Autowired
    private SeckillFeignClient seckillFeignClient;
    @Autowired
    private AlipayRefundService alipayRefundService;

    /**
     * 生成支付宝电脑网站支付的自动提交表单。
     */
    public String createPagePayForm(String orderSn, Long memberId) {
        // 执行顺序 1：查订单，并确认这个订单属于当前登录用户。
        // orderSn（订单号）是商户侧对外业务号，防止改 orderSn 去支付别人的订单。
        OrderEntity order = orderService.getOne(new QueryWrapper<OrderEntity>()
                .eq("order_sn", orderSn)
                .last("LIMIT 1"), false);

        if (order == null || memberId == null || !memberId.equals(order.getMemberId())) {
            throw new RRException(BizCodeEnum.ORDER_NOT_FOUND_OR_NOT_OWNER);
        }

        // 执行顺序 2：只有待付款（status=0）的订单能发起支付。
        // status 是 oms_order.status：0 待付款、1 待发货、4 已关闭。
        if (!Integer.valueOf(0).equals(order.getStatus())) {
            throw new RRException(BizCodeEnum.ORDER_PAY_NOT_ALLOWED);
        }

        // 执行顺序 3：payAmount（应付金额）必须存在且大于 0。
        if (order.getPayAmount() == null
                || order.getPayAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RRException(BizCodeEnum.ORDER_PAY_AMOUNT_INVALID);
        }

        // 执行顺序 4：创建“电脑网站支付”请求。
        // notify_url（异步通知地址）由支付宝服务器 POST 访问，必须公网可达；
        // return_url（同步跳转地址）由用户浏览器 GET 访问，只用于跳回页面。
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setNotifyUrl(properties.getNotifyUrl());
        request.setReturnUrl(properties.getReturnUrl());

        // 执行顺序 5：组装 biz_content（业务参数）。
        // out_trade_no：商户订单号，对应 orderSn；
        // total_amount：支付金额，单位元，保留两位小数；
        // subject：交易标题；
        // product_code：产品码，电脑网站支付固定 FAST_INSTANT_TRADE_PAY；
        // timeout_express：支付超时时间，和订单 30 分钟超时保持一致。
        AlipayTradePagePayModel model = new AlipayTradePagePayModel();
        model.setOutTradeNo(orderSn);
        model.setTotalAmount(order.getPayAmount()
                .setScale(2, RoundingMode.HALF_UP).toPlainString());
        model.setSubject("谷粒商城订单 " + orderSn);
        model.setProductCode("FAST_INSTANT_TRADE_PAY");
        model.setTimeoutExpress("30m");
        request.setBizModel(model);

        // 执行顺序 6：pageExecute（调用支付宝接口）返回一段自动提交的 HTML 表单，
        // 不是 JSON。浏览器打开这段 HTML 会自动跳到支付宝收银台。
        try {
            AlipayTradePagePayResponse response = alipayClient.pageExecute(request);
            if (!response.isSuccess() || response.getBody() == null) {
                log.error("[alipay] 创建支付单失败，orderSn={}, code={}, msg={}, subMsg={}",
                        orderSn, response.getCode(), response.getMsg(), response.getSubMsg());
                throw new RRException(BizCodeEnum.ORDER_PAY_CREATE_FAILED);
            }
            return response.getBody();
        } catch (AlipayApiException e) {
            log.error("[alipay] 创建支付单异常，orderSn={}", orderSn, e);
            throw new RRException(BizCodeEnum.ORDER_PAY_CREATE_FAILED);
        }
    }

    /**
     * 支付宝异步通知。处理成功返回 success，支付宝才会停止重试。
     */
    public String handleNotify(Map<String, String> params) {
        try {
            // 执行顺序 1：验签。
            // 用支付宝公钥验证签名，确认这次 POST 真的是支付宝发的，而不是别人伪造回调。
            if (!verify(params)) {
                log.warn("[alipay-notify] 验签失败，params={}", params);
                return "failure";
            }

            // 执行顺序 2：校验 app_id（应用 ID），防止把别的支付宝应用的回调串到本项目。
            if (!properties.getAppId().equals(params.get("app_id"))) {
                log.warn("[alipay-notify] app_id 不匹配，expected={}, actual={}",
                        properties.getAppId(), params.get("app_id"));
                return "failure";
            }

            // 执行顺序 3：看 trade_status（交易状态）。
            // TRADE_SUCCESS=支付成功，TRADE_FINISHED=交易完成；其它状态不处理，
            // 但仍然返回 success，告诉支付宝“我收到了”，避免它一直重试。
            String tradeStatus = params.get("trade_status");
            if (!"TRADE_SUCCESS".equals(tradeStatus)
                    && !"TRADE_FINISHED".equals(tradeStatus)) {
                return "success";
            }

            // 执行顺序 4：更新订单状态 + 写 oms_payment_info（支付信息表）。
            // out_trade_no 是商户订单号，trade_no 是支付宝交易流水号。
            String orderSn = params.get("out_trade_no");
            String tradeNo = params.get("trade_no");
            BigDecimal paidAmount = new BigDecimal(params.get("total_amount"));

            boolean paid = orderService.markOrderPaid(
                    orderSn, tradeNo, paidAmount, params.toString());

            // 执行顺序 5：订单 DB 提交后，再调 ware.markPaid 把锁定库存转真实扣减，
            // 并从购物车删除这次已支付的商品。
            if (paid) {
                notifyWarePaid(orderSn);
                clearCartQuietly(orderSn);
                notifySeckillPaid(orderSn);
            } else if (orderService.isOrderClosed(orderSn)) {
                // 订单已超时关闭，但用户又付款成功：全额原路退款
                alipayRefundService.refundLatePayment(
                        orderSn, tradeNo, paidAmount, params.toString());
            }
            // 返回纯文本 success，支付宝收到后就不再重试这条通知。
            return "success";
        } catch (Exception e) {
            log.error("[alipay-notify] 处理失败，params={}", params, e);
            return "failure";
        }
    }

    /**
     * 同步跳转。浏览器 GET 回来，参数不代表支付成功，必须查单。
     */
    public String handleReturn(Map<String, String> params) {
        try {
            // 执行顺序 1：return（同步跳转）参数也可能被伪造，先验签。
            if (!verify(params)) {
                log.warn("[alipay-return] 验签失败，params={}", params);
                return null;
            }

            String orderSn = params.get("out_trade_no");
            if (orderSn == null || orderSn.isBlank()) {
                return null;
            }

            // 执行顺序 2：return 参数里没有 trade_status（交易状态），
            // 所以必须用 AlipayTradeQueryRequest 主动查单，不能只看浏览器跳回来。
            AlipayTradeQueryRequest query = new AlipayTradeQueryRequest();
            query.setBizContent("{\"out_trade_no\":\"" + orderSn + "\"}");
            AlipayTradeQueryResponse response = alipayClient.execute(query);

            // 执行顺序 3：查询结果明确是成功，才更新订单。
            if (response.isSuccess()
                    && ("TRADE_SUCCESS".equals(response.getTradeStatus())
                    || "TRADE_FINISHED".equals(response.getTradeStatus()))) {

                BigDecimal paidAmount = new BigDecimal(response.getTotalAmount());
                boolean paid = orderService.markOrderPaid(
                        orderSn, response.getTradeNo(), paidAmount, params.toString());
                if (paid) {
                    notifyWarePaid(orderSn);
                    clearCartQuietly(orderSn);
                    notifySeckillPaid(orderSn);
                } else if (orderService.isOrderClosed(orderSn)) {
                    // 同步跳转查单发现“订单已关闭但已支付”，同样走自动退款
                    alipayRefundService.refundLatePayment(
                            orderSn, response.getTradeNo(), paidAmount, params.toString());
                }
            }
            return orderSn;
        } catch (Exception e) {
            log.error("[alipay-return] 处理失败，params={}", params, e);
            return null;
        }
    }

    /**
     * 关闭支付宝交易。
     *
     * <p>订单超时/用户取消时调用，尽量避免用户之后还能继续付款。
     * 交易不存在、已经支付、已经关闭等情况只记日志，不影响本地取消流程。
     */
    public void closeTradeQuietly(String orderSn) {
        try {
            AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
            AlipayTradeCloseModel model = new AlipayTradeCloseModel();
            model.setOutTradeNo(orderSn);
            request.setBizModel(model);

            AlipayTradeCloseResponse response = alipayClient.execute(request);
            if (response.isSuccess()) {
                log.info("[alipay-close] 交易已关闭：orderSn={}", orderSn);
            } else {
                log.warn("[alipay-close] 关闭交易未成功：orderSn={}, code={}, msg={}, subMsg={}",
                        orderSn, response.getCode(), response.getMsg(), response.getSubMsg());
            }
        } catch (Exception e) {
            log.warn("[alipay-close] 关闭交易异常：orderSn={}", orderSn, e);
        }
    }

    /**
     * 支付宝签名校验。
     *
     * <p>AlipaySignature.rsaCheckV1 是支付宝 SDK 提供的 RSA2 验签方法：
     * 用“支付宝公钥”验证参数里的 sign，防止别人伪造 notify/return。
     */
    private boolean verify(Map<String, String> params) throws AlipayApiException {
        return AlipaySignature.rsaCheckV1(params,
                properties.getAlipayPublicKey(),
                properties.getCharset(),
                properties.getSignType());
    }

    /**
     * 支付成功后删除购物车里这次下单的商品。
     *
     * <p>取订单明细里的 skuId，调用 cart 的 /deleteCheckedItems。
     * 删除失败只记日志，不影响支付结果；购物车残留可以下次再清。
     */
    private void clearCartQuietly(String orderSn) {
        try {
            // 秒杀订单不经过购物车，不能按 skuId 删用户购物车里的同款商品
            if (orderService.isSeckillOrder(orderSn)) {
                return;
            }
            List<Long> skuIds = orderService.getOrderSkuIds(orderSn);
            if (!skuIds.isEmpty()) {
                cartFeignClient.deleteCheckedItems(skuIds);
            }
        } catch (Exception e) {
            log.error("[alipay] 删除购物车商品失败：orderSn={}", orderSn, e);
        }
    }

    /**
     * 秒杀订单支付成功后，实时把 sms_seckill_order 置为 PAID。
     * 失败只记日志，SeckillOrderReconcileTask 会兜底回填。
     */
    private void notifySeckillPaid(String orderSn) {
        try {
            if (!orderService.isSeckillOrder(orderSn)) {
                return;
            }
            R result = seckillFeignClient.markSeckillOrderPaid(orderSn);
            if (result != null && result.getCode() != null && result.getCode() != 0) {
                log.error("[alipay] 秒杀单支付状态回填失败：orderSn={}, msg={}",
                        orderSn, result.get("msg"));
            }
        } catch (Exception e) {
            log.error("[alipay] 秒杀单支付状态回填异常：orderSn={}", orderSn, e);
        }
    }

    /**
     * 订单 DB 已支付后，再通知 ware 把锁定库存转真实扣减。
     *
     * <p>这里失败不能把支付回调改成 failure：支付宝已经扣款，订单也已经置为已支付，
     * 库存扣减失败要单独补偿/重试。
     */
    private void notifyWarePaid(String orderSn) {
        try {
            R result = wareFeignClient.markPaid(orderSn);
            if (result != null && result.getCode() != null && result.getCode() != 0) {
                log.error("[alipay] 订单已支付，但库存扣减失败：orderSn={}, msg={}",
                        orderSn, result.get("msg"));
            }
        } catch (Exception e) {
            log.error("[alipay] 订单已支付，但库存扣减失败：orderSn={}", orderSn, e);
        }
    }
}
