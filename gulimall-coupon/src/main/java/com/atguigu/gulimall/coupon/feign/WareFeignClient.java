package com.atguigu.gulimall.coupon.feign;

import com.atguigu.gulimall.common.utils.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 仓储服务（gulimall-ware）远程调用
 */
@FeignClient("gulimall-ware")
public interface WareFeignClient {

    /**
     * 关联秒杀商品时锁定库存：只增加 seckill_stock_locked，
     * 库存不够返回 code=500。
     */
    @PostMapping("/ware/waresku/seckill/lock")
    R lockSeckillStock(@RequestParam("skuId") Long skuId,
                       @RequestParam("quantity") Integer quantity,
                       @RequestParam("operationId") String operationId,
                       @RequestParam(value = "wareId", required = false) Long wareId);

    /**
     * 修改/删除关联时释放秒杀锁定库存。
     */
    @PostMapping("/ware/waresku/seckill/release")
    R releaseSeckillStock(@RequestParam("skuId") Long skuId,
                          @RequestParam("wareId") Long wareId,
                          @RequestParam("quantity") Integer quantity,
                          @RequestParam("operationId") String operationId);

    /**
     * 全局事务结束后刷新普通可售库存缓存。
     */
    @PostMapping("/ware/waresku/seckill/refresh")
    R refreshStock(@RequestParam("skuId") Long skuId);

    /**
     * 结算/删除场次时释放该 SKU+仓库当前剩余的全部秒杀锁定。
     * ware 以数据库 seckill_stock_locked 为准，置 0 并返回释放量。
     */
    @PostMapping("/ware/waresku/seckill/releaseRemaining")
    R releaseRemainingSeckillStock(@RequestParam("sessionId") Long sessionId,
                                   @RequestParam("skuId") Long skuId,
                                   @RequestParam("wareId") Long wareId,
                                   @RequestParam("operationId") String operationId);
}
