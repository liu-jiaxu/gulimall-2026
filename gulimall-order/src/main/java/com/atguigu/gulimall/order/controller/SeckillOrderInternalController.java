package com.atguigu.gulimall.order.controller;

import com.atguigu.gulimall.api.order.vo.SeckillOrderCreateVo;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.order.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 秒杀建单内部接口：只给 seckill 服务调用，不经网关。
 */
@RestController
@RequestMapping("/internal/order/seckill")
public class SeckillOrderInternalController {

    @Autowired
    private OrderService orderService;

    @PostMapping("/create")
    public R create(@RequestBody SeckillOrderCreateVo vo) {
        // 1. 幂等建单 + 秒杀库存转订单锁定
        R result = R.ok();
        result.putAll(orderService.createSeckillOrder(vo));
        return result;
    }

    @GetMapping("/status")
    public R status(@RequestParam("orderSn") String orderSn) {
        // 1. 查订单状态，供 seckill 对账
        R result = R.ok();
        result.putAll(orderService.getSeckillOrderStatus(orderSn));
        return result;
    }

    @PostMapping("/status/batch")
    public R batchStatus(@RequestBody List<String> orderSns) {
        // 1. 批量查，减少对账时的远程调用次数
        return R.ok().put("statusMap", orderService.batchGetOrderStatus(orderSns));
    }
}
