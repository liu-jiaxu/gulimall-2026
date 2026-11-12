package com.atguigu.gulimall.order.feign;

import com.atguigu.gulimall.common.utils.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 秒杀服务：订单取消/超时后通知它归还秒杀库存。
 */
@FeignClient(value = "gulimall-seckill", fallback = SeckillFeignClientFallback.class)
public interface SeckillFeignClient {

    @PostMapping("/internal/seckill/order/release")
    R releaseSeckillOrder(@RequestParam("orderSn") String orderSn,
                          @RequestParam("reason") String reason);

    /**
     * 支付成功：实时把秒杀单置为已支付，失败由对账任务兜底。
     */
    @PostMapping("/internal/seckill/order/paid")
    R markSeckillOrderPaid(@RequestParam("orderSn") String orderSn);
}
