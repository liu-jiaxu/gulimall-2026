package com.atguigu.gulimall.seckill.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * product 返回的 sku 概要
 */
@Data
public class ProductSkuVo {

    private Long skuId;

    private String skuName;

    private BigDecimal price;

    private String skuDefaultImg;
}
