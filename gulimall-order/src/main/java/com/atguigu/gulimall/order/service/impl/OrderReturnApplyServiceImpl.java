package com.atguigu.gulimall.order.service.impl;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.order.dao.OrderDao;
import com.atguigu.gulimall.order.dao.OrderItemDao;
import com.atguigu.gulimall.order.dao.OrderReturnApplyDao;
import com.atguigu.gulimall.order.entity.OrderEntity;
import com.atguigu.gulimall.order.entity.OrderItemEntity;
import com.atguigu.gulimall.order.entity.OrderReturnApplyEntity;
import com.atguigu.gulimall.order.entity.RefundInfoEntity;
import com.atguigu.gulimall.order.feign.WareFeignClient;
import com.atguigu.gulimall.order.payment.AlipayRefundService;
import com.atguigu.gulimall.order.service.OrderReturnApplyService;
import com.atguigu.gulimall.order.service.RefundInfoService;
import com.atguigu.gulimall.order.thread.MemberResponseVoThreadLocal;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.Query;


@Slf4j
@Service("orderReturnApplyService")
public class OrderReturnApplyServiceImpl extends ServiceImpl<OrderReturnApplyDao, OrderReturnApplyEntity> implements OrderReturnApplyService {

    /** 订单状态：2-已发货（待收货） */
    private static final int ORDER_STATUS_SHIPPED = 2;
    /** 订单状态：3-已完成 */
    private static final int ORDER_STATUS_COMPLETED = 3;

    /** 退货申请状态：0-待处理、1-退货中、2-已完成、3-已拒绝 */
    private static final int RETURN_STATUS_COMPLETED = 2;

    @Autowired
    private OrderDao orderDao;
    @Autowired
    private OrderItemDao orderItemDao;
    @Autowired
    private AlipayRefundService alipayRefundService;
    @Autowired
    private WareFeignClient wareFeignClient;
    @Autowired
    private RefundInfoService refundInfoService;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        QueryWrapper<OrderReturnApplyEntity> wrapper = new QueryWrapper<>();

        Object key = params.get("key");
        if (key instanceof String text && StringUtils.hasText(text)) {
            wrapper.and(w -> w.like("order_sn", text)
                    .or().like("sku_name", text)
                    .or().like("member_username", text));
        }
        Object status = params.get("status");
        if (status != null && StringUtils.hasText(status.toString())) {
            wrapper.eq("status", status.toString());
        }
        Object orderSn = params.get("orderSn");
        if (orderSn instanceof String sn && StringUtils.hasText(sn)) {
            wrapper.eq("order_sn", sn);
        }
        wrapper.orderByDesc("create_time");

        IPage<OrderReturnApplyEntity> page = this.page(
                new Query<OrderReturnApplyEntity>().getPage(params),
                wrapper
        );

        return new PageUtils(page);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderReturnApplyEntity applyReturn(String orderSn, Long skuId, Integer count,
                                              String reason, String description) {
        MemberResponseVo loginUser = MemberResponseVoThreadLocal.get();
        if (loginUser == null) {
            throw new RRException(BizCodeEnum.ORDER_NOT_LOGIN);
        }

        OrderEntity order = orderDao.selectOne(new QueryWrapper<OrderEntity>()
                .eq("order_sn", orderSn)
                .last("LIMIT 1"));
        if (order == null || !loginUser.getId().equals(order.getMemberId())) {
            throw new RRException(BizCodeEnum.ORDER_NOT_FOUND_OR_NOT_OWNER);
        }
        if (!Integer.valueOf(ORDER_STATUS_SHIPPED).equals(order.getStatus())
                && !Integer.valueOf(ORDER_STATUS_COMPLETED).equals(order.getStatus())) {
            throw new RRException(BizCodeEnum.ORDER_RETURN_NOT_ALLOWED);
        }

        OrderItemEntity item = orderItemDao.selectOne(new QueryWrapper<OrderItemEntity>()
                .eq("order_id", order.getId())
                .eq("sku_id", skuId)
                .last("LIMIT 1"));
        if (item == null) {
            throw new RRException(BizCodeEnum.ORDER_NOT_FOUND_OR_NOT_OWNER);
        }

        int buyCount = item.getSkuQuantity() == null ? 0 : item.getSkuQuantity();
        if (count == null || count <= 0 || count > buyCount) {
            throw new RRException(BizCodeEnum.ORDER_RETURN_COUNT_INVALID);
        }

        // 同一订单同一商品只允许一条未完成/已完成的申请，避免重复退款
        Long exists = baseMapper.selectCount(new QueryWrapper<OrderReturnApplyEntity>()
                .eq("order_id", order.getId())
                .eq("sku_id", skuId)
                .in("status", 0, 1, 2));
        if (exists != null && exists > 0) {
            throw new RRException(BizCodeEnum.ORDER_RETURN_ALREADY_APPLIED);
        }

        ReturnPrice price = calculateReturnPrice(order, item, count);
        BigDecimal realUnitPrice = price.unitPrice();
        BigDecimal returnAmount = price.amount();

        OrderReturnApplyEntity apply = new OrderReturnApplyEntity();
        apply.setOrderId(order.getId());
        apply.setSkuId(skuId);
        apply.setOrderSn(orderSn);
        apply.setCreateTime(new Date());
        apply.setMemberUsername(loginUser.getUsername());
        apply.setReturnAmount(returnAmount);
        apply.setReturnName(order.getReceiverName());
        apply.setReturnPhone(order.getReceiverPhone());
        apply.setStatus(0);
        apply.setSkuImg(item.getSkuPic());
        apply.setSkuName(item.getSkuName());
        apply.setSkuBrand(item.getSpuBrand());
        apply.setSkuAttrsVals(item.getSkuAttrsVals());
        apply.setSkuCount(count);
        apply.setSkuPrice(item.getSkuPrice());
        apply.setSkuRealPrice(realUnitPrice);
        apply.setReason(reason);
        apply.setDescription述(description);
        apply.setCompanyAddress("谷粒商城售后中心");

        save(apply);
        log.info("[return-apply] 用户 {} 提交退货申请：applyId={}, orderSn={}, skuId={}, count={}, amount={}",
                loginUser.getUsername(), apply.getId(), orderSn, skuId, count, returnAmount);
        return apply;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(OrderReturnApplyEntity form) {
        if (form == null || form.getId() == null) {
            throw new RRException(BizCodeEnum.ORDER_RETURN_NOT_FOUND);
        }
        OrderReturnApplyEntity existing = getById(form.getId());
        if (existing == null) {
            throw new RRException(BizCodeEnum.ORDER_RETURN_NOT_FOUND);
        }

        if (Integer.valueOf(RETURN_STATUS_COMPLETED).equals(existing.getStatus())) {
            // 已完成代表钱已退、库存已回补，任何修改都不允许，避免二次退款/重复加库存
            throw new RRException(BizCodeEnum.ORDER_RETURN_ALREADY_COMPLETED);
        }

        boolean complete = Integer.valueOf(RETURN_STATUS_COMPLETED).equals(form.getStatus());

        if (complete) {
            completeReturn(existing, form);
            return;
        }

        // 未完成：只允许改处理/收货相关字段，金额、商品、订单信息一律不可改
        UpdateWrapper<OrderReturnApplyEntity> update = baseUpdateWrapper(existing.getId(), form);
        if (form.getStatus() != null) {
            update.set("status", form.getStatus())
                    .set("handle_time", new Date());
            if (!StringUtils.hasText(form.getHandleMan())) {
                update.set("handle_man", "admin");
            }
        }
        // 没有任何可更新字段时直接返回，避免生成 "UPDATE ... WHERE id=?" 这种非法 SQL
        if (update.getSqlSet() == null || update.getSqlSet().isBlank()) {
            log.info("[return-handle] 退货单 {} 没有需要修改的字段", existing.getId());
            return;
        }

        // entity 传 null：只按 wrapper 里的白名单字段更新，
        // 避免把 existing 的整体状态重新写回去
        baseMapper.update(null, update);
    }

    /**
     * 退货单完结：
     * <ol>
     *   <li>按退货申请金额原路退款（支付宝 out_request_no 幂等）</li>
     *   <li>按退货数量回补原发货仓库库存（ware 侧 3→4 幂等）</li>
     *   <li>把退货单状态条件更新为已完成</li>
     * </ol>
     * 退款或回补失败都会抛异常，状态保持不变，管理员可重试；
     * 已成功的退款/回补操作本身是幂等的，重试不会重复退钱或重复加库存。
     */
    private void completeReturn(OrderReturnApplyEntity existing, OrderReturnApplyEntity form) {
        OrderEntity order = orderDao.selectById(existing.getOrderId());
        if (order == null) {
            throw new RRException(BizCodeEnum.ORDER_NOT_FOUND_OR_NOT_OWNER);
        }

        // 老退货单可能是在金额算法修正前提交的（例如按商品原价 5500 算，
        // 而订单实付只有 5496.26，支付宝会报退款金额无效）。
        // 处理时按订单实付重新算一次，并同步回退货单。
        OrderItemEntity item = orderItemDao.selectOne(new QueryWrapper<OrderItemEntity>()
                .eq("order_id", existing.getOrderId())
                .eq("sku_id", existing.getSkuId())
                .last("LIMIT 1"));
        int returnCount = existing.getSkuCount() == null ? 0 : existing.getSkuCount();
        if (item == null || returnCount <= 0) {
            throw new RRException(BizCodeEnum.ORDER_RETURN_COUNT_INVALID);
        }
        ReturnPrice price = calculateReturnPrice(order, item, returnCount);
        if (existing.getReturnAmount() == null
                || price.amount().compareTo(existing.getReturnAmount()) != 0) {
            log.info("[return-handle] 修正退货金额：applyId={}, old={}, new={}",
                    existing.getId(), existing.getReturnAmount(), price.amount());
            existing.setReturnAmount(price.amount());
            existing.setSkuRealPrice(price.unitPrice());
            baseMapper.update(null, new UpdateWrapper<OrderReturnApplyEntity>()
                    .set("return_amount", price.amount())
                    .set("sku_real_price", price.unitPrice())
                    .eq("id", existing.getId()));
        }

        boolean refunded = alipayRefundService.refundReturn(existing, order);
        if (!refunded) {
            throw new RRException(refundFailureMessage(existing),
                    BizCodeEnum.ORDER_RETURN_REFUND_FAILED.getCode());
        }

        R stockResult = wareFeignClient.returnStock(existing.getOrderSn(),
                existing.getSkuId(), existing.getSkuCount());
        if (stockResult == null || stockResult.getCode() == null || stockResult.getCode() != 0) {
            String msg = stockResult == null ? "库存回补失败" : (String) stockResult.get("msg");
            throw new RRException("退款已成功，但" + msg + "，请重试",
                    BizCodeEnum.ORDER_RETURN_REFUND_FAILED.getCode());
        }

        UpdateWrapper<OrderReturnApplyEntity> update =
                baseUpdateWrapper(existing.getId(), form)
                        .set("status", RETURN_STATUS_COMPLETED)
                        .set("handle_time", new Date());
        int updated = baseMapper.update(null, update
                .eq("id", existing.getId())
                .ne("status", RETURN_STATUS_COMPLETED));
        if (updated == 0) {
            // 并发下已被其它管理员处理：退款和回补都是幂等的，这里按成功返回
            log.info("[return-handle] 退货单 {} 已被其它请求完成", existing.getId());
        }
        log.info("[return-handle] 退货单完成：applyId={}, orderSn={}, skuId={}, count={}",
                existing.getId(), existing.getOrderSn(), existing.getSkuId(),
                existing.getSkuCount());
    }

    /**
     * 管理员可修改的字段白名单。id 由路径/表单单独指定，不在这里 set。
     */
    private UpdateWrapper<OrderReturnApplyEntity> baseUpdateWrapper(Long id,
                                                                    OrderReturnApplyEntity form) {
        UpdateWrapper<OrderReturnApplyEntity> update = new UpdateWrapper<>();
        update.eq("id", id);
        if (form.getHandleNote() != null) {
            update.set("handle_note", form.getHandleNote());
        }
        if (form.getHandleMan() != null) {
            update.set("handle_man", form.getHandleMan());
        }
        if (form.getReceiveMan() != null) {
            update.set("receive_man", form.getReceiveMan());
        }
        if (form.getReceiveTime() != null) {
            update.set("receive_time", form.getReceiveTime());
        }
        if (form.getReceiveNote() != null) {
            update.set("receive_note", form.getReceiveNote());
        }
        if (form.getReceivePhone() != null) {
            update.set("receive_phone", form.getReceivePhone());
        }
        if (form.getCompanyAddress() != null) {
            update.set("company_address", form.getCompanyAddress());
        }
        return update;
    }

    /**
     * 把支付宝返回的 sub_msg 翻译成后台能看懂的提示。
     * refund_status=3 表示交易不存在/状态已终结，重试也没用，提示人工处理。
     */
    private String refundFailureMessage(OrderReturnApplyEntity apply) {
        String refundSn = apply.getOrderSn() + "-R" + apply.getId();
        RefundInfoEntity refund = refundInfoService.getOne(
                new QueryWrapper<RefundInfoEntity>()
                        .eq("refund_sn", refundSn)
                        .last("LIMIT 1"), false);
        if (refund == null) {
            return "退款失败，已记录待重试";
        }

        String reason = "";
        String content = refund.getRefundContent();
        if (content != null) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\"sub_msg\":\"([^\"]+)\"").matcher(content);
            if (matcher.find()) {
                reason = matcher.group(1);
            } else {
                matcher = java.util.regex.Pattern
                        .compile("\"sub_code\":\"([^\"]+)\"").matcher(content);
                if (matcher.find()) {
                    reason = matcher.group(1);
                }
            }
        }

        String suffix = reason.isBlank() ? "" : "：" + reason;
        if (Integer.valueOf(AlipayRefundService.REFUND_TERMINAL_FAILURE)
                .equals(refund.getRefundStatus())) {
            return "退款失败" + suffix + "。该笔交易无法自动退款，请人工核对支付宝交易";
        }
        return "退款失败" + suffix + "，已记录待重试";
    }

    /**
     * 计算退货单价和退款金额。
     *
     * <p>不能用商品原价直接退：订单存在订单级优惠/运费，实际支付金额可能低于商品原价。
     * 正确口径：
     * <ol>
     *   <li>订单实付商品金额 = pay_amount - freight_amount；</li>
     *   <li>按各商品原价（real_amount，缺失时用 sku_price * 数量）占比分摊；</li>
     *   <li>再除以购买数量，乘以退货数量。</li>
     * </ol>
     * 这样算出来的金额一定不超过支付宝交易金额。
     */
    private ReturnPrice calculateReturnPrice(OrderEntity order, OrderItemEntity item, int count) {
        List<OrderItemEntity> allItems = orderItemDao.selectList(
                new QueryWrapper<OrderItemEntity>().eq("order_id", order.getId()));

        BigDecimal goodsPaid = order.getPayAmount() == null
                ? BigDecimal.ZERO : order.getPayAmount();
        if (order.getFreightAmount() != null) {
            goodsPaid = goodsPaid.subtract(order.getFreightAmount());
        }
        if (goodsPaid.signum() < 0) {
            goodsPaid = BigDecimal.ZERO;
        }

        BigDecimal totalWeight = allItems.stream()
                .map(this::itemWeight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.signum() <= 0) {
            totalWeight = order.getTotalAmount() == null
                    ? BigDecimal.ZERO : order.getTotalAmount();
        }

        BigDecimal itemWeight = itemWeight(item);
        BigDecimal itemPaid = totalWeight.signum() <= 0
                ? BigDecimal.ZERO
                : itemWeight.multiply(goodsPaid)
                        .divide(totalWeight, 4, RoundingMode.HALF_UP);

        int buyCount = item.getSkuQuantity() == null ? 0 : item.getSkuQuantity();
        BigDecimal unitPrice = buyCount <= 0
                ? BigDecimal.ZERO
                : itemPaid.divide(BigDecimal.valueOf(buyCount), 4, RoundingMode.HALF_UP);
        BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(count))
                .setScale(4, RoundingMode.HALF_UP);

        // 兜底：绝不能超过订单实付总额（支付宝交易金额）
        if (order.getPayAmount() != null
                && amount.compareTo(order.getPayAmount()) > 0) {
            amount = order.getPayAmount().setScale(4, RoundingMode.HALF_UP);
        }
        if (amount.signum() <= 0) {
            throw new RRException("订单实付金额为 0，无法退款",
                    BizCodeEnum.ORDER_RETURN_REFUND_FAILED.getCode());
        }
        return new ReturnPrice(unitPrice, amount);
    }

    /** 商品分摊权重：优先 real_amount，缺失时退化为 sku_price * 数量 */
    private BigDecimal itemWeight(OrderItemEntity item) {
        BigDecimal real = item.getRealAmount();
        if (real != null && real.signum() > 0) {
            return real;
        }
        BigDecimal price = item.getSkuPrice() == null ? BigDecimal.ZERO : item.getSkuPrice();
        int qty = item.getSkuQuantity() == null ? 0 : item.getSkuQuantity();
        return price.multiply(BigDecimal.valueOf(qty));
    }

    private record ReturnPrice(BigDecimal unitPrice, BigDecimal amount) {
    }
}
