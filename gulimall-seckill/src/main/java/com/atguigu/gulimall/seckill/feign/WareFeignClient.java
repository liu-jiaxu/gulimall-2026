package com.atguigu.gulimall.seckill.feign;

import com.atguigu.gulimall.common.utils.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 仓储服务：取消/超时后释放秒杀订单占用的库存。
 */
@FeignClient("gulimall-ware")
public interface WareFeignClient {

    /**
     * @param mode SECKILL_POOL 回秒杀池，NORMAL_POOL 回普通可售池
     */
    @PostMapping("/ware/waresku/seckill/order/release")
    R releaseSeckillOrderStock(@RequestParam("orderSn") String orderSn,
                               @RequestParam("mode") String mode);
}
