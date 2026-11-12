package com.atguigu.gulimall.coupon.feign;

import com.atguigu.gulimall.api.product.SkuDetailVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 商品服务：后台查看关联商品时补名称/图片
 */
@FeignClient("gulimall-product")
public interface ProductFeignClient {

    @PostMapping("/product/skuinfo/details")
    List<SkuDetailVo> getSkuDetails(@RequestBody List<Long> skuIds);
}
