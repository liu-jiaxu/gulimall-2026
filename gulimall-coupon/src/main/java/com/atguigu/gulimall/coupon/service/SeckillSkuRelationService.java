package com.atguigu.gulimall.coupon.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.coupon.entity.SeckillSkuRelationEntity;
import com.atguigu.gulimall.coupon.vo.SeckillSkuRelationVo;

import java.util.List;
import java.util.Map;

/**
 * 秒杀活动商品关联
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 19:44:40
 */
public interface SeckillSkuRelationService extends IService<SeckillSkuRelationEntity> {

    PageUtils queryPage(Map<String, Object> params);

    /**
     * 关联秒杀商品：库存够就加秒杀锁库存，不够直接失败。
     */
    void saveRelation(SeckillSkuRelationEntity relation);

    /** 修改关联：按数量增减锁定库存，并同步 Redis */
    void updateRelation(SeckillSkuRelationEntity relation);

    /** 删除关联：释放对应库存，并删掉 Redis key */
    void deleteRelations(List<Long> ids);

    /** 关联失败时删掉秒杀库存 Redis key */
    void clearSeckillStock(Long sessionId, Long skuId);

    /** 全局事务失败时把旧秒杀库存 key 恢复回去 */
    void restoreSeckillStock(Long sessionId, Long skuId, Integer quantity);

    /** 全局事务结束后刷新普通可售库存缓存 */
    void refreshNormalStock(Long skuId);

    /**
     * 后台只读查看某场次关联商品，支持按 SKU ID 或商品名称模糊匹配。
     */
    List<SeckillSkuRelationVo> listBySession(Long sessionId, String keyword);

    /**
     * 后台全局只读查看全部关联商品，可按场次过滤，支持 SKU/商品名称/场次名称模糊查询。
     */
    List<SeckillSkuRelationVo> listAll(Long sessionId, String keyword);
}

