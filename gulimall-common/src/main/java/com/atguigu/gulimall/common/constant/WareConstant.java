package com.atguigu.gulimall.common.constant;

/**
 * 仓储模块的公共常量
 *
 * <p>放在 common 而不是 ware 里，是因为这些 key 要<b>跨模块共用</b>：
 * order 提交订单时加的锁，必须和 ware 管理员增删改库存时加的锁是<b>同一把</b>，
 * 否则两边各锁各的，等于没锁。
 */
public class WareConstant {

    /**
     * 库存缓存 key 前缀，完整 key = {@code gulimall:ware:stock:{skuId}}，值是「可用库存数量」
     *
     * <p>不设过期时间：库存是一直有效的业务数据，过期了反而会被当成"没缓存"重新查库
     */
    public static final String STOCK_CACHE_PREFIX = "gulimall:ware:stock:";

    /** 库存操作的读写锁：下单加读锁（可并发），管理员增删改库存加写锁（与所有下单互斥） */
    public static final String STOCK_LOCK = "gulimall:ware:stock:lock";

    private WareConstant() {
    }

}
