package com.atguigu.gulimall.order.web;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.order.entity.OrderEntity;
import com.atguigu.gulimall.order.payment.AlipayService;
import com.atguigu.gulimall.order.service.OrderService;
import com.atguigu.gulimall.order.thread.MemberResponseVoThreadLocal;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * 收银台 / 支付页
 */
@Controller
public class PayController {

    @Autowired
    private OrderService orderService;
    @Autowired
    private AlipayService alipayService;

    /** 支付超时（毫秒），配置在 common.yaml */
    @Value("${gulimall.ware.stock-release.delay-ms}")
    private long payTimeoutMs;

    @GetMapping("/pay.html")
    public String pay(@RequestParam(value = "orderSn", required = false) String orderSn, Model model) {
        if (orderSn == null || orderSn.isBlank()) {
            return "redirect:/list.html";
        }

        OrderEntity order = orderService.getOne(new QueryWrapper<OrderEntity>()
                .eq("order_sn", orderSn)
                .last("LIMIT 1"), false);
        MemberResponseVo loginUser = MemberResponseVoThreadLocal.get();
        if (order == null || loginUser == null
                || !Objects.equals(loginUser.getId(), order.getMemberId())
                || !Integer.valueOf(0).equals(order.getStatus())) {
            return "redirect:/list.html";
        }

        BigDecimal payAmount = order.getPayAmount();
        long remainingSeconds = (order.getCreateTime().getTime() + payTimeoutMs
                - System.currentTimeMillis()) / 1000;
        model.addAttribute("orderSn", order.getOrderSn());
        model.addAttribute("payAmount", payAmount);
        model.addAttribute("payRemainingSeconds", remainingSeconds);
        model.addAttribute("installment3", installment(payAmount, 3));
        model.addAttribute("installment6", installment(payAmount, 6));
        model.addAttribute("installment12", installment(payAmount, 12));
        model.addAttribute("installment24", installment(payAmount, 24));
        model.addAttribute("creditDueDate", LocalDate.now().plusDays(30));
        return "pay";
    }

    /**
     * 用户主动取消订单：status 0 -> 4，并通知 ware 释放锁定库存。
     * 库存释放失败也返回成功，由延时消息 / StockReleaseTask 兜底。
     */
    @PostMapping("/pay/cancel")
    @ResponseBody
    public R cancel(@RequestParam("orderSn") String orderSn) {
        try {
            orderService.cancelOrder(orderSn);
            // 尽量关闭支付宝交易，避免用户取消后还能继续付款
            alipayService.closeTradeQuietly(orderSn);
            return R.ok();
        } catch (RRException e) {
            return R.error(e.getCode(), e.getMsg());
        }
    }

    /**
     * 执行顺序 1：用户点“立即支付”或“支付宝”图标后进入这里。
     * 返回的是支付宝自动提交的 HTML 表单（form），不是 JSON。
     */
    @GetMapping(value = "/pay/alipay", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String alipayPay(@RequestParam("orderSn") String orderSn) {
        // loginUser 来自登录拦截器放到 ThreadLocal 里的当前用户
        MemberResponseVo loginUser = MemberResponseVoThreadLocal.get();
        if (loginUser == null) {
            throw new RRException(BizCodeEnum.ORDER_NOT_LOGIN);
        }
        return alipayService.createPagePayForm(orderSn, loginUser.getId());
    }

    /**
     * 执行顺序 3：支付宝服务器异步通知（POST）。
     * 这个请求没有用户登录会话，所以 /pay/notify 必须在拦截器里放行。
     * 返回值必须是纯文本 success/failure。
     */
    @PostMapping("/pay/notify")
    @ResponseBody
    public String alipayNotify(@RequestParam Map<String, String> params) {
        return alipayService.handleNotify(params);
    }

    /**
     * 执行顺序 4：用户浏览器同步跳回（GET）。
     * 支付结果仍以异步通知或主动查单为准，这里只是把用户带回订单列表。
     */
    @GetMapping("/pay/return")
    public String alipayReturn(@RequestParam Map<String, String> params) {
        alipayService.handleReturn(params);
        return "redirect:/list.html";
    }

    /** 每期应付金额（页面展示用，未计手续费） */
    private BigDecimal installment(BigDecimal payAmount, int periods) {
        return payAmount.divide(BigDecimal.valueOf(periods), 2, RoundingMode.HALF_UP);
    }

}
