package com.atguigu.gulimall.coupon.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 后台查看场次关联商品用的只读视图
 */
@Data
public class SeckillSkuRelationVo {

    private Long id;

    /** 所属场次 */
    private Long sessionId;

    private String sessionName;

    private Long skuId;

    /** 商品名称，来自 product 服务 */
    private String skuName;

    /** 商品图片 */
    private String skuImg;

    private Long wareId;

    private BigDecimal seckillPrice;

    private Integer seckillCount;

    private Integer seckillLimit;

    private Integer seckillSort;
}
