package com.atguigu.gulimall.api.order.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * seckill 消费建单消息后，调 order 内部接口创建秒杀订单。
 */
@Data
public class SeckillOrderCreateVo {

    /** seckill 预生成的订单号，全链路幂等键 */
    private String orderSn;

    private String requestId;

    private Long memberId;

    private String memberUsername;

    private Long sessionId;

    private Long skuId;

    private String skuName;

    private String skuPic;

    /** 关联时锁定的仓库 id */
    private Long wareId;

    private Integer quantity;

    private BigDecimal seckillPrice;

    private Long addrId;

    private Integer payType;
}
