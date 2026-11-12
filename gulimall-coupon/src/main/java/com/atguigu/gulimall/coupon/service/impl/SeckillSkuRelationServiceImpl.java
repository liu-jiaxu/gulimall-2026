package com.atguigu.gulimall.coupon.service.impl;

import com.atguigu.gulimall.api.product.SkuDetailVo;
import com.atguigu.gulimall.common.constant.SeckillConstant;
import com.atguigu.gulimall.common.constant.SeckillSessionState;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.coupon.dao.SeckillSessionDao;
import com.atguigu.gulimall.coupon.entity.SeckillSessionEntity;
import com.atguigu.gulimall.coupon.feign.ProductFeignClient;
import com.atguigu.gulimall.coupon.feign.WareFeignClient;
import com.atguigu.gulimall.coupon.vo.SeckillSkuRelationVo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.Query;

import com.atguigu.gulimall.coupon.dao.SeckillSkuRelationDao;
import com.atguigu.gulimall.coupon.entity.SeckillSkuRelationEntity;
import com.atguigu.gulimall.coupon.service.SeckillSkuRelationService;


@Slf4j
@Service("seckillSkuRelationService")
public class SeckillSkuRelationServiceImpl extends ServiceImpl<SeckillSkuRelationDao, SeckillSkuRelationEntity> implements SeckillSkuRelationService {

    @Autowired
    private WareFeignClient wareFeignClient;

    @Autowired
    private ProductFeignClient productFeignClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SeckillSessionDao seckillSessionDao;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        QueryWrapper<SeckillSkuRelationEntity> wrapper = new QueryWrapper<>();

        // 1. 关联商品弹窗只查当前场次，避免看到/误删别的场次
        Object sessionId = params.get("promotionSessionId");
        if (sessionId != null && StringUtils.isNotBlank(String.valueOf(sessionId))) {
            wrapper.eq("promotion_session_id", sessionId);
        }

        // 2. 关键字按关联 id 或 skuId 模糊匹配
        Object keyObj = params.get("key");
        String key = keyObj == null ? "" : String.valueOf(keyObj).trim();
        if (StringUtils.isNotBlank(key)) {
            wrapper.and(w -> w.like("id", key).or().like("sku_id", key));
        }
        wrapper.orderByAsc("seckill_sort");

        IPage<SeckillSkuRelationEntity> page = this.page(
                new Query<SeckillSkuRelationEntity>().getPage(params),
                wrapper
        );

        return new PageUtils(page);
    }

    @Override
    @GlobalTransactional
    @Transactional(rollbackFor = Exception.class)
    public void saveRelation(SeckillSkuRelationEntity relation) {
        // 1. 校验必填字段：场次、商品、秒杀数量
        if (relation.getPromotionSessionId() == null) {
            throw new RRException("秒杀场次不能为空");
        }
        if (relation.getSkuId() == null) {
            throw new RRException("商品不能为空");
        }
        if (relation.getSeckillCount() == null || relation.getSeckillCount() <= 0) {
            throw new RRException("秒杀总量必须大于 0");
        }

        // 2. 查场次，确认存在，并且必须启用、未开始
        SeckillSessionEntity session = seckillSessionDao.selectById(relation.getPromotionSessionId());
        if (session == null) {
            throw new RRException("秒杀场次不存在");
        }
        if (!canEditRelation(session)) {
            throw new RRException("场次未启用或已经开始，不能关联商品");
        }

        // 3. 应用层查重：同一场次、同一商品只能有关联一条
        long exists = this.count(new QueryWrapper<SeckillSkuRelationEntity>()
                .eq("promotion_session_id", relation.getPromotionSessionId())
                .eq("sku_id", relation.getSkuId()));
        if (exists > 0) {
            throw new RRException("该场次已经关联过这个商品");
        }

        // 4. 同一个商品不能同时参加时间重叠的其他启用场次
        checkNoOverlappingSession(relation.getSkuId(), session, null);

        // 5. 调 ware 锁库存，拿到实际锁定的仓库 id
        Long wareId = reserveAndGetWareId(relation.getSkuId(), relation.getSeckillCount());

        // 6. ware_id 是 NOT NULL，先把仓库 id 回填到关联对象；版本从 0 开始
        relation.setWareId(wareId);
        relation.setVersion(0);

        // 7. 保存关联记录；保存失败 Seata 会回滚第 5 步锁的库存
        try {
            if (!this.save(relation)) {
                throw new RRException("保存秒杀商品关联失败");
            }
        } catch (DuplicateKeyException e) {
            throw new RRException("该场次已经关联过这个商品");
        }

        // 8. 写秒杀库存 Redis key；写失败 Seata 会回滚关联和库存
        writeSeckillStock(session, relation.getSkuId(), relation.getSeckillCount());
    }

    @Override
    @GlobalTransactional
    @Transactional(rollbackFor = Exception.class)
    public void updateRelation(SeckillSkuRelationEntity relation) {
        // 1. 查旧关联，旧数量、旧仓库、旧 sku 都要拿到
        if (relation.getId() == null) {
            throw new RRException("关联 id 不能为空");
        }
        SeckillSkuRelationEntity old = this.getById(relation.getId());
        if (old == null) {
            throw new RRException("秒杀商品关联不存在");
        }

        // 2. 前端没传的字段用旧值兜底；数量必须大于 0
        if (relation.getPromotionSessionId() == null) {
            relation.setPromotionSessionId(old.getPromotionSessionId());
        }
        if (relation.getSkuId() == null) {
            relation.setSkuId(old.getSkuId());
        }
        if (relation.getSeckillCount() == null || relation.getSeckillCount() <= 0) {
            throw new RRException("秒杀总量必须大于 0");
        }

        // 3. 查旧场次和新场次，两个都必须是启用、未开始
        SeckillSessionEntity oldSession = seckillSessionDao.selectById(old.getPromotionSessionId());
        if (!canEditRelation(oldSession)) {
            throw new RRException("场次未启用或已经开始，不能修改关联");
        }
        SeckillSessionEntity session = seckillSessionDao.selectById(relation.getPromotionSessionId());
        if (!canEditRelation(session)) {
            throw new RRException("场次未启用或已经开始，不能修改关联");
        }

        int oldCount = old.getSeckillCount() == null ? 0 : old.getSeckillCount();
        int newCount = relation.getSeckillCount();
        boolean skuChanged = !Objects.equals(old.getSkuId(), relation.getSkuId());
        boolean sessionChanged = !Objects.equals(old.getPromotionSessionId(), relation.getPromotionSessionId());

        // 4. 换场次或换商品时，重新查重，并检查新场次是否和该商品的其他启用场次重叠
        if (skuChanged || sessionChanged) {
            long exists = this.count(new QueryWrapper<SeckillSkuRelationEntity>()
                    .eq("promotion_session_id", relation.getPromotionSessionId())
                    .eq("sku_id", relation.getSkuId())
                    .ne("id", relation.getId()));
            if (exists > 0) {
                throw new RRException("该场次已经关联过这个商品");
            }
            checkNoOverlappingSession(relation.getSkuId(), session, relation.getId());
        }

        // 5. 换商品：先释放旧商品的原仓库，再给新商品锁库存并记录新仓库
        //    换场次：库存和仓库不动
        //    数量变化：只在旧仓库上加减
        if (skuChanged) {
            release(old.getSkuId(), old.getWareId(), oldCount);
            Long newWareId = reserveAndGetWareId(relation.getSkuId(), newCount);
            relation.setWareId(newWareId);
        } else {
            int delta = newCount - oldCount;
            if (delta > 0) {
                // 调大只在原仓库加；原仓不够会报错，不自动换仓
                reserveOnWare(relation.getSkuId(), old.getWareId(), delta);
            } else if (delta < 0) {
                release(relation.getSkuId(), old.getWareId(), -delta);
            }
        }

        // 6. 没换商品时 ware_id 不允许前端改，强制用旧仓库；换商品时用第 5 步新仓库
        if (!skuChanged) {
            relation.setWareId(old.getWareId());
        }

        // 7. 更新关联表：带 version 条件，别人先改过就回滚并提示刷新
        int oldVersion = old.getVersion() == null ? 0 : old.getVersion();
        relation.setVersion(oldVersion + 1);
        try {
            int affected = this.baseMapper.update(relation,
                    new UpdateWrapper<SeckillSkuRelationEntity>()
                            .eq("id", relation.getId())
                            .eq("version", oldVersion));
            if (affected == 0) {
                throw new RRException("关联已被别人修改，请刷新后重试");
            }
        } catch (DuplicateKeyException e) {
            throw new RRException("该场次已经关联过这个商品");
        }

        // 8. 换场次/换商品时删旧库存 key 和旧商品信息，再写新 key
        if (sessionChanged || skuChanged) {
            deleteSeckillStock(old.getPromotionSessionId(), old.getSkuId());
            deleteSessionSku(old.getPromotionSessionId(), old.getSkuId());
        }
        writeSeckillStock(session, relation.getSkuId(), newCount);
    }

    @Override
    @GlobalTransactional
    @Transactional(rollbackFor = Exception.class)
    public void deleteRelations(List<Long> ids) {
        // 1. 没传 id 直接返回
        if (ids == null || ids.isEmpty()) {
            return;
        }

        // 2. 查出要删除的关联
        List<SeckillSkuRelationEntity> relations = this.listByIds(ids);

        // 3. 逐条判断场次状态：
        //    - 进行中/停止中：不能删
        //    - 未开始：删除时要释放全部库存
        //    - 已结束/已取消：已经结算，只删记录
        //    - 到点但还没结算：等结算后再删
        List<SeckillSkuRelationEntity> needRelease = new ArrayList<>();
        for (SeckillSkuRelationEntity relation : relations) {
            SeckillSessionEntity session = seckillSessionDao.selectById(relation.getPromotionSessionId());
            if (session == null) {
                throw new RRException("秒杀场次不存在");
            }
            int stored = session.getSeckillState() == null
                    ? SeckillSessionState.NOT_STARTED : session.getSeckillState();
            int effective = SeckillSessionState.effectiveState(
                    stored, session.getStartTime(), session.getEndTime(), new Date());

            if (stored == SeckillSessionState.STOPPING
                    || effective == SeckillSessionState.RUNNING) {
                throw new RRException("场次进行中或停止中，不能删除关联");
            }
            if (stored == SeckillSessionState.ENDED || stored == SeckillSessionState.CANCELLED) {
                continue;
            }
            // status=0 且还没进入结算，说明是下架的未开始场次，按未开始处理
            boolean disabledNotStarted = Integer.valueOf(0).equals(session.getStatus());
            if (effective != SeckillSessionState.NOT_STARTED && !disabledNotStarted) {
                throw new RRException("场次已到结束时间但还没结算，请等待结算后再删除关联");
            }
            needRelease.add(relation);
        }

        // 4. 未开始的关联释放全部库存
        for (SeckillSkuRelationEntity relation : needRelease) {
            release(relation.getSkuId(), relation.getWareId(), relation.getSeckillCount());
        }

        // 5. 删除关联表记录：带 version 条件，别人先改过就回滚并提示刷新
        for (SeckillSkuRelationEntity relation : relations) {
            int version = relation.getVersion() == null ? 0 : relation.getVersion();
            int deleted = this.baseMapper.delete(new QueryWrapper<SeckillSkuRelationEntity>()
                    .eq("id", relation.getId())
                    .eq("version", version));
            if (deleted == 0) {
                throw new RRException("关联已被别人修改，请刷新后重试");
            }
        }

        // 6. 删除 Redis key；已结束的 key 本来就该没了，这里只做兜底
        for (SeckillSkuRelationEntity relation : relations) {
            try {
                deleteSeckillStock(relation.getPromotionSessionId(), relation.getSkuId());
                // 7. 商品信息 hash 里也删掉这个 sku
                deleteSessionSku(relation.getPromotionSessionId(), relation.getSkuId());
            } catch (Exception e) {
                log.error("[seckill-relation-delete] 删除秒杀 key 失败 sessionId={} skuId={}",
                        relation.getPromotionSessionId(), relation.getSkuId(), e);
            }
        }
    }

    @Override
    public void clearSeckillStock(Long sessionId, Long skuId) {
        if (sessionId == null || skuId == null) {
            return;
        }
        try {
            deleteSeckillStock(sessionId, skuId);
        } catch (Exception e) {
            log.error("[seckill] 清理秒杀库存缓存失败 sessionId={} skuId={}", sessionId, skuId, e);
        }
    }

    @Override
    public void restoreSeckillStock(Long sessionId, Long skuId, Integer quantity) {
        if (sessionId == null || skuId == null || quantity == null || quantity <= 0) {
            return;
        }
        try {
            SeckillSessionEntity session = seckillSessionDao.selectById(sessionId);
            if (session != null) {
                writeSeckillStock(session, skuId, quantity);
            }
        } catch (Exception e) {
            log.error("[seckill] 恢复秒杀库存缓存失败 sessionId={} skuId={}", sessionId, skuId, e);
        }
    }

    @Override
    public List<SeckillSkuRelationVo> listBySession(Long sessionId, String keyword) {
        // 1. 查这个场次的关联，按排序展示
        if (sessionId == null) {
            return List.of();
        }
        List<SeckillSkuRelationEntity> relations = this.list(
                new QueryWrapper<SeckillSkuRelationEntity>()
                        .eq("promotion_session_id", sessionId)
                        .orderByAsc("seckill_sort"));
        if (relations.isEmpty()) {
            return List.of();
        }

        // 2. 批量查商品名称/图片；product 暂时不可用时仍能看 SKU ID
        Map<Long, SkuDetailVo> skuMap = new HashMap<>();
        try {
            List<Long> skuIds = relations.stream()
                    .map(SeckillSkuRelationEntity::getSkuId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            List<SkuDetailVo> details = productFeignClient.getSkuDetails(skuIds);
            if (details != null) {
                details.forEach(detail -> skuMap.put(detail.getSkuId(), detail));
            }
        } catch (Exception e) {
            log.warn("[seckill-relation] 查询商品名称失败，只返回 SKU ID", e);
        }

        // 3. 组装并做模糊过滤：SKU ID 或商品名称包含关键字
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<SeckillSkuRelationVo> result = new ArrayList<>();
        for (SeckillSkuRelationEntity relation : relations) {
            SkuDetailVo sku = skuMap.get(relation.getSkuId());
            String skuName = sku == null ? null : sku.getSpuName();
            String skuImg = sku == null ? null : sku.getSpuPic();
            if (!kw.isEmpty()) {
                boolean match = String.valueOf(relation.getSkuId()).contains(kw)
                        || (skuName != null && skuName.toLowerCase().contains(kw));
                if (!match) {
                    continue;
                }
            }
            SeckillSkuRelationVo vo = new SeckillSkuRelationVo();
            BeanUtils.copyProperties(relation, vo);
            vo.setSkuName(skuName);
            vo.setSkuImg(skuImg);
            result.add(vo);
        }
        return result;
    }

    @Override
    public List<SeckillSkuRelationVo> listAll(Long sessionId, String keyword) {
        // 1. 查关联，可按场次限定
        QueryWrapper<SeckillSkuRelationEntity> wrapper = new QueryWrapper<>();
        if (sessionId != null) {
            wrapper.eq("promotion_session_id", sessionId);
        }
        wrapper.orderByAsc("promotion_session_id").orderByAsc("seckill_sort");
        List<SeckillSkuRelationEntity> relations = this.list(wrapper);
        if (relations.isEmpty()) {
            return List.of();
        }

        // 2. 批量查商品名称/图片
        Map<Long, SkuDetailVo> skuMap = new HashMap<>();
        try {
            List<Long> skuIds = relations.stream()
                    .map(SeckillSkuRelationEntity::getSkuId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            List<SkuDetailVo> details = productFeignClient.getSkuDetails(skuIds);
            if (details != null) {
                details.forEach(detail -> skuMap.put(detail.getSkuId(), detail));
            }
        } catch (Exception e) {
            log.warn("[seckill-relation] 查询商品名称失败，只返回 SKU ID", e);
        }

        // 3. 场次名称
        Map<Long, String> sessionNameMap = new HashMap<>();
        List<Long> sessionIds = relations.stream()
                .map(SeckillSkuRelationEntity::getPromotionSessionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!sessionIds.isEmpty()) {
            for (SeckillSessionEntity session : seckillSessionDao.selectBatchIds(sessionIds)) {
                sessionNameMap.put(session.getId(), session.getName());
            }
        }

        // 4. 组装 + 模糊过滤：SKU ID / 商品名称 / 场次名称
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<SeckillSkuRelationVo> result = new ArrayList<>();
        for (SeckillSkuRelationEntity relation : relations) {
            SkuDetailVo sku = skuMap.get(relation.getSkuId());
            String skuName = sku == null ? null : sku.getSpuName();
            String skuImg = sku == null ? null : sku.getSpuPic();
            String sessionName = sessionNameMap.get(relation.getPromotionSessionId());
            if (!kw.isEmpty()) {
                boolean match = String.valueOf(relation.getSkuId()).contains(kw)
                        || String.valueOf(relation.getPromotionSessionId()).contains(kw)
                        || (skuName != null && skuName.toLowerCase().contains(kw))
                        || (sessionName != null && sessionName.toLowerCase().contains(kw));
                if (!match) {
                    continue;
                }
            }
            SeckillSkuRelationVo vo = new SeckillSkuRelationVo();
            BeanUtils.copyProperties(relation, vo);
            vo.setSessionId(relation.getPromotionSessionId());
            vo.setSessionName(sessionName);
            vo.setSkuName(skuName);
            vo.setSkuImg(skuImg);
            result.add(vo);
        }
        return result;
    }

    @Override
    public void refreshNormalStock(Long skuId) {
        if (skuId == null) {
            return;
        }
        try {
            wareFeignClient.refreshStock(skuId);
        } catch (Exception e) {
            log.error("[seckill] 刷新普通库存缓存失败 skuId={}", skuId, e);
        }
    }

    /**
     * 场次是否允许改关联：必须存在、启用、未开始
     */
    private boolean canEditRelation(SeckillSessionEntity session) {
        // 1. 场次为空直接不允许
        if (session == null) {
            return false;
        }
        // 2. status=1 且有效状态=未开始
        return SeckillSessionState.isEnabledAndNotStarted(
                session.getStatus(),
                session.getSeckillState(),
                session.getStartTime(),
                session.getEndTime(),
                new Date());
    }

    /**
     * 同一个 sku 不能同时参加时间重叠的启用场次。
     *
     * <p>ware 的 seckill_stock_locked 是 sku + 仓库维度的聚合列，不区分场次；
     * 如果重叠，结算时无法只释放本场次的部分，所以这里直接禁止。
     */
    private void checkNoOverlappingSession(Long skuId, SeckillSessionEntity current, Long excludeRelationId) {
        // 1. 当前场次时间不完整，交给其它校验
        if (skuId == null || current.getId() == null
                || current.getStartTime() == null || current.getEndTime() == null) {
            return;
        }

        // 2. 查这个 sku 的其他关联，排除自己
        QueryWrapper<SeckillSkuRelationEntity> wrapper = new QueryWrapper<SeckillSkuRelationEntity>()
                .eq("sku_id", skuId)
                .ne("promotion_session_id", current.getId());
        if (excludeRelationId != null) {
            wrapper.ne("id", excludeRelationId);
        }
        List<SeckillSkuRelationEntity> others = this.list(wrapper);
        if (others.isEmpty()) {
            return;
        }

        // 3. 只看启用且未结算的场次
        List<Long> sessionIds = others.stream()
                .map(SeckillSkuRelationEntity::getPromotionSessionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (sessionIds.isEmpty()) {
            return;
        }
        List<SeckillSessionEntity> sessions = seckillSessionDao.selectBatchIds(sessionIds);
        for (SeckillSessionEntity other : sessions) {
            if (!Integer.valueOf(1).equals(other.getStatus())) {
                continue;
            }
            int state = other.getSeckillState() == null
                    ? SeckillSessionState.NOT_STARTED : other.getSeckillState();
            if (state == SeckillSessionState.STOPPING
                    || state == SeckillSessionState.ENDED
                    || state == SeckillSessionState.CANCELLED) {
                continue;
            }
            if (other.getStartTime() == null || other.getEndTime() == null) {
                continue;
            }
            // 4. 时间区间重叠：other.start < current.end && other.end > current.start
            if (other.getStartTime().before(current.getEndTime())
                    && other.getEndTime().after(current.getStartTime())) {
                throw new RRException("该商品已参加时间重叠的秒杀场次，不能重复关联");
            }
        }
    }

    private Long reserveAndGetWareId(Long skuId, Integer quantity) {
        // 1. 每次调用生成一个 operationId，Feign/Seata 重试时沿用同一个
        String operationId = UUID.randomUUID().toString().replace("-", "");
        // 2. 调 ware 锁库存；wareId 传 0 表示新增关联，由 ware 自动选仓
        R result = wareFeignClient.lockSeckillStock(skuId, quantity, operationId, 0L);
        // 3. 失败直接抛异常，Seata 会回滚
        check(result, "锁定秒杀库存失败");
        // 4. 从返回结果里取实际锁定的仓库 id
        Object wareId = result.get("wareId");
        if (!(wareId instanceof Number)) {
            throw new RRException("锁定秒杀库存失败：未返回仓库 id");
        }
        return ((Number) wareId).longValue();
    }

    private void reserveOnWare(Long skuId, Long wareId, Integer quantity) {
        if (quantity == null || quantity <= 0) {
            return;
        }
        if (skuId == null || wareId == null) {
            throw new RRException("缺少原仓库id，无法增加秒杀库存");
        }
        // 1. 生成 operationId，防止重试重复锁
        String operationId = UUID.randomUUID().toString().replace("-", "");
        // 2. 只在原仓库加；原仓不够 ware 会报错，不换仓
        check(wareFeignClient.lockSeckillStock(skuId, quantity, operationId, wareId),
                "锁定秒杀库存失败");
    }

    private void release(Long skuId, Long wareId, Integer quantity) {
        if (quantity == null || quantity <= 0) {
            return;
        }
        if (skuId == null || wareId == null) {
            throw new RRException("缺少原仓库id，无法释放秒杀库存");
        }
        // 1. 生成 operationId，防止重试重复释放
        String operationId = UUID.randomUUID().toString().replace("-", "");
        // 2. 只释放原仓库；失败抛异常，Seata 回滚
        check(wareFeignClient.releaseSeckillStock(skuId, wareId, quantity, operationId),
                "释放秒杀库存失败");
    }

    private void check(R result, String defaultMsg) {
        if (result == null || result.getCode() == null || result.getCode() != 0) {
            Object msg = result == null ? null : result.get("msg");
            throw new RRException(msg == null ? defaultMsg : String.valueOf(msg));
        }
    }

    private void writeSeckillStock(SeckillSessionEntity session, Long skuId, Integer quantity) {
        long ttlMillis = 3L * 24 * 60 * 60 * 1000;
        if (session.getEndTime() != null) {
            ttlMillis = session.getEndTime().getTime() + 24L * 60 * 60 * 1000 - System.currentTimeMillis();
            if (ttlMillis <= 0) {
                ttlMillis = 24L * 60 * 60 * 1000;
            }
        }
        stringRedisTemplate.opsForValue().set(
                SeckillConstant.stockKey(session.getId(), skuId),
                String.valueOf(quantity),
                Duration.ofMillis(ttlMillis));
    }

    private void deleteSeckillStock(Long sessionId, Long skuId) {
        if (sessionId == null || skuId == null) {
            return;
        }
        stringRedisTemplate.delete(SeckillConstant.stockKey(sessionId, skuId));
    }

    /**
     * 删除场次商品信息 hash 里的单个 sku；Redis 失败只记日志，不影响主流程
     */
    private void deleteSessionSku(Long sessionId, Long skuId) {
        if (sessionId == null || skuId == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForHash().delete(
                    SeckillConstant.sessionSkusKey(sessionId), skuId.toString());
        } catch (Exception e) {
            log.error("[seckill] 删除秒杀商品信息失败 sessionId={} skuId={}", sessionId, skuId, e);
        }
    }

}
