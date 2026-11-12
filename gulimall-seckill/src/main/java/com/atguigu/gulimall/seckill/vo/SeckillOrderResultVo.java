package com.atguigu.gulimall.seckill.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 秒杀抢购结果
 */
@Data
public class SeckillOrderResultVo {

    private String orderSn;

    /** 0待建单 1已建单 2已释放 3建单失败 4已支付 */
    private Integer status;

    private String statusText;

    private Long orderId;

    private BigDecimal payAmount;

    /** 1回秒杀池 2回普通池 */
    private Integer releaseMode;

    private String failReason;
}
