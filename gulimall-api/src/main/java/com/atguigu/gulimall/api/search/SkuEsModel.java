package com.atguigu.gulimall.api.search;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * @ClassName: SkuEsModel
 * @Package: com.atguigu.gulimall.api.to.es
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/16 - 10:00
 * @Version: v1.0
 */
@Data
public class SkuEsModel {
    private Long skuId;
    private Long spuId;
    private String skuTitle;
    private BigDecimal skuPrice;
    private String skuImg;
    private Long saleCount;
    private boolean hasStock;
    private Long hotScore;
    private Long brandId;
    private Long catelogId;
    private String brandName;
    private String brandImg;
    private String catelogName;
    private List<Attr> attrs;
}
