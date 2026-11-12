package com.atguigu.gulimall.ware.service;

import com.atguigu.gulimall.common.constant.WareConstant;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.exception.RRException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 解锁库存的统一入口：MQ 消费者和定时兜底任务都从这里走。
 *
 * <p><b>为什么解锁也要加锁：</b>每日对账任务（{@code StockReconcileTask}）会拿
 * 「库存写锁」用 DB 的可用库存覆盖 Redis；下单拿的是「读锁」。解锁库存如果什么锁都不拿，
 * 就可能在对账读取 DB 之后、写 Redis 之前插进去做 INCRBY，导致 Redis 被多加了数量。
 * 这里拿和下单同一把读锁，就能被对账的写锁挡住，三者不会交错。
 *
 * <p>读锁之间可以并发：解锁和下单同时进行没有问题 ——
 * 解锁的 DB 更新是原子的，Redis 也只是 INCRBY，不会覆盖别人的预扣。
 */
@Component
public class StockReleaseExecutor {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private WareSkuService wareSkuService;

    /**
     * 加读锁后解锁库存。
     *
     * <p>拿不到锁（正在对账）就抛异常：MQ 会重试，定时任务下一分钟还会再扫到。
     */
    public void release(Long taskId) {
        RLock lock = redissonClient.getReadWriteLock(WareConstant.STOCK_LOCK).readLock();
        boolean locked = false;
        try {
            locked = lock.tryLock(3, TimeUnit.SECONDS);
            if (!locked) {
                throw new RRException(BizCodeEnum.WARE_STOCK_RELEASING);
            }
            wareSkuService.releaseStock(taskId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RRException(BizCodeEnum.WARE_STOCK_RELEASE_INTERRUPTED);
        } finally {
            // tryLock 超时那条路根本没拿到锁，不能 unlock（会抛 IllegalMonitorStateException）
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

}
