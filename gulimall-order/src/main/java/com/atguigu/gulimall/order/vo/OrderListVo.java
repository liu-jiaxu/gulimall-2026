package com.atguigu.gulimall.order.vo;

import com.atguigu.gulimall.order.entity.OrderItemEntity;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 订单列表页展示对象：一条订单 + 它的商品明细。
 */
@Data
public class OrderListVo {

    private Long id;
    private String orderSn;
    private Date createTime;
    private Integer status;
    private String statusText;
    private BigDecimal payAmount;
    private String receiverName;
    private List<OrderItemEntity> items;
}
