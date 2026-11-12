package com.atguigu.gulimall.seckill.controller;

import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.seckill.service.SeckillOrderService;
import com.atguigu.gulimall.seckill.vo.SeckillOrderResultVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀订单内部接口：order 取消/超时后调用，不走网关。
 */
@RestController
@RequestMapping("/internal/seckill/order")
public class SeckillOrderInternalController {

    @Autowired
    private SeckillOrderService seckillOrderService;

    @PostMapping("/release")
    public R release(@RequestParam("orderSn") String orderSn,
                     @RequestParam(value = "reason", required = false) String reason) {
        // 1. 普通订单没有秒杀预扣记录，直接幂等返回
        SeckillOrderResultVo result = seckillOrderService.releaseByOrderSn(orderSn, reason);
        // 2. 没有预扣记录说明是普通订单，直接返回成功，保证取消/超时重复回调也不会报错
        if (result == null) {
            return R.ok();
        }
        // 3. 有预扣记录就把释放后的秒杀单状态带回给订单服务，便于核对库存已经退回
        return R.ok().put("seckillOrder", result);
    }

    /**
     * 支付成功：order 支付回调实时调用，失败仍由对账任务兜底
     */
    @PostMapping("/paid")
    public R paid(@RequestParam("orderSn") String orderSn) {
        // 1. 把对应秒杀预扣记录标记为已支付，防止超时任务再把这份库存释放掉
        seckillOrderService.markPaid(orderSn);
        // 2. 返回成功，订单服务收到后即可结束这次支付回调
        return R.ok();
    }
}
