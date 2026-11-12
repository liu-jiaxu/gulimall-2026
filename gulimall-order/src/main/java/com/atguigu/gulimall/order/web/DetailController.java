package com.atguigu.gulimall.order.web;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.api.order.vo.OrderItemVo;
import com.atguigu.gulimall.order.entity.OrderEntity;
import com.atguigu.gulimall.order.entity.OrderItemEntity;
import com.atguigu.gulimall.order.feign.CartFeignClient;
import com.atguigu.gulimall.order.service.OrderItemService;
import com.atguigu.gulimall.order.service.OrderService;
import com.atguigu.gulimall.order.thread.MemberResponseVoThreadLocal;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 订单详情页：展示当前登录用户自己的订单和商品明细。
 */
@Controller
public class DetailController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderItemService orderItemService;

    @Autowired
    private CartFeignClient cartFeignClient;

    @GetMapping("/detail.html")
    public String detail(@RequestParam(value = "orderSn", required = false) String orderSn,
                         Model model) {

        MemberResponseVo loginUser = MemberResponseVoThreadLocal.get();
        if (loginUser == null || !StringUtils.hasText(orderSn)) {
            return "redirect:/list.html";
        }

        OrderEntity order = orderService.getOne(new QueryWrapper<OrderEntity>()
                .eq("order_sn", orderSn)
                .last("LIMIT 1"), false);
        if (order == null || !Objects.equals(loginUser.getId(), order.getMemberId())) {
            return "redirect:/list.html";
        }

        List<OrderItemEntity> orderItems = orderItemService.list(
                new QueryWrapper<OrderItemEntity>()
                        .eq("order_id", order.getId()));

        // 顶部 mini 购物车：和商品页、订单列表页保持一致
        List<OrderItemVo> cartItems = List.of();
        try {
            List<OrderItemVo> items = cartFeignClient.getCurrentCartItems();
            if (items != null) {
                cartItems = items;
            }
        } catch (Exception ignored) {
            // cart 服务不可用时不阻塞订单详情
        }

        model.addAttribute("order", order);
        model.addAttribute("orderItems", orderItems);
        model.addAttribute("statusText", statusText(order.getStatus()));
        model.addAttribute("fullAddress", fullAddress(order));
        model.addAttribute("payTypeText", order.getPayType() != null && order.getPayType() == 1
                ? "支付宝" : "在线支付");
        model.addAttribute("cartItems", cartItems);
        model.addAttribute("cartCount", cartItems.size());

        return "detail";
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "未知状态";
        }
        return switch (status) {
            case 0 -> "等待付款";
            case 1 -> "等待发货";
            case 2 -> "等待收货";
            case 3 -> "已完成";
            case 4 -> "已关闭";
            default -> "无效订单";
        };
    }

    private String fullAddress(OrderEntity order) {
        return Stream.of(order.getReceiverProvince(), order.getReceiverCity(),
                        order.getReceiverRegion(), order.getReceiverDetailAddress())
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(" "));
    }
}
