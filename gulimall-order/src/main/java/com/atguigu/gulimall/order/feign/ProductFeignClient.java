package com.atguigu.gulimall.order.feign;

import com.atguigu.gulimall.api.product.SkuDetailVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 商品服务（gulimall-product）远程调用
 */
@FeignClient("gulimall-product")
public interface ProductFeignClient {

    /**
     * 批量查询 sku 的 spu 概要信息（spu 名称、品牌、分类），用于订单明细快照
     */
    @PostMapping("/product/skuinfo/details")
    List<SkuDetailVo> getSkuDetails(@RequestBody List<Long> skuIds);

}
