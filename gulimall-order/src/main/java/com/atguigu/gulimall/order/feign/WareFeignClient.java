package com.atguigu.gulimall.order.feign;

import com.atguigu.gulimall.api.ware.vo.FareRequestVo;
import com.atguigu.gulimall.api.ware.vo.SeckillOrderStockLockVo;
import com.atguigu.gulimall.api.ware.vo.StockLockVo;
import com.atguigu.gulimall.api.ware.vo.WareStockCheckVo;
import com.atguigu.gulimall.common.utils.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 仓储服务（gulimall-ware）远程调用
 */
@FeignClient("gulimall-ware")
public interface WareFeignClient {

    /**
     * 计算运费与时间
     *
     * @param fareRequestVo 收货地址 + 商品清单（只含算运费要用的字段）
     */
    @PostMapping("/ware/wareinfo/fare")
    R getFare(@RequestBody FareRequestVo fareRequestVo);

    /**
     * 查询一组 sku 是否有货（任意一个仓库有货就算有）
     */
    @PostMapping("/ware/waresku/hasStock")
    Map<Long, Boolean> hasStock(@RequestBody List<Long> skuIds);

    /**
     * 按指定仓库校验库存：仓库可用库存 >= 购买数量才算有货。
     */
    @PostMapping("/ware/waresku/hasStockByWare")
    Map<Long, Boolean> hasStockByWare(@RequestBody WareStockCheckVo vo);

    /**
     * 查询一组 sku 的可用库存数量（所有仓库求和），用于给 Redis 库存预热
     */
    @PostMapping("/ware/waresku/stockNums")
    Map<Long, Long> getStockNums(@RequestBody List<Long> skuIds);

    /**
     * 下单锁定库存（库存锁由调用方 order 持有，ware 侧不再加锁）
     *
     * @param stockLockVo 订单号 + skuId → 锁定数量
     */
    @PostMapping("/ware/waresku/lockStock")
    R lockStock(@RequestBody StockLockVo stockLockVo);

    /**
     * 按订单号释放锁定库存（用户取消 / 订单关闭时调用）。
     * 找不到工作单或已经释放过都返回成功，保证幂等。
     */
    @PostMapping("/ware/waresku/releaseByOrderSn")
    R releaseStockByOrderSn(@RequestParam("orderSn") String orderSn);

    /**
     * 秒杀建单：把该场次锁定的 seckill_stock_locked 转成订单占用的 stock_locked。
     * 不发送普通库存解锁消息，取消/超时由 seckill 统一处理。
     */
    @PostMapping("/ware/waresku/seckill/order/lock")
    R lockSeckillOrderStock(@RequestBody SeckillOrderStockLockVo vo);

    /**
     * 执行顺序 5：订单已支付后，通知 ware 把“锁定库存”转成“真实扣减”。
     *
     * <p>ware 侧会把 wms_ware_order_task.task_status 从 1（已锁定）改成 3（已扣减），
     * 同时扣减 wms_ware_sku 的 stock 和 stock_locked。
     */
    @PostMapping("/ware/waresku/markPaid")
    R markPaid(@RequestParam("orderSn") String orderSn);

    /**
     * 售后退货完成：把退货数量回补到原发货仓库（ware 侧保证幂等）。
     */
    @PostMapping("/ware/waresku/returnStock")
    R returnStock(@RequestParam("orderSn") String orderSn,
                  @RequestParam("skuId") Long skuId,
                  @RequestParam("num") Integer num);

}
