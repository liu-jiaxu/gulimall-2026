package com.atguigu.gulimall.api.order.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderSubmitVo {

    /** 收获地址的id **/
    private Long addrId;

    /** 支付方式 **/
    private Integer payType;
    //无需提交要购买的商品，去购物车再获取一遍，最终提交的订单数据是购物车的实时数据
    //优惠、发票

    /** 防重令牌 **/
    private String orderToken;

    /**
     * 商品总额，用于判断
     */
    private BigDecimal goodsTotal;

    /** 运费金额 **/
    private BigDecimal freightAmount;

    /** 应付价格 **/
    private BigDecimal payPrice;

    /** 订单备注 **/
    private String remarks;

    /** 运费页选中的仓库：skuId → wareId 的 JSON 字符串 */
    private String skuWareMap;

    //用户相关的信息，直接去session中取出即可
}
