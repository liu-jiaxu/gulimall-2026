package com.atguigu.gulimall.order.web;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.api.order.vo.OrderItemVo;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.order.feign.CartFeignClient;
import com.atguigu.gulimall.order.service.OrderReturnApplyService;
import com.atguigu.gulimall.order.service.OrderService;
import com.atguigu.gulimall.order.thread.MemberResponseVoThreadLocal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/**
 * 订单列表页。
 *
 * <p>查询当前登录用户的订单，支持：
 * <ul>
 *   <li>status：0 待付款、1 待发货、2 待收货、3 已完成、4 已关闭、
 *       5 待退货、6 已退货（后两个按退货申请状态筛选）</li>
 *   <li>keyword：订单号 / 商品名称 / 商品编号</li>
 *   <li>pageNo：分页页码</li>
 * </ul>
 *
 * <p>顶部购物车数量走 cart 服务，失败时按 0 处理，不影响订单页打开。
 */
@Controller
public class ListController {

    private static final long PAGE_SIZE = 5;

    @Autowired
    private OrderService orderService;

    @Autowired
    private CartFeignClient cartFeignClient;

    @Autowired
    private OrderReturnApplyService orderReturnApplyService;

    @GetMapping("/list.html")
    public String list(@RequestParam(value = "pageNo", defaultValue = "1") long pageNo,
                       @RequestParam(value = "status", required = false) String statusParam,
                       @RequestParam(value = "keyword", required = false) String keyword,
                       Model model) {

        MemberResponseVo loginUser = MemberResponseVoThreadLocal.get();
        if (loginUser == null) {
            throw new RRException(BizCodeEnum.ORDER_NOT_LOGIN);
        }

        Integer status = null;
        if (StringUtils.hasText(statusParam)) {
            try {
                status = Integer.valueOf(statusParam);
            } catch (NumberFormatException ignored) {
                // 参数非法时按“全部”处理
            }
        }

        model.addAttribute("ordersPage",
                orderService.queryUserOrders(loginUser.getId(), status, keyword, pageNo, PAGE_SIZE));
        model.addAttribute("status", status);
        model.addAttribute("keyword", keyword);

        // 顶部 mini 购物车：和商品页一样从 cart 服务取，失败就当空车
        List<OrderItemVo> cartItems = List.of();
        try {
            List<OrderItemVo> items = cartFeignClient.getCurrentCartItems();
            if (items != null) {
                cartItems = items;
            }
        } catch (Exception ignored) {
            // cart 服务暂时不可用，不影响订单页
        }
        model.addAttribute("cartItems", cartItems);
        model.addAttribute("cartCount", cartItems.size());

        return "list";
    }

    /**
     * 用户提交退货申请：订单必须是已发货或已完成，且该商品没有进行中的申请。
     */
    @PostMapping("/order/userReturn/apply")
    @ResponseBody
    public R applyReturn(@RequestParam("orderSn") String orderSn,
                         @RequestParam("skuId") Long skuId,
                         @RequestParam(value = "count", defaultValue = "1") Integer count,
                         @RequestParam(value = "reason", required = false) String reason,
                         @RequestParam(value = "description", required = false) String description) {
        try {
            orderReturnApplyService.applyReturn(orderSn, skuId, count, reason, description);
            return R.ok();
        } catch (RRException e) {
            return R.error(e.getCode(), e.getMsg());
        }
    }

    /**
     * 确认收货：只有 status=2（已发货）的订单可以操作。
     */
    @PostMapping("/order/confirmReceive")
    @ResponseBody
    public R confirmReceive(@RequestParam("orderSn") String orderSn) {
        try {
            boolean ok = orderService.confirmReceive(orderSn);
            return ok ? R.ok() : R.error("确认收货失败");
        } catch (RRException e) {
            return R.error(e.getCode(), e.getMsg());
        }
    }
}
