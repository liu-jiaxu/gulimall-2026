package com.atguigu.gulimall.ware.controller;

import com.atguigu.gulimall.api.ware.vo.StockLockVo;
import com.atguigu.gulimall.api.ware.vo.WareStockCheckVo;
import com.atguigu.gulimall.common.constant.WareConstant;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.ware.entity.WareOrderTaskEntity;
import com.atguigu.gulimall.ware.entity.WareSkuEntity;
import com.atguigu.gulimall.ware.service.StockReleaseExecutor;
import com.atguigu.gulimall.ware.service.WareOrderTaskService;
import com.atguigu.gulimall.ware.service.WareSkuService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * 商品库存
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 20:18:03
 */
@Slf4j
@RestController
@RequestMapping("ware/waresku")
public class WareSkuController {
    @Autowired
    private WareSkuService wareSkuService;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private WareOrderTaskService wareOrderTaskService;
    @Autowired
    private StockReleaseExecutor stockReleaseExecutor;

    /**
     * 列表
     */
    @RequestMapping("/list")
    @RequiresPermissions("ware:waresku:list")
    public R list(@RequestParam Map<String, Object> params) {
        PageUtils page = wareSkuService.queryPage(params);

        return R.ok().put("page", page);
    }


    /**
     * 信息
     */
    @RequestMapping("/info/{id}")
    @RequiresPermissions("ware:waresku:info")
    public R info(@PathVariable("id") Long id) {
        WareSkuEntity wareSku = wareSkuService.getById(id);

        return R.ok().put("wareSku", wareSku);
    }

    /**
     * 保存
     */
    @RequestMapping("/save")
    @RequiresPermissions("ware:waresku:save")
    public R save(@RequestBody WareSkuEntity wareSku) {
        executeWithStockLock(() -> {
            wareSkuService.save(wareSku);
            // 库存变了，立刻把最新的可用库存写回 Redis
            wareSkuService.refreshStockCache(wareSku.getSkuId());
        });

        return R.ok();
    }

    /**
     * 修改
     */
    @RequestMapping("/update")
    @RequiresPermissions("ware:waresku:update")
    public R update(@RequestBody WareSkuEntity wareSku) {
        executeWithStockLock(() -> {
            wareSkuService.updateById(wareSku);
            wareSkuService.refreshStockCache(wareSku.getSkuId());
        });

        return R.ok();
    }

    /**
     * 删除
     */
    @RequestMapping("/delete")
    @RequiresPermissions("ware:waresku:delete")
    public R delete(@RequestBody Long[] ids) {
        executeWithStockLock(() -> {
            // 删除前先记住这些记录对应的 skuId —— 删完就查不到了，没法回写缓存
            List<WareSkuEntity> rows = wareSkuService.listByIds(Arrays.asList(ids));

            wareSkuService.removeByIds(Arrays.asList(ids));

            rows.stream()
                    .map(WareSkuEntity::getSkuId)
                    .distinct()
                    .forEach(wareSkuService::refreshStockCache);
        });

        return R.ok();
    }

    /**
     * 查询sku是否有库存
     *
     * @param skuIds
     * @return
     */
    @PostMapping("/hasStock")
    @RequiresPermissions("ware:waresku:hasStock")
    public Map<Long, Boolean> hasStock(@RequestBody List<Long> skuIds) {
        return wareSkuService.getSkuHasStock(skuIds);
    }

    /**
     * 按指定仓库校验库存（order 提交订单时调用）
     */
    @PostMapping("/hasStockByWare")
    public Map<Long, Boolean> hasStockByWare(@RequestBody WareStockCheckVo vo) {
        return wareSkuService.getSkuHasStockByWare(vo.skuWareMap(), vo.skuQuantities());
    }

    /**
     * 查询sku库存数量
     *
     * @param skuIds
     * @return
     */
    @PostMapping("/stockNums")
    @RequiresPermissions("ware:waresku:stockNums")
    public Map<Long, Long> getStockNums(@RequestBody List<Long> skuIds) {
        return wareSkuService.getStockNums(skuIds);
    }

    /**
     * 关联秒杀商品时锁定库存（coupon 调用）
     */
    @PostMapping("/seckill/lock")
    public R lockSeckillStock(@RequestParam("skuId") Long skuId,
                              @RequestParam("quantity") Integer quantity,
                              @RequestParam("operationId") String operationId,
                              @RequestParam(value = "wareId", required = false) Long wareId) {
        try {
            // 1. 锁库存：wareId 为空=新增关联，自动选仓；有值=调大数量，只在原仓加
            Long lockedWareId = wareSkuService.reserveSeckillStock(skuId, quantity, operationId, wareId);
            // 2. 把实际仓库 id 返回给 coupon
            return R.ok().put("wareId", lockedWareId);
        } catch (RRException e) {
            return R.error(e.getMsg());
        }
    }

    /**
     * 修改/删除关联时释放秒杀锁定库存（coupon 调用）
     */
    @PostMapping("/seckill/release")
    public R releaseSeckillStock(@RequestParam("skuId") Long skuId,
                                 @RequestParam("wareId") Long wareId,
                                 @RequestParam("quantity") Integer quantity,
                                 @RequestParam("operationId") String operationId) {
        try {
            // 1. 只释放关联表记录的原仓库；operationId 用来防重复
            wareSkuService.releaseSeckillStock(skuId, wareId, quantity, operationId);
            return R.ok();
        } catch (RRException e) {
            return R.error(e.getMsg());
        }
    }

    /**
     * 全局事务结束后刷新普通可售库存缓存（coupon 调用）。
     *
     * <p>Seata 只管数据库；全局提交或回滚后，由调用方再让缓存跟数据库对齐。
     */
    @PostMapping("/seckill/refresh")
    public R refreshSeckillStockCache(@RequestParam("skuId") Long skuId) {
        wareSkuService.refreshStockCache(skuId);
        return R.ok();
    }

    /**
     * 秒杀建单：把活动锁定转成订单锁定（order 调用）
     */
    @PostMapping("/seckill/order/lock")
    public R lockSeckillOrderStock(@RequestBody com.atguigu.gulimall.api.ware.vo.SeckillOrderStockLockVo vo) {
        try {
            // 1. seckill_stock_locked -> stock_locked，写 biz_type=1 工作单
            Long taskId = wareSkuService.lockSeckillOrderStock(vo);
            return R.ok().put("taskId", taskId);
        } catch (RRException e) {
            return R.error(e.getMsg());
        }
    }

    /**
     * 秒杀订单取消/超时：释放订单占用，按 mode 决定是否回秒杀池（seckill 调用）
     */
    @PostMapping("/seckill/order/release")
    public R releaseSeckillOrderStock(@RequestParam("orderSn") String orderSn,
                                      @RequestParam("mode") String mode) {
        try {
            // 1. task 1→2、stock_locked 减；SECKILL_POOL 时 seckill_stock_locked 加
            wareSkuService.releaseSeckillOrderStock(orderSn, mode);
            return R.ok();
        } catch (RRException e) {
            return R.error(e.getMsg());
        }
    }

    /**
     * 结算/删除场次：释放该 SKU+仓库当前剩余的秒杀锁定（coupon 调用）
     */
    @PostMapping("/seckill/releaseRemaining")
    public R releaseRemainingSeckillStock(@RequestParam("sessionId") Long sessionId,
                                          @RequestParam("skuId") Long skuId,
                                          @RequestParam("wareId") Long wareId,
                                          @RequestParam("operationId") String operationId) {
        try {
            // 1. 以 DB 的 seckill_stock_locked 为准，置 0 并返回释放量
            int released = wareSkuService.releaseRemainingSeckillStock(
                    sessionId, skuId, wareId, operationId);
            return R.ok().put("releasedQuantity", released);
        } catch (RRException e) {
            return R.error(e.getMsg());
        }
    }

    /**
     * 下单锁定库存（由 order 提交订单时调用）
     *
     * <p>锁库存 + 写工作单在 ware 的同一个本地事务里完成，事务提交后发延时解锁消息。
     *
     * <p>⚠️ 这里<b>不加锁</b>：order 的 submitOrder 已经持有 {@code WareConstant.STOCK_LOCK}，
     * 这里再加同一把锁会自己等自己，3 秒后超时失败。
     *
     * @param stockLockVo 订单号 + skuId → 锁定数量
     */
    @PostMapping("/lockStock")
    public R lockStock(@RequestBody StockLockVo stockLockVo) {
        wareSkuService.lockStock(stockLockVo.getOrderId(), stockLockVo.getOrderSn(),
                stockLockVo.getSkuQuantities(), stockLockVo.getSkuWareMap());

        return R.ok();
    }

    /**
     * 支付成功：锁定库存转真实扣减（由 order 支付成功后调用）
     *
     * <p>幂等：重复调用不会重复扣减；工作单已解锁时会报错。
     */
    @PostMapping("/markPaid")
    public R markPaid(@RequestParam("orderSn") String orderSn) {
        wareSkuService.deductLockedStock(orderSn);

        return R.ok();
    }

    /**
     * 按订单号释放锁定库存（用户取消订单时由 order 调用）。
     *
     * <p>找不到工作单、或者工作单已经释放/扣减过，都返回成功，保证幂等。
     * 释放逻辑复用 {@link StockReleaseExecutor}，和延时消息、兜底任务走同一把锁。
     */
    /**
     * 售后退货完成：把退货数量回补到原发货仓库（由 order 在退货单完成时调用）。
     *
     * <p>幂等：同一订单同一 sku 重复调用只会回补一次。
     */
    @PostMapping("/returnStock")
    public R returnStock(@RequestParam("orderSn") String orderSn,
                         @RequestParam("skuId") Long skuId,
                         @RequestParam("num") Integer num) {
        executeWithStockLock(() -> wareSkuService.returnStock(orderSn, skuId, num));
        return R.ok();
    }

    @PostMapping("/releaseByOrderSn")
    public R releaseByOrderSn(@RequestParam("orderSn") String orderSn) {
        if (orderSn == null || orderSn.isBlank()) {
            throw new RRException(BizCodeEnum.WARE_ORDER_SN_REQUIRED);
        }

        WareOrderTaskEntity task = wareOrderTaskService.getOne(
                new QueryWrapper<WareOrderTaskEntity>()
                        .eq("order_sn", orderSn)
                        .last("LIMIT 1"), false);

        if (task != null) {
            stockReleaseExecutor.release(task.getId());
        }

        return R.ok();
    }

    /** 管理员改库存：加库存写锁（独占，与所有下单读锁互斥） */
    private void executeWithStockLock(Runnable action) {
        RLock lock = redissonClient.getReadWriteLock(WareConstant.STOCK_LOCK).writeLock();
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 30, TimeUnit.SECONDS);
            if (!locked) {
                throw new RRException(BizCodeEnum.WARE_STOCK_BUSY);
            }
            action.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RRException(BizCodeEnum.WARE_STOCK_OPERATE_INTERRUPTED);
        } finally {
            // tryLock 超时那条路根本没拿到锁，不能 unlock（会抛 IllegalMonitorStateException）
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

}
