package com.atguigu.gulimall.seckill.mq.message;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 秒杀订单异步建单消息
 */
@Data
public class SeckillOrderCreateMessage {

    private String orderSn;

    private String requestId;

    private Long memberId;

    private String memberUsername;

    private Long sessionId;

    private Long skuId;

    private String skuName;

    private String skuPic;

    private Long wareId;

    private Integer quantity;

    private BigDecimal seckillPrice;

    private Long addrId;

    private Integer payType;
}
