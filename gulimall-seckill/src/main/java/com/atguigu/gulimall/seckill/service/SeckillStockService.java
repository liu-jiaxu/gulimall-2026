package com.atguigu.gulimall.seckill.service;

import com.atguigu.gulimall.seckill.vo.SeckillReserveResult;

/**
 * 秒杀库存的 Redis 原子操作
 */
public interface SeckillStockService {

    /**
     * 原子预扣库存、累加每人已购数量、写 10 秒防重 key 和预扣标识。
     */
    SeckillReserveResult tryReserve(Long sessionId, Long skuId, Long memberId, int quantity,
                                    int limit, String orderSn, long startMillis, long endMillis);

    /**
     * 取消/建单失败时回补：库存 +quantity、限购计数 -quantity。
     *
     * @return 1 回补成功，0 已经回补过，-1 库存 key 不存在（调用方应降级普通池）
     */
    int releaseReservation(Long sessionId, Long skuId, Long memberId, int quantity, String orderSn);
}
