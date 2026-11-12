package com.atguigu.gulimall.product.vo;

import com.atguigu.gulimall.api.ware.vo.SkuShippingFromVo;
import com.atguigu.gulimall.product.entity.CategoryEntity;
import com.atguigu.gulimall.product.entity.SkuImagesEntity;
import com.atguigu.gulimall.product.entity.SkuInfoEntity;
import com.atguigu.gulimall.product.entity.SkuSaleAttrValueEntity;
import com.atguigu.gulimall.product.entity.SpuInfoDescEntity;
import lombok.Data;
import lombok.ToString;

import java.util.List;

@ToString
@Data
public class SkuItemVo {

    //1、sku基本信息的获取  pms_sku_info
    private SkuInfoEntity info;

    //2、sku的图片信息    pms_sku_images
    private List<SkuImagesEntity> images;

    //3、获取spu的销售属性组合
    private List<SkuItemSaleAttrVo> saleAttr;

    //4、获取spu的介绍
    private SpuInfoDescEntity desc;

    //5、获取spu的规格参数信息
    private List<SpuItemAttrGroupVo> groupAttrs;

    //6、商品是否有货
    private boolean hasStock;

    //7、秒杀商品的优惠信息
    private SeckillSkuVo seckillSkuVo;

    //8、当前 SKU 的三级分类路径（一级 -> 二级 -> 三级）
    private List<CategoryEntity> categoryPath;

    //9、当前 SKU 自己的销售属性（颜色/状态/价格等，直接展示）
    private List<SkuSaleAttrValueEntity> skuSaleAttrs;

    //10、可发货仓库及可用库存
    private List<SkuShippingFromVo> shippingFroms;

}
