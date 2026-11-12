package com.atguigu.gulimall.product.feign;

import com.atguigu.gulimall.common.utils.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 秒杀服务：首页秒杀区、商品详情页秒杀模式
 */
@FeignClient("gulimall-seckill")
public interface SeckillFeignClient {

    @GetMapping("/seckill/home")
    R getHome();

    @GetMapping("/seckill/sku/info")
    R getSkuInfo(@RequestParam("sessionId") Long sessionId,
                 @RequestParam("skuId") Long skuId);
}
