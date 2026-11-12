package com.atguigu.gulimall.seckill.task;

import com.atguigu.gulimall.common.constant.SeckillConstant;
import com.atguigu.gulimall.common.constant.SeckillSessionState;
import com.atguigu.gulimall.seckill.constant.SeckillOrderStatus;
import com.atguigu.gulimall.seckill.dao.SeckillOrderDao;
import com.atguigu.gulimall.seckill.dao.SeckillSessionDao;
import com.atguigu.gulimall.seckill.dao.SeckillSkuRelationDao;
import com.atguigu.gulimall.seckill.entity.SeckillOrderEntity;
import com.atguigu.gulimall.seckill.entity.SeckillSessionEntity;
import com.atguigu.gulimall.seckill.entity.SeckillSkuRelationEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 限购计数对账：以 sms_seckill_order 的预扣/已建单/已支付数量为准，
 * 重建 Redis 里的每人已购 hash。
 *
 * <p>只处理当前可售场次（未开始/进行中，未来 3 天内），避免扫历史数据。
 */
@Slf4j
@Component
public class SeckillLimitReconcileTask {

    private static final long THREE_DAYS_MILLIS = 3L * 24 * 60 * 60 * 1000;
    private static final long ONE_DAY_MILLIS = 24L * 60 * 60 * 1000;

    @Autowired
    private SeckillSessionDao seckillSessionDao;

    @Autowired
    private SeckillSkuRelationDao seckillSkuRelationDao;

    @Autowired
    private SeckillOrderDao seckillOrderDao;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 每分钟跑一次；限购 key 丢失会导致用户绕过限购，尽快修复 */
    @Scheduled(cron = "30 * * * * ?")
    public void reconcile() {
        Date now = new Date();
        Date future = new Date(now.getTime() + THREE_DAYS_MILLIS);

        // 1. 当前可售场次
        List<SeckillSessionEntity> sessions = seckillSessionDao.selectList(
                new QueryWrapper<SeckillSessionEntity>()
                        .eq("status", 1)
                        .in("seckill_state", SeckillSessionState.NOT_STARTED, SeckillSessionState.RUNNING)
                        .ge("end_time", now)
                        .le("start_time", future));
        if (sessions.isEmpty()) {
            return;
        }

        // 2. 这些场次下所有关联
        Map<Long, SeckillSessionEntity> sessionMap = new HashMap<>();
        List<Long> sessionIds = new ArrayList<>();
        for (SeckillSessionEntity session : sessions) {
            sessionMap.put(session.getId(), session);
            sessionIds.add(session.getId());
        }
        List<SeckillSkuRelationEntity> relations = seckillSkuRelationDao.selectList(
                new QueryWrapper<SeckillSkuRelationEntity>()
                        .in("promotion_session_id", sessionIds));
        if (relations.isEmpty()) {
            return;
        }

        // 3. 订单事实：待建单/已建单/已支付都算已购；已释放/建单失败不算
        List<SeckillOrderEntity> orders = seckillOrderDao.selectList(
                new QueryWrapper<SeckillOrderEntity>()
                        .select("session_id", "sku_id", "member_id", "quantity")
                        .in("session_id", sessionIds)
                        .in("status", SeckillOrderStatus.WAIT_BUILD,
                                SeckillOrderStatus.BUILT, SeckillOrderStatus.PAID));
        Map<String, Map<String, Integer>> desiredBySku = new HashMap<>();
        for (SeckillOrderEntity order : orders) {
            if (order.getSessionId() == null || order.getSkuId() == null
                    || order.getMemberId() == null || order.getQuantity() == null) {
                continue;
            }
            String skuKey = order.getSessionId() + ":" + order.getSkuId();
            desiredBySku.computeIfAbsent(skuKey, k -> new HashMap<>())
                    .merge(order.getMemberId().toString(), order.getQuantity(), Integer::sum);
        }

        // 4. 逐个 SKU 校正 Redis hash
        int fixed = 0;
        for (SeckillSkuRelationEntity relation : relations) {
            if (relation.getPromotionSessionId() == null || relation.getSkuId() == null) {
                continue;
            }
            String skuKey = relation.getPromotionSessionId() + ":" + relation.getSkuId();
            Map<String, Integer> desired = desiredBySku.getOrDefault(skuKey, Map.of());
            String redisKey = SeckillConstant.userCountKey(
                    relation.getPromotionSessionId(), relation.getSkuId());

            Map<Object, Object> current = stringRedisTemplate.opsForHash().entries(redisKey);
            boolean same = current.size() == desired.size();
            if (same) {
                for (Map.Entry<String, Integer> entry : desired.entrySet()) {
                    Object value = current.get(entry.getKey());
                    if (value == null
                            || !Objects.equals(String.valueOf(entry.getValue()), String.valueOf(value))) {
                        same = false;
                        break;
                    }
                }
            }
            if (same) {
                continue;
            }

            // 5. 重建：先删再加，TTL 跟库存 key 一致
            stringRedisTemplate.delete(redisKey);
            if (!desired.isEmpty()) {
                Map<String, String> values = new HashMap<>();
                desired.forEach((memberId, count) -> values.put(memberId, String.valueOf(count)));
                stringRedisTemplate.opsForHash().putAll(redisKey, values);
                long ttlMillis = ttlMillis(sessionMap.get(relation.getPromotionSessionId()),
                        relation.getPromotionSessionId(), relation.getSkuId());
                if (ttlMillis > 0) {
                    stringRedisTemplate.expire(redisKey, Duration.ofMillis(ttlMillis));
                }
            }
            fixed++;
            log.error("[ALERT][seckill-limit-reconcile] 限购计数已按订单重建 sessionId={} skuId={} old={} new={}",
                    relation.getPromotionSessionId(), relation.getSkuId(), current, desired);
        }

        if (fixed > 0) {
            log.error("[ALERT][seckill-limit-reconcile] 本轮修复 {} 个限购计数 key", fixed);
        }
    }

    private long ttlMillis(SeckillSessionEntity session, Long sessionId, Long skuId) {
        // 1. 优先沿用库存 key 的剩余过期时间，保证限购 hash 和库存同生共死
        Long stockTtl = stringRedisTemplate.getExpire(
                SeckillConstant.stockKey(sessionId, skuId), TimeUnit.MILLISECONDS);
        if (stockTtl != null && stockTtl > 0) {
            return stockTtl;
        }
        // 2. 库存 key 不在（还没开始扣或已过期），就按场次结束 + 1 天算
        if (session != null && session.getEndTime() != null) {
            long ttl = session.getEndTime().getTime() + ONE_DAY_MILLIS - System.currentTimeMillis();
            if (ttl > 0) {
                return ttl;
            }
        }
        // 3. 什么信息都算不出来时兜底 1 天，别让 key 变成永不过期
        return ONE_DAY_MILLIS;
    }
}
