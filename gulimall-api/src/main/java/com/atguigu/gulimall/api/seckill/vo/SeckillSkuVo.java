package com.atguigu.gulimall.api.seckill.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 秒杀商品信息：跨服务传输用，字段和 Redis hash 里的 JSON 保持一致。
 */
@Data
public class SeckillSkuVo {

    private Long promotionId;

    private Long promotionSessionId;

    private Long skuId;

    private Long wareId;

    private BigDecimal seckillPrice;

    private Integer seckillCount;

    private Integer seckillLimit;

    private Integer seckillSort;

    /** 场次开始时间戳（毫秒） */
    private Long startTime;

    /** 场次结束时间戳（毫秒） */
    private Long endTime;

    private String randomCode;

    private String skuName;

    private String skuImg;

    private BigDecimal skuPrice;
}
