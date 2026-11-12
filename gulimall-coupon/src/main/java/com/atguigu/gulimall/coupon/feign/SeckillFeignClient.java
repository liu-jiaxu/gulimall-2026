package com.atguigu.gulimall.coupon.feign;

import com.atguigu.gulimall.common.utils.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 秒杀服务（gulimall-seckill）远程调用
 */
@FeignClient("gulimall-seckill")
public interface SeckillFeignClient {

    /**
     * 关联新增/修改后，让秒杀服务按数据库最新数据重建商品信息缓存。
     */
    @PostMapping("/seckill/refresh")
    R refreshSessionSku(@RequestParam("sessionId") Long sessionId,
                        @RequestParam("skuId") Long skuId);
}
