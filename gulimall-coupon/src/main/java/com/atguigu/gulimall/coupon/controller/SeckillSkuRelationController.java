package com.atguigu.gulimall.coupon.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.atguigu.gulimall.common.constant.SeckillConstant;
import com.atguigu.gulimall.coupon.entity.SeckillSkuRelationEntity;
import com.atguigu.gulimall.coupon.feign.SeckillFeignClient;
import com.atguigu.gulimall.coupon.service.SeckillSkuRelationService;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.R;



/**
 * 秒杀活动商品关联
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 19:44:40
 */
@Slf4j
@RestController
@RequestMapping("coupon/seckillskurelation")
public class SeckillSkuRelationController {
    @Autowired
    private SeckillSkuRelationService seckillSkuRelationService;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private SeckillFeignClient seckillFeignClient;

    /**
     * 列表
     */
    @RequestMapping("/list")
    @RequiresPermissions("coupon:seckillskurelation:list")
    public R list(@RequestParam Map<String, Object> params){
        PageUtils page = seckillSkuRelationService.queryPage(params);

        return R.ok().put("page", page);
    }


    /**
     * 后台只读查看某场次关联商品，支持按 SKU ID/商品名称模糊查询
     */
    @RequestMapping("/session/{sessionId}")
    @RequiresPermissions("coupon:seckillskurelation:list")
    public R listBySession(@PathVariable("sessionId") Long sessionId,
                           @RequestParam(value = "keyword", required = false) String keyword) {
        return R.ok().put("list", seckillSkuRelationService.listBySession(sessionId, keyword));
    }

    /**
     * 后台全局只读查看全部关联商品，支持场次筛选和模糊查询
     */
    @RequestMapping("/all")
    @RequiresPermissions("coupon:seckillskurelation:list")
    public R listAll(@RequestParam(value = "sessionId", required = false) Long sessionId,
                     @RequestParam(value = "keyword", required = false) String keyword) {
        return R.ok().put("list", seckillSkuRelationService.listAll(sessionId, keyword));
    }

    /**
     * 信息
     */
    @RequestMapping("/info/{id}")
    @RequiresPermissions("coupon:seckillskurelation:info")
    public R info(@PathVariable("id") Long id){
		SeckillSkuRelationEntity seckillSkuRelation = seckillSkuRelationService.getById(id);

        return R.ok().put("seckillSkuRelation", seckillSkuRelation);
    }

    /**
     * 保存
     */
    @RequestMapping("/save")
    @RequiresPermissions("coupon:seckillskurelation:save")
    public R save(@RequestBody SeckillSkuRelationEntity seckillSkuRelation){
        Long sessionId = seckillSkuRelation.getPromotionSessionId();
        Long skuId = seckillSkuRelation.getSkuId();

        List<RLock> locks;
        try {
            locks = lockSessions(sessionId == null ? List.of() : List.of(sessionId));
        } catch (RRException e) {
            return R.error(e.getMsg());
        }
        try {
            boolean success = false;
            try {
                seckillSkuRelationService.saveRelation(seckillSkuRelation);
                success = true;
                return R.ok();
            } catch (RRException e) {
                return R.error(e.getMsg());
            } finally {
                // 全局事务已经结束：失败时删掉可能写进去的秒杀 key
                if (!success) {
                    seckillSkuRelationService.clearSeckillStock(sessionId, skuId);
                } else {
                    // 关联已提交：让秒杀服务立即重建商品信息缓存
                    refreshSkuCache(sessionId, skuId);
                }
                // Seata 只管 DB，普通可售库存缓存按最终 DB 状态重新刷一遍
                seckillSkuRelationService.refreshNormalStock(skuId);
            }
        } finally {
            unlock(locks);
        }
    }

    /**
     * 修改
     */
    @RequestMapping("/update")
    @RequiresPermissions("coupon:seckillskurelation:update")
    public R update(@RequestBody SeckillSkuRelationEntity seckillSkuRelation){
        SeckillSkuRelationEntity old = seckillSkuRelation.getId() == null
                ? null : seckillSkuRelationService.getById(seckillSkuRelation.getId());
        Long oldSessionId = old == null ? null : old.getPromotionSessionId();
        Long oldSkuId = old == null ? null : old.getSkuId();
        Integer oldCount = old == null ? null : old.getSeckillCount();
        Long newSessionId = seckillSkuRelation.getPromotionSessionId();
        Long newSkuId = seckillSkuRelation.getSkuId();

        List<Long> lockIds = new ArrayList<>();
        if (oldSessionId != null) {
            lockIds.add(oldSessionId);
        }
        if (newSessionId != null) {
            lockIds.add(newSessionId);
        }
        List<RLock> locks;
        try {
            locks = lockSessions(lockIds);
        } catch (RRException e) {
            return R.error(e.getMsg());
        }
        try {
            boolean success = false;
            try {
                seckillSkuRelationService.updateRelation(seckillSkuRelation);
                success = true;
                return R.ok();
            } catch (RRException e) {
                return R.error(e.getMsg());
            } finally {
                if (!success) {
                    // 全局事务回滚后：删掉可能写进去的新 key，把旧 key 恢复
                    seckillSkuRelationService.clearSeckillStock(newSessionId, newSkuId);
                    seckillSkuRelationService.restoreSeckillStock(oldSessionId, oldSkuId, oldCount);
                    // 旧 hash 字段可能已经被删，按数据库重建旧关联
                    refreshSkuCache(oldSessionId, oldSkuId);
                } else {
                    // 前端没传的字段用旧值兜底，刷新最终生效的那一条
                    refreshSkuCache(newSessionId != null ? newSessionId : oldSessionId,
                            newSkuId != null ? newSkuId : oldSkuId);
                }
                seckillSkuRelationService.refreshNormalStock(oldSkuId);
                if (newSkuId != null && !newSkuId.equals(oldSkuId)) {
                    seckillSkuRelationService.refreshNormalStock(newSkuId);
                }
            }
        } finally {
            unlock(locks);
        }
    }

    /**
     * 删除
     */
    @RequestMapping("/delete")
    @RequiresPermissions("coupon:seckillskurelation:delete")
    public R delete(@RequestBody Long[] ids){
        List<Long> idList = ids == null ? List.of() : Arrays.asList(ids);
        List<SeckillSkuRelationEntity> oldList = idList.isEmpty()
                ? List.of() : seckillSkuRelationService.listByIds(idList);
        List<Long> sessionIds = oldList.stream()
                .map(SeckillSkuRelationEntity::getPromotionSessionId)
                .toList();

        List<RLock> locks;
        try {
            locks = lockSessions(sessionIds);
        } catch (RRException e) {
            return R.error(e.getMsg());
        }
        try {
            try {
                // 1. 删除关联：未开始会释放库存，已结束/已取消只删记录
                seckillSkuRelationService.deleteRelations(idList);
                return R.ok();
            } catch (RRException e) {
                return R.error(e.getMsg());
            } finally {
                // 2. 不管成功失败，都刷新普通库存缓存
                oldList.forEach(old -> seckillSkuRelationService.refreshNormalStock(old.getSkuId()));
            }
        } finally {
            unlock(locks);
        }
    }

    /**
     * 让秒杀服务按数据库最新关联重建商品信息 hash。
     * 刷新失败只记日志，凌晨 3 点的上架任务会兜底。
     */
    private void refreshSkuCache(Long sessionId, Long skuId) {
        if (sessionId == null || skuId == null) {
            return;
        }
        try {
            seckillFeignClient.refreshSessionSku(sessionId, skuId);
        } catch (Exception e) {
            log.error("[seckill-relation] 刷新秒杀商品信息缓存失败 sessionId={} skuId={}",
                    sessionId, skuId, e);
        }
    }

    /**
     * 按场次 id 加锁，和停止/结算/取消/下架/删除共用同一把锁
     */
    private List<RLock> lockSessions(Collection<Long> sessionIds) {
        List<Long> ids = sessionIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        List<RLock> locks = new ArrayList<>();
        try {
            for (Long sessionId : ids) {
                RLock lock = redissonClient.getLock(SeckillConstant.settleLockKey(sessionId));
                if (!lock.tryLock(3, TimeUnit.SECONDS)) {
                    throw new RRException("秒杀场次正在处理中，请稍后重试");
                }
                locks.add(lock);
            }
            return locks;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            unlock(locks);
            throw new RRException("获取秒杀场次锁被中断");
        } catch (RuntimeException e) {
            unlock(locks);
            throw e;
        }
    }

    /**
     * 倒序释放场次锁
     */
    private void unlock(List<RLock> locks) {
        for (int i = locks.size() - 1; i >= 0; i--) {
            RLock lock = locks.get(i);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

}
