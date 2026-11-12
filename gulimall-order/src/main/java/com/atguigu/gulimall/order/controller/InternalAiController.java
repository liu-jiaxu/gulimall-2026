package com.atguigu.gulimall.order.controller;

import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.common.web.InternalAiControllerSupport;
import com.atguigu.gulimall.order.entity.OrderEntity;
import com.atguigu.gulimall.order.entity.OrderItemEntity;
import com.atguigu.gulimall.order.service.OrderItemService;
import com.atguigu.gulimall.order.service.OrderService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 给 gulimall-agent 用的订单只读接口。
 *
 * <p>用户端工具只能查本人订单（memberId 由 agent 从登录态带过来）；
 * 管理端工具可以查全部订单和交易统计。
 */
@RestController
@RequestMapping("/internal/ai/order")
@RequiredArgsConstructor
public class InternalAiController extends InternalAiControllerSupport {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OrderService orderService;
    private final OrderItemService orderItemService;

    /**
     * 用户订单列表。
     */
    @GetMapping("/member/{memberId}")
    public R memberOrders(HttpServletRequest request,
                          @PathVariable("memberId") Long memberId,
                          @RequestParam(value = "status", required = false) Integer status,
                          @RequestParam(value = "keyword", required = false) String keyword,
                          @RequestParam(value = "page", defaultValue = "1") long page,
                          @RequestParam(value = "limit", defaultValue = "10") long limit) {
        // 1. 校验内部调用凭证
        verifyInternal(request);

        // 2. 复用订单列表页的查询（会带出订单明细）
        IPage<?> result = orderService.queryUserOrders(memberId, status, keyword, page, limit);
        return R.ok().put("page", new PageUtils(result));
    }

    /**
     * 用户订单详情：只能看自己的单。
     */
    @GetMapping("/detail/{orderSn}")
    public R detail(HttpServletRequest request,
                    @PathVariable("orderSn") String orderSn,
                    @RequestParam(value = "memberId", required = false) Long memberId) {
        // 1. 校验内部调用凭证
        verifyInternal(request);

        // 2. 按订单号查订单
        OrderEntity order = orderService.getOne(
                new QueryWrapper<OrderEntity>().eq("order_sn", orderSn).last("limit 1"));
        if (order == null) {
            return R.error(9001, "订单不存在：" + orderSn);
        }

        // 3. 用户端必须校验归属，防止查别人的订单
        if (memberId != null && !memberId.equals(order.getMemberId())) {
            return R.error(9001, "无权查看该订单");
        }

        // 4. 组装精简详情，手机号脱敏
        List<OrderItemEntity> items = orderItemService.list(
                new QueryWrapper<OrderItemEntity>().eq("order_sn", orderSn));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderSn", order.getOrderSn());
        data.put("status", order.getStatus());
        data.put("createTime", format(order.getCreateTime()));
        data.put("paymentTime", format(order.getPaymentTime()));
        data.put("deliveryTime", format(order.getDeliveryTime()));
        data.put("totalAmount", order.getTotalAmount());
        data.put("payAmount", order.getPayAmount());
        data.put("freightAmount", order.getFreightAmount());
        data.put("receiverName", order.getReceiverName());
        data.put("receiverPhone", maskPhone(order.getReceiverPhone()));
        data.put("receiverCity", order.getReceiverCity());
        data.put("receiverRegion", order.getReceiverRegion());
        data.put("receiverDetailAddress", order.getReceiverDetailAddress());
        data.put("deliveryCompany", order.getDeliveryCompany());
        data.put("deliverySn", order.getDeliverySn());
        data.put("note", order.getNote());
        data.put("items", items);
        return R.ok().put("data", data);
    }

    /**
     * 管理端订单检索。
     */
    @GetMapping("/admin/search")
    public R adminSearch(HttpServletRequest request,
                         @RequestParam(value = "orderSn", required = false) String orderSn,
                         @RequestParam(value = "memberId", required = false) Long memberId,
                         @RequestParam(value = "status", required = false) Integer status,
                         @RequestParam(value = "startTime", required = false) String startTime,
                         @RequestParam(value = "endTime", required = false) String endTime,
                         @RequestParam(value = "page", defaultValue = "1") long page,
                         @RequestParam(value = "limit", defaultValue = "10") long limit) {
        // 1. 校验内部调用凭证
        verifyInternal(request);

        // 2. 组合筛选条件
        QueryWrapper<OrderEntity> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(orderSn)) {
            wrapper.like("order_sn", orderSn);
        }
        if (memberId != null) {
            wrapper.eq("member_id", memberId);
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        if (StringUtils.hasText(startTime)) {
            wrapper.ge("create_time", startTime);
        }
        if (StringUtils.hasText(endTime)) {
            wrapper.le("create_time", endTime);
        }
        wrapper.orderByDesc("create_time");

        // 3. 分页返回
        IPage<OrderEntity> result = orderService.page(new Page<>(page, limit), wrapper);
        return R.ok().put("page", new PageUtils(result));
    }

    /**
     * 管理端交易统计。
     */
    @GetMapping("/admin/stat")
    public R adminStat(HttpServletRequest request,
                       @RequestParam(value = "startTime", required = false) String startTime,
                       @RequestParam(value = "endTime", required = false) String endTime,
                       @RequestParam(value = "keyword", required = false) String keyword) {
        // 1. 校验内部调用凭证
        verifyInternal(request);

        // 2. 默认统计最近 30 天，避免前端没传时间导致 SQL 条件为空
        String end = StringUtils.hasText(endTime) ? endTime : LocalDateTime.now().format(DATE_TIME);
        String start = StringUtils.hasText(startTime)
                ? startTime
                : LocalDate.now().minusDays(30).atStartOfDay().format(DATE_TIME);
        return R.ok().put("data", orderService.getOrderStat(start, end, keyword));
    }

    private String format(java.util.Date date) {
        if (date == null) {
            return null;
        }
        return new java.sql.Timestamp(date.getTime()).toLocalDateTime().format(DATE_TIME);
    }
}
