package com.atguigu.gulimall.api.cart;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CartVo {

    /**
     * 购物车子项信息
     */
    List<CartItemVo> items;

    /**
     * 商品数量
     */
    private Integer countNum;

    /**
     * 商品类型数量
     */
    private Integer countType;

    /**
     * 商品总价
     */
    private BigDecimal totalAmount;

    /**
     * 减免价格
     */
    private BigDecimal reduce = new BigDecimal("0.00");

    public Integer getCountNum() {
        int count = 0;
        if (items != null && !items.isEmpty()) {
            for (CartItemVo item : items) {
                count += item.getCount();
            }
        }
        return count;
    }

    public Integer getCountType() {
        return items != null ? items.size() : 0;
    }

    /**
     * 已选商品数量：只统计打了勾的购物项
     * <p>和 getCountNum（全部商品）区分开，底栏那个「已选数量」用这个
     */
    public Integer getCheckedCountNum() {
        int count = 0;
        if (items != null) {
            for (CartItemVo item : items) {
                // check 可能是 null（老数据 / 反序列化出来的），null 一律当作没勾
                if (Boolean.TRUE.equals(item.getCheck())) {
                    count += item.getCount() == null ? 0 : item.getCount();
                }
            }
        }
        return count;
    }

    /**
     * 已选商品类型数量：只统计打了勾的购物项
     */
    public Integer getCheckedCountType() {
        int count = 0;
        if (items != null) {
            for (CartItemVo item : items) {
                if (Boolean.TRUE.equals(item.getCheck())) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 重写计算属性
     * 需要计算的属性，必须重写他的get方法，保证每次获取属性都会重新计算
     *
     * @return
     */
    public BigDecimal getTotalAmount() {
        BigDecimal amount = new BigDecimal("0");
        // 计算购物项总价
        if (items != null && !items.isEmpty()) {
            for (CartItemVo cartItem : items) {
                if (cartItem.getCheck()) {
                    amount = amount.add(cartItem.getTotalPrice());
                }
            }
        }
        // 计算优惠后的价格
        return amount.subtract(getReduce());
    }

}
