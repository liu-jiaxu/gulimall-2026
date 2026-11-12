package com.atguigu.gulimall.seckill.vo;

import lombok.Data;

/**
 * 秒杀抢购请求
 */
@Data
public class SeckillOrderSubmitVo {

    /** 客户端生成的请求 id，重复提交时返回同一订单 */
    private String requestId;

    private Long sessionId;

    private Long skuId;

    private Integer quantity;

    /** 收货地址 id */
    private Long addrId;

    /** 支付方式，默认支付宝 */
    private Integer payType;

    /** 上架时写进商品 hash 的随机码 */
    private String randomCode;
}
