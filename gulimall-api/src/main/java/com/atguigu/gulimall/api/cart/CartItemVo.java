package com.atguigu.gulimall.api.cart;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CartItemVo {

    private Long skuId;

    private Boolean check = true;

    private String title;

    private String image;

    /**
     * 商品套餐属性
     */
    private List<String> skuAttrValues;

    private BigDecimal price;

    private Integer count;

    /**
     * 该 sku 的实时库存，用来限制页面上的「+」和手动输入的数量
     * <p>不是购物车数据本身，每次渲染购物车页时现查一次
     */
    private Long stock = Long.MAX_VALUE;

    /**
     * 商品重量
     */
    private BigDecimal weight;

    private BigDecimal totalPrice;

    /**
     * 计算当前购物项总价
     * @return
     */
    public BigDecimal getTotalPrice() {
        return this.price != null ? this.price.multiply(new BigDecimal(this.count)) : BigDecimal.ZERO;
    }

}
