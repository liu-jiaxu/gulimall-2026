package com.atguigu.gulimall.common.constant;

/**
 * 秒杀 Redis key
 */
public class SeckillConstant {

    /** 秒杀库存 key 前缀，完整 key = seckill:stock:{场次id}:{skuId} */
    public static final String SECKILL_STOCK_PREFIX = "seckill:stock:";

    /** 场次锁前缀：停止/结算/取消/下架/删除/关联修改共用 */
    public static final String SECKILL_SETTLE_LOCK_PREFIX = "seckill:settle:lock:";

    /** 场次商品信息 hash 前缀，完整 key = seckill:session:{场次id}:skus */
    public static final String SECKILL_SESSION_SKUS_PREFIX = "seckill:session:";

    /** 每人已购数量 hash 前缀，完整 key = seckill:user:count:{场次id}:{skuId}，field = memberId */
    public static final String SECKILL_USER_COUNT_PREFIX = "seckill:user:count:";

    /** 抢购防重前缀，完整 key = seckill:req:{场次id}:{skuId}:{memberId}，value = orderSn */
    public static final String SECKILL_REQUEST_PREFIX = "seckill:req:";

    /** 预扣标识前缀，完整 key = seckill:reserve:{orderSn} */
    public static final String SECKILL_RESERVE_PREFIX = "seckill:reserve:";

    /** 释放幂等标识前缀，完整 key = seckill:release:{orderSn} */
    public static final String SECKILL_RELEASE_PREFIX = "seckill:release:";

    public static String stockKey(Long sessionId, Long skuId) {
        return SECKILL_STOCK_PREFIX + sessionId + ":" + skuId;
    }

    public static String sessionSkusKey(Long sessionId) {
        return SECKILL_SESSION_SKUS_PREFIX + sessionId + ":skus";
    }

    public static String userCountKey(Long sessionId, Long skuId) {
        return SECKILL_USER_COUNT_PREFIX + sessionId + ":" + skuId;
    }

    public static String requestKey(Long sessionId, Long skuId, Long memberId) {
        return SECKILL_REQUEST_PREFIX + sessionId + ":" + skuId + ":" + memberId;
    }

    public static String reserveKey(String orderSn) {
        return SECKILL_RESERVE_PREFIX + orderSn;
    }

    public static String releaseKey(String orderSn) {
        return SECKILL_RELEASE_PREFIX + orderSn;
    }

    public static String settleLockKey(Long sessionId) {
        return SECKILL_SETTLE_LOCK_PREFIX + sessionId;
    }

    private SeckillConstant() {
    }
}
