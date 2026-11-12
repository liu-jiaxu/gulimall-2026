package com.atguigu.gulimall.seckill.feign;

import com.atguigu.gulimall.common.utils.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 商品服务
 */
@FeignClient(value = "gulimall-product", fallback = ProductFeignClientFallback.class)
public interface ProductFeignClient {

    /** 查单个 sku 的名称、价格、默认图 */
    @GetMapping("/product/skuinfo/info/{skuId}")
    R getSkuInfo(@PathVariable("skuId") Long skuId);
}
