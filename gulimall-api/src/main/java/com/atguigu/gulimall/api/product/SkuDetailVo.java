package com.atguigu.gulimall.api.product;

import lombok.Data;

/**
 * sku + spu 的概要信息，下单时给订单明细做快照用
 *
 * <p>放在 api 模块：product 负责提供、order 负责消费。
 */
@Data
public class SkuDetailVo {

    private Long skuId;

    /** spu id */
    private Long spuId;

    /** spu 名称 */
    private String spuName;

    /** 商品图片（用 sku 的默认图） */
    private String spuPic;

    /** 品牌名 */
    private String spuBrand;

    /** 分类 id */
    private Long categoryId;

}
