package com.atguigu.gulimall.ware.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.ware.entity.WareSkuEntity;

import java.util.List;
import java.util.Map;

/**
 * 商品库存
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 20:18:03
 */
public interface WareSkuService extends IService<WareSkuEntity> {

    PageUtils queryPage(Map<String, Object> params);

    /**
     * 入库：该 sku 在该仓库已有记录就累加库存，没有就新增一条
     *
     * @param skuId   sku id
     * @param wareId  仓库 id
     * @param skuName sku 名称，可为空
     * @param skuNum  本次入库数量
     */
    void addStock(Long skuId, Long wareId, String skuName, Integer skuNum);

    Map<Long, Boolean> getSkuHasStock(List<Long> skuIds);

    /**
     * 按指定仓库校验库存：仓库可用库存 >= 购买数量才算有货。
     */
    Map<Long, Boolean> getSkuHasStockByWare(Map<Long, Long> skuWareMap, Map<Long, Integer> skuQuantities);

    Map<Long, Long> getStockNums(List<Long> skuIds);

    /**
     * 把某个 sku 的「可用库存」重新写进 Redis
     *
     * <p>可用库存 = 该 sku 在<b>所有仓库</b>的
     * {@code stock - stock_locked - seckill_stock_locked} 之和。
     * 管理员新增/修改/删除库存后必须立刻调一次，且<b>不设过期时间</b> ——
     * 库存是长期有效的业务数据，过期只会让下单时误判成"没缓存"。
     *
     * @param skuId 商品 id
     */
    void refreshStockCache(Long skuId);

    /**
     * 关联秒杀商品时锁定库存：只增加 {@code seckill_stock_locked}，不动 stock；
     * 库存不够直接报错。
     *
     * @return 实际锁定的仓库 id，coupon 要存到关联表
     */
    Long reserveSeckillStock(Long skuId, Integer quantity, String operationId, Long wareId);

    /**
     * 修改/删除关联时释放秒杀锁定库存：只扣指定的仓库那一行。
     */
    void releaseSeckillStock(Long skuId, Long wareId, Integer quantity, String operationId);

    /**
     * 秒杀建单：把活动锁定的 seckill_stock_locked 转成订单占用的 stock_locked，
     * 同时写 biz_type=1 的工作单，不发普通解锁消息。
     *
     * @return 工作单 id
     */
    Long lockSeckillOrderStock(com.atguigu.gulimall.api.ware.vo.SeckillOrderStockLockVo vo);

    /**
     * 秒杀订单取消/超时：释放订单占用的 stock_locked。
     *
     * @param mode SECKILL_POOL 同时把数量还回 seckill_stock_locked；NORMAL_POOL 只回普通可售池
     */
    void releaseSeckillOrderStock(String orderSn, String mode);

    /**
     * 结算/删除场次时释放该 SKU+仓库当前剩余的全部秒杀锁定。
     *
     * <p>以数据库 seckill_stock_locked 为准，不依赖 Redis 余量；
     * 置 0 天然幂等，operationId 挡住重复消息。
     *
     * @return 本次实际释放的数量
     */
    int releaseRemainingSeckillStock(Long sessionId, Long skuId, Long wareId, String operationId);

    /**
     * 下单锁库存：锁 DB 的 {@code stock_locked} + 写库存工作单，两件事在同一个本地事务里。
     *
     * <p>事务提交后发送延时消息，到期由 ware 自己判断要不要解锁。
     * 消息发送失败不影响下单，定时任务会扫超时未处理的工作单兜底。
     *
     * <p>⚠️ 本方法<b>不自己加锁</b>：调用方（order 的 submitOrder）已经持有
     * {@code WareConstant.STOCK_LOCK}，这里再加同一把锁会自己等自己。
     *
     * @param orderId        订单 id
     * @param orderSn        订单号
     * @param skuQuantities  skuId → 锁定数量
     * @param skuWareMap     skuId → 运费页选中的仓库 id（必须传，缺失直接报错）
     * @return 库存工作单 id
     */
    Long lockStock(Long orderId, String orderSn, Map<Long, Integer> skuQuantities,
                   Map<Long, Long> skuWareMap);

    /**
     * 解锁库存（订单超时 / 取消）。
     *
     * <p>幂等：先把工作单从「已锁定」条件更新成「已解锁」，更新成功的那次才继续解锁库存；
     * 重复消息、定时任务与 MQ 同时处理，都只会真正解锁一次。
     *
     * @param taskId 库存工作单 id
     */
    void releaseStock(Long taskId);

    /**
     * 支付成功：把锁定库存转成真实扣减。
     *
     * <p>DB 上 {@code stock} 和 {@code stock_locked} 同时减，可用库存不变；
     * Redis 在下单时已经预扣过，这里不再动。
     *
     * @param orderSn 订单号
     */
    void deductLockedStock(String orderSn);

    /**
     * 售后退货完成：把商品数量回补到当时发货的仓库。
     *
     * <p>幂等：库存工作单明细从「已扣减(3)」条件更新成「已退货(4)」，
     * 只有更新成功的那次才真正加库存；重复调用直接返回。
     *
     * @param orderSn 订单号
     * @param skuId   商品 id
     * @param num     退货数量
     */
    void returnStock(String orderSn, Long skuId, Integer num);
}
