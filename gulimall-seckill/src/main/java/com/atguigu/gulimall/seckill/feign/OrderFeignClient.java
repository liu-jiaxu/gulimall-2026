package com.atguigu.gulimall.seckill.feign;

import com.atguigu.gulimall.api.order.vo.SeckillOrderCreateVo;
import com.atguigu.gulimall.common.utils.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 订单服务：秒杀消费端在这里建单、对账时查订单状态。
 */
@FeignClient("gulimall-order")
public interface OrderFeignClient {

    @PostMapping("/internal/order/seckill/create")
    R createSeckillOrder(@RequestBody SeckillOrderCreateVo vo);

    @GetMapping("/internal/order/seckill/status")
    R getSeckillOrderStatus(@RequestParam("orderSn") String orderSn);

    @PostMapping("/internal/order/seckill/status/batch")
    R batchGetOrderStatus(@RequestBody List<String> orderSns);
}
