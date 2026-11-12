package com.atguigu.gulimall.coupon.task;

import com.atguigu.gulimall.common.constant.SeckillConstant;
import com.atguigu.gulimall.common.constant.SeckillSessionState;
import com.atguigu.gulimall.coupon.dao.SeckillSessionDao;
import com.atguigu.gulimall.coupon.dao.SeckillSkuRelationDao;
import com.atguigu.gulimall.coupon.entity.SeckillSessionEntity;
import com.atguigu.gulimall.coupon.entity.SeckillSkuRelationEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 秒杀库存对账：只检查、只记日志/告警，不自动改数据
 */
@Slf4j
@Component
public class SeckillStockReconcileTask {

    @Autowired
    private SeckillSessionDao seckillSessionDao;

    @Autowired
    private SeckillSkuRelationDao seckillSkuRelationDao;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Scheduled(cron = "0 0 3 * * ?")
    public void reconcile() {
        Date now = new Date();
        Date future = new Date(now.getTime() + 3L * 24 * 60 * 60 * 1000);

        // 1. 扫未来 3 天的场次 + 停止中的场次
        List<SeckillSessionEntity> sessions = seckillSessionDao.selectList(
                new QueryWrapper<SeckillSessionEntity>()
                        .and(w -> w.eq("seckill_state", SeckillSessionState.STOPPING)
                                .or(x -> x.in("seckill_state",
                                                SeckillSessionState.NOT_STARTED, SeckillSessionState.RUNNING)
                                        .ge("end_time", now)
                                        .le("start_time", future))));

        int checked = 0;
        int missing = 0;
        int invalid = 0;

        for (SeckillSessionEntity session : sessions) {
            int effective = SeckillSessionState.effectiveState(
                    session.getSeckillState(), session.getStartTime(), session.getEndTime(), now);
            List<SeckillSkuRelationEntity> relations = seckillSkuRelationDao.selectList(
                    new QueryWrapper<SeckillSkuRelationEntity>()
                            .eq("promotion_session_id", session.getId()));

            for (SeckillSkuRelationEntity relation : relations) {
                checked++;
                String key = SeckillConstant.stockKey(session.getId(), relation.getSkuId());
                String value = stringRedisTemplate.opsForValue().get(key);
                int total = relation.getSeckillCount() == null ? 0 : relation.getSeckillCount();

                // 2. 未开始/进行中/停止中：key 必须存在
                if ((effective == SeckillSessionState.NOT_STARTED
                        || effective == SeckillSessionState.RUNNING
                        || effective == SeckillSessionState.STOPPING) && value == null) {
                    missing++;
                    log.error("[ALERT][seckill-reconcile] 秒杀库存 key 缺失 sessionId={} skuId={} state={}",
                            session.getId(), relation.getSkuId(), effective);
                    continue;
                }

                if (value == null) {
                    continue;
                }

                // 3. 值必须在 0 ~ 配置总量之间
                try {
                    int remaining = Integer.parseInt(value);
                    if (remaining < 0 || remaining > total) {
                        invalid++;
                        log.error("[ALERT][seckill-reconcile] 秒杀库存值异常 sessionId={} skuId={} value={} total={}",
                                session.getId(), relation.getSkuId(), remaining, total);
                    }
                } catch (NumberFormatException e) {
                    invalid++;
                    log.error("[ALERT][seckill-reconcile] 秒杀库存值不是数字 sessionId={} skuId={} value={}",
                            session.getId(), relation.getSkuId(), value);
                }
            }
        }

        log.info("[seckill-reconcile] 对账完成：场次={} 关联={} key缺失={} 值异常={}",
                sessions.size(), checked, missing, invalid);
    }
}
