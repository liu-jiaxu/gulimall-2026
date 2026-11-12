package com.atguigu.gulimall.api.order.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
public class OrderConfirmVo {

    /** 会员收获地址列表 */
    private List<MemberAddressVo> memberAddressVos;

    /** 所有选中的购物项 */
    private List<OrderItemVo> items;

    /**
     * 发票记录
     * 优惠券（会员积分）
     */
    private Integer integration;

    /** 订单总额 */
    BigDecimal total;

    /** 应付价格 */
    BigDecimal payPrice;

    BigDecimal totalWeight;

    /** 防止重复提交的令牌 */
    private String orderToken;

    private Map<Long,Boolean> stocks;

    /**
     * 商品总件数
     * @return
     */
    public Integer getCount() {
        return items != null ? items.size() : 0;
    }

    //计算订单总额
    public BigDecimal getTotal() {
        BigDecimal totalNum = BigDecimal.ZERO;
        if (items != null && !items.isEmpty()) {
            for (OrderItemVo item : items) {
                //计算当前商品的总价格
                BigDecimal itemPrice = item.getPrice().multiply(new BigDecimal(item.getCount()));
                //再计算全部商品的总价格
                totalNum = totalNum.add(itemPrice);
            }
        }
        return totalNum;
    }

    public BigDecimal getPayPrice() {
        return getTotal();
    }

    public BigDecimal getTotalWeight() {
        BigDecimal totalWeight = BigDecimal.ZERO;
        if (items != null && !items.isEmpty()) {
            for (OrderItemVo item : items) {
                BigDecimal itemWeight = item.getWeight().multiply(new BigDecimal(item.getCount()));
                totalWeight = totalWeight.add(itemWeight);
            }
        }
        return totalWeight;
    }

}
