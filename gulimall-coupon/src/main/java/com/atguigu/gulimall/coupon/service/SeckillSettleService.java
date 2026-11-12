package com.atguigu.gulimall.coupon.service;

import java.util.List;

/**
 * 秒杀场次安全下架/结算
 */
public interface SeckillSettleService {

    /** 自然到点：变停止中，宽限期后再结算成已结束 */
    void markEnding(Long sessionId, Long expectedEndTimeMillis);

    /** 管理员取消：只有进行中能取消，变停止中，宽限期后变已取消 */
    void cancelSession(Long sessionId);

    /** 宽限期到期后结算：释放未售、删 Redis key、按 settle_type 变已结束/已取消 */
    void settleSession(Long sessionId);

    /** 下架：只把 status 改成 0，不动库存和 Redis */
    void offlineSession(Long sessionId);

    /** 物理删除场次和关联；未开始先释放库存，已结束/已取消只删记录 */
    void deleteSessions(List<Long> ids);
}
