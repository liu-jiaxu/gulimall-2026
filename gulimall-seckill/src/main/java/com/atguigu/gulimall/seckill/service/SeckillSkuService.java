package com.atguigu.gulimall.seckill.service;

import com.atguigu.gulimall.api.seckill.vo.SeckillHomeVo;
import com.atguigu.gulimall.api.seckill.vo.SeckillSkuInfoVo;
import com.atguigu.gulimall.api.seckill.vo.SeckillSkuVo;
import com.atguigu.gulimall.seckill.entity.SeckillSessionEntity;

import java.util.List;

/**
 * 秒杀商品上架与查询
 */
public interface SeckillSkuService {

    /** 把未来 3 天 + 进行中的场次商品写到 Redis */
    void uploadSeckillSkuLatest3Days();

    /** 关联新增/修改后，按数据库最新数据刷新单个商品信息 */
    void refreshSessionSku(Long sessionId, Long skuId);

    /** 查询可展示的场次 */
    List<SeckillSessionEntity> listActiveSessions();

    /** 从 Redis 查某场次的商品 */
    List<SeckillSkuVo> getSessionSkus(Long sessionId);

    /** 所有可展示场次 + 商品，秒杀页用 */
    List<SeckillHomeVo> listSessionsWithSkus();

    /** 首页秒杀区：优先进行中场次 */
    SeckillHomeVo getHome();

    /** 商品详情页秒杀模式：价格、限购、已购、剩余 */
    SeckillSkuInfoVo getSkuInfo(Long sessionId, Long skuId, Long memberId);
}
