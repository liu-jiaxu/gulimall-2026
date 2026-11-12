package com.atguigu.gulimall.ware.task;

import com.atguigu.gulimall.common.constant.WareConstant;
import com.atguigu.gulimall.ware.dao.WareSkuDao;
import com.atguigu.gulimall.ware.entity.WareSkuEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 库存对账：定时用 DB 的可用库存覆盖 Redis
 *
 * <p><b>分片对账 + 分布式锁</b>：
 * <ul>
 *   <li>每次只处理 {@link #BATCH_SIZE} 个 sku，用 Redis 游标记录进度，一轮跑完自动从头开始</li>
 *   <li>每批都先拿「库存写锁」—— 它和下单用的读锁互斥，所以对账期间不会有下单在跑，
 *       Redis 和 DB 是同一个快照，直接用 DB 的值覆盖即可</li>
 *   <li>抢不到锁（正在下单）就跳过这一分钟，下一分钟再来</li>
 * </ul>
 */
@Slf4j
@Component
public class StockReconcileTask {

    /** 每批处理的 sku 数量 */
    private static final int BATCH_SIZE = 100;

    /** 对账游标：记录上一批处理到的 skuId，一轮结束后删除，下次自动从 0 开始 */
    private static final String CURSOR_KEY = "gulimall:ware:reconcile:cursor";

    @Autowired
    private WareSkuDao wareSkuDao;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 每天凌晨 2:00 ~ 4:59 每分钟跑一批
     */
    @Scheduled(cron = "0 * 2-4 * * ?")
    public void reconcile() {
        RLock writeLock = redissonClient.getReadWriteLock(WareConstant.STOCK_LOCK).writeLock();

        // 阻塞等锁：下单结束后立刻抢到；最多等 3 分钟还抢不到才跳过本轮。
        // 不传租约时间 → Redisson 看门狗自动续期，不会因为对账跑得久就锁超时
        try {
            if (!writeLock.tryLock(3, TimeUnit.MINUTES)) {
                log.info("[reconcile] 等待 3 分钟仍在下单，本轮跳过");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("[reconcile] 等待锁被中断，本轮跳过");
            return;
        }

        try {
            long cursor = getCursor();

            // 按 sku_id 游标取一批（去重，一个 sku 可能对应多个仓库）
            List<WareSkuEntity> batch = wareSkuDao.selectList(new QueryWrapper<WareSkuEntity>()
                    .select("DISTINCT sku_id")
                    .gt("sku_id", cursor)
                    .orderByAsc("sku_id")
                    .last("LIMIT " + BATCH_SIZE));

            if (batch.isEmpty()) {
                // 一轮结束：删掉游标，明天从 0 重新开始
                stringRedisTemplate.delete(CURSOR_KEY);
                log.info("[reconcile] 全部 sku 对账完成");
                return;
            }

            for (WareSkuEntity row : batch) {
                Long skuId = row.getSkuId();
                Long dbAvailable = wareSkuDao.getAvailableStock(skuId);

                stringRedisTemplate.opsForValue().set(
                        WareConstant.STOCK_CACHE_PREFIX + skuId,
                        String.valueOf(dbAvailable == null ? 0L : dbAvailable));
            }

            long nextCursor = batch.getLast().getSkuId();
            stringRedisTemplate.opsForValue().set(CURSOR_KEY, String.valueOf(nextCursor));

            log.info("[reconcile] 本批 {} 个 sku 已同步，游标 -> {}", batch.size(), nextCursor);
        } catch (Exception e) {
            log.error("[reconcile] 对账失败", e);
        } finally {
            if (writeLock.isHeldByCurrentThread()) {
                writeLock.unlock();
            }
        }
    }

    private long getCursor() {
        String cursor = stringRedisTemplate.opsForValue().get(CURSOR_KEY);
        return cursor == null ? 0L : Long.parseLong(cursor);
    }

}
