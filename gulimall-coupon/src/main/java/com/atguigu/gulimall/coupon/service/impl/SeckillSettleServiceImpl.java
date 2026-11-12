package com.atguigu.gulimall.coupon.service.impl;

import com.atguigu.gulimall.common.constant.SeckillConstant;
import com.atguigu.gulimall.common.constant.SeckillSessionState;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.coupon.dao.SeckillSessionDao;
import com.atguigu.gulimall.coupon.dao.SeckillSkuRelationDao;
import com.atguigu.gulimall.coupon.entity.SeckillSessionEntity;
import com.atguigu.gulimall.coupon.entity.SeckillSkuRelationEntity;
import com.atguigu.gulimall.coupon.feign.WareFeignClient;
import com.atguigu.gulimall.coupon.mq.message.SeckillSessionEndMessage;
import com.atguigu.gulimall.coupon.mq.message.SeckillSettleMessage;
import com.atguigu.gulimall.coupon.service.SeckillSettleService;
import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.atguigu.gulimall.rabbitmq.service.MqMessageService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀场次安全下架/结算
 */
@Slf4j
@Service("seckillSettleService")
public class SeckillSettleServiceImpl implements SeckillSettleService {

    @Autowired
    private SeckillSessionDao seckillSessionDao;

    @Autowired
    private SeckillSkuRelationDao seckillSkuRelationDao;

    @Autowired
    private WareFeignClient wareFeignClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private MqMessageService mqMessageService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 和订单支付超时共用 30 分钟 */
    @Value("${gulimall.ware.stock-release.delay-ms}")
    private long graceMs;

    @Override
    public void markEnding(Long sessionId, Long expectedEndTimeMillis) {
        // 1. 参数校验
        if (sessionId == null) {
            throw new RRException("场次id不能为空");
        }

        // 2. 拿场次锁
        RLock lock = redissonClient.getLock(SeckillConstant.settleLockKey(sessionId));
        boolean locked = false;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                throw new RRException("秒杀场次正在处理中，请稍后重试");
            }

            // 3. 改状态 + 写结算消息放在同一个本地事务
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> doMarkEnding(sessionId, expectedEndTimeMillis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RRException("秒杀场次结束处理被中断");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 自然到点：场次变停止中，再发 30 分钟后的结算消息
     */
    private void doMarkEnding(Long sessionId, Long expectedEndTimeMillis) {
        // 1. 查场次；场次已经被删了，说明是旧消息，直接忽略
        SeckillSessionEntity session = seckillSessionDao.selectById(sessionId);
        if (session == null) {
            return;
        }

        int stored = session.getSeckillState() == null
                ? SeckillSessionState.NOT_STARTED : session.getSeckillState();

        // 2. 已经停止/结束/取消，或者已经下架，直接返回
        if (stored == SeckillSessionState.STOPPING
                || stored == SeckillSessionState.ENDED
                || stored == SeckillSessionState.CANCELLED) {
            return;
        }
        if (!Integer.valueOf(1).equals(session.getStatus())) {
            return;
        }

        // 3. 结束时间必须存在；消息里的结束时间和当前不一致说明是旧消息，忽略
        if (session.getEndTime() == null) {
            throw new RRException("场次结束时间不能为空");
        }
        if (expectedEndTimeMillis != null
                && !Objects.equals(session.getEndTime().getTime(), expectedEndTimeMillis)) {
            return;
        }

        long now = System.currentTimeMillis();

        // 4. 消息到早了，重新投一条到 end_time 的消息。
        //
        // ⚠️ RabbitMQ 延时插件的 x-delay 是 32 位毫秒值，最大约 49.7 天。
        // 如果 end_time - now 超过这个范围，消息不会被延时，而是被当成 0/非法值
        // 立即投回 seckill.end 队列；消费者再次执行到这里，又发一条同样超限的
        // 结束消息，形成「结束消息 → 立即消费 → 再发结束消息」的死循环。
        //
        // 因此新增场次时前端把开始/结束时间限制在 30 天内；更长的场次不能用
        // 一条 MQ 延时消息直接等到结束，应改用定时任务扫描 end_time 到点的场次，
        // 或按安全上限分段续投。
        if (now < session.getEndTime().getTime()) {
            mqMessageService.sendReliable(
                    MqConstants.RETRY_EXCHANGE,
                    MqConstants.SECKILL_END_ROUTING_KEY,
                    new SeckillSessionEndMessage(sessionId, session.getEndTime().getTime()),
                    session.getEndTime().getTime() - now);
            return;
        }

        // 5. 条件更新成停止中，settle_type=1 表示最终变已结束
        int affected = seckillSessionDao.update(null, new UpdateWrapper<SeckillSessionEntity>()
                .set("seckill_state", SeckillSessionState.STOPPING)
                .set("settle_type", SeckillSessionState.SETTLE_TYPE_END)
                .set("stop_time", null)
                .eq("id", sessionId)
                .in("seckill_state", SeckillSessionState.NOT_STARTED, SeckillSessionState.RUNNING));
        if (affected == 0) {
            return;
        }

        // 6. 停止中不能再抢，删掉库存 key；结算按 DB 剩余释放，不依赖它
        deleteStockKeys(sessionId);

        // 7. 从 end_time 起算 30 分钟宽限期，到了再结算
        long delay = Math.max(0, session.getEndTime().getTime() + graceMs - now);
        mqMessageService.sendReliable(
                MqConstants.RETRY_EXCHANGE,
                MqConstants.SECKILL_SETTLE_ROUTING_KEY,
                new SeckillSettleMessage(sessionId),
                delay);
    }

    @Override
    public void cancelSession(Long sessionId) {
        // 1. 参数校验
        if (sessionId == null) {
            throw new RRException("场次id不能为空");
        }

        // 2. 拿场次锁
        RLock lock = redissonClient.getLock(SeckillConstant.settleLockKey(sessionId));
        boolean locked = false;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                throw new RRException("秒杀场次正在处理中，请稍后重试");
            }

            // 3. 改状态 + 写结算消息放在同一个本地事务
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> doCancel(sessionId));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RRException("取消秒杀场次被中断");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 管理员取消：只有进行中能取消，先变停止中，30 分钟后变已取消
     */
    private void doCancel(Long sessionId) {
        // 1. 查场次
        SeckillSessionEntity session = seckillSessionDao.selectById(sessionId);
        if (session == null) {
            throw new RRException("秒杀场次不存在");
        }

        int stored = session.getSeckillState() == null
                ? SeckillSessionState.NOT_STARTED : session.getSeckillState();

        // 2. 已取消直接返回；停止中/已结束不能取消
        if (stored == SeckillSessionState.CANCELLED) {
            return;
        }
        if (stored == SeckillSessionState.STOPPING || stored == SeckillSessionState.ENDED) {
            throw new RRException("场次已停止或已结束，不能取消");
        }

        // 3. 只有启用且进行中的场次可以取消
        if (!Integer.valueOf(1).equals(session.getStatus())) {
            throw new RRException("场次未启用，不能取消");
        }
        int effective = SeckillSessionState.effectiveState(
                stored, session.getStartTime(), session.getEndTime(), new Date());
        if (effective != SeckillSessionState.RUNNING) {
            throw new RRException("只有进行中的场次可以取消");
        }

        // 4. 条件更新成停止中，settle_type=2 表示最终变已取消
        Date stopTime = new Date();
        int affected = seckillSessionDao.update(null, new UpdateWrapper<SeckillSessionEntity>()
                .set("seckill_state", SeckillSessionState.STOPPING)
                .set("settle_type", SeckillSessionState.SETTLE_TYPE_CANCEL)
                .set("stop_time", stopTime)
                .eq("id", sessionId)
                .in("seckill_state", SeckillSessionState.NOT_STARTED, SeckillSessionState.RUNNING));
        if (affected == 0) {
            SeckillSessionEntity latest = seckillSessionDao.selectById(sessionId);
            if (latest != null
                    && Integer.valueOf(SeckillSessionState.STOPPING).equals(latest.getSeckillState())
                    && Integer.valueOf(SeckillSessionState.SETTLE_TYPE_CANCEL).equals(latest.getSettleType())) {
                return;
            }
            throw new RRException("场次状态已变化，取消失败");
        }

        // 5. 停止中不能再抢，删掉库存 key；结算按 DB 剩余释放，不依赖它
        deleteStockKeys(sessionId);

        // 6. 从停止时间起算 30 分钟宽限期
        mqMessageService.sendReliable(
                MqConstants.RETRY_EXCHANGE,
                MqConstants.SECKILL_SETTLE_ROUTING_KEY,
                new SeckillSettleMessage(sessionId),
                graceMs);
    }

    @Override
    public void settleSession(Long sessionId) {
        // 1. 参数校验
        if (sessionId == null) {
            return;
        }

        // 2. 拿场次锁；MQ 重投和 3 点兜底同时来，只让一个线程结算
        RLock lock = redissonClient.getLock(SeckillConstant.settleLockKey(sessionId));
        boolean locked = false;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }

            // 3. 只有停止中的场次才结算
            SeckillSessionEntity session = seckillSessionDao.selectById(sessionId);
            if (session == null
                    || !Integer.valueOf(SeckillSessionState.STOPPING).equals(session.getSeckillState())) {
                return;
            }

            // 4. 宽限期没到直接返回
            Date graceStart = session.getStopTime() != null ? session.getStopTime() : session.getEndTime();
            if (graceStart == null) {
                throw new RRException("场次缺少停止/结束时间，无法结算");
            }
            if (System.currentTimeMillis() < graceStart.getTime() + graceMs) {
                return;
            }

            // 5. 查出这场秒杀的所有关联
            List<SeckillSkuRelationEntity> relations = seckillSkuRelationDao.selectList(
                    new QueryWrapper<SeckillSkuRelationEntity>()
                            .eq("promotion_session_id", sessionId));

            // 6. 逐个释放数据库里当前剩余的秒杀锁定；已售部分在建单时已转成 stock_locked
            for (SeckillSkuRelationEntity relation : relations) {
                String operationId = "settle:" + sessionId + ":" + relation.getId();
                check(wareFeignClient.releaseRemainingSeckillStock(
                                sessionId, relation.getSkuId(), relation.getWareId(), operationId),
                        "释放秒杀剩余库存失败");
            }

            // 7. 根据 settle_type 决定最终状态：1已结束 2已取消
            int finalState = Integer.valueOf(SeckillSessionState.SETTLE_TYPE_CANCEL)
                    .equals(session.getSettleType())
                    ? SeckillSessionState.CANCELLED : SeckillSessionState.ENDED;
            seckillSessionDao.update(null, new UpdateWrapper<SeckillSessionEntity>()
                    .set("seckill_state", finalState)
                    .eq("id", sessionId)
                    .eq("seckill_state", SeckillSessionState.STOPPING));

            // 8. 状态改完后删 key；删失败只记日志，key 到期也会消失
            for (SeckillSkuRelationEntity relation : relations) {
                try {
                    deleteSeckillKey(sessionId, relation.getSkuId());
                } catch (Exception e) {
                    log.error("[seckill-settle] 删除秒杀 key 失败 sessionId={} skuId={}",
                            sessionId, relation.getSkuId(), e);
                }
                // 9. 释放后普通可售库存变了，必须按数据库重新刷一遍缓存
                refreshNormalStock(relation.getSkuId());
            }

            // 10. 场次结束，商品信息 hash 不再需要
            stringRedisTemplate.delete(SeckillConstant.sessionSkusKey(sessionId));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RRException("秒杀结算被中断");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void offlineSession(Long sessionId) {
        // 1. 参数校验
        if (sessionId == null) {
            throw new RRException("场次id不能为空");
        }

        // 2. 拿场次锁
        RLock lock = redissonClient.getLock(SeckillConstant.settleLockKey(sessionId));
        boolean locked = false;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                throw new RRException("秒杀场次正在处理中，请稍后重试");
            }

            // 3. 查场次
            SeckillSessionEntity session = seckillSessionDao.selectById(sessionId);
            if (session == null) {
                throw new RRException("秒杀场次不存在");
            }
            if (Integer.valueOf(0).equals(session.getStatus())) {
                return;
            }

            int stored = session.getSeckillState() == null
                    ? SeckillSessionState.NOT_STARTED : session.getSeckillState();
            int effective = SeckillSessionState.effectiveState(
                    stored, session.getStartTime(), session.getEndTime(), new Date());

            // 4. 只有未开始、已结束、已取消能下架
            boolean allowed = effective == SeckillSessionState.NOT_STARTED
                    || stored == SeckillSessionState.ENDED
                    || stored == SeckillSessionState.CANCELLED;
            if (!allowed) {
                throw new RRException("只有未开始/已结束/已取消的场次可以下架");
            }

            // 5. 只改 status=0，不动状态、库存和 Redis
            seckillSessionDao.update(null, new UpdateWrapper<SeckillSessionEntity>()
                    .set("status", 0)
                    .eq("id", sessionId)
                    .eq("status", 1));

            // 6. 下架后商品信息 hash 不再使用
            stringRedisTemplate.delete(SeckillConstant.sessionSkusKey(sessionId));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RRException("下架秒杀场次被中断");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void deleteSessions(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        for (Long sessionId : ids) {
            if (sessionId == null) {
                continue;
            }
            deleteOneSession(sessionId);
        }
    }

    private void deleteOneSession(Long sessionId) {
        RLock lock = redissonClient.getLock(SeckillConstant.settleLockKey(sessionId));
        boolean locked = false;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                throw new RRException("秒杀场次正在处理中，请稍后重试");
            }

            // 1. 查场次，已经不存在直接返回
            SeckillSessionEntity session = seckillSessionDao.selectById(sessionId);
            if (session == null) {
                return;
            }

            int stored = session.getSeckillState() == null
                    ? SeckillSessionState.NOT_STARTED : session.getSeckillState();
            int effective = SeckillSessionState.effectiveState(
                    stored, session.getStartTime(), session.getEndTime(), new Date());

            // 2. 进行中、停止中不能删；到点没结算也不能删
            if (effective == SeckillSessionState.RUNNING || stored == SeckillSessionState.STOPPING) {
                throw new RRException("场次进行中或停止中，不能删除");
            }
            boolean settled = stored == SeckillSessionState.ENDED
                    || stored == SeckillSessionState.CANCELLED;
            // status=0 且还没进入结算，说明是下架的未开始场次，按未开始处理
            boolean disabledNotStarted = Integer.valueOf(0).equals(session.getStatus()) && !settled;
            boolean notStarted = effective == SeckillSessionState.NOT_STARTED || disabledNotStarted;
            if (!settled && !notStarted) {
                throw new RRException("场次已到结束时间但还没结算，请等待结算后再删除");
            }

            // 3. 查关联
            List<SeckillSkuRelationEntity> relations = seckillSkuRelationDao.selectList(
                    new QueryWrapper<SeckillSkuRelationEntity>()
                            .eq("promotion_session_id", sessionId));

            // 4. 未开始的场次释放数据库里当前剩余的全部锁定；已结束/已取消的结算时已经处理过
            if (!settled) {
                for (SeckillSkuRelationEntity relation : relations) {
                    String operationId = "delete:" + sessionId + ":" + relation.getId();
                    check(wareFeignClient.releaseRemainingSeckillStock(
                                    sessionId, relation.getSkuId(), relation.getWareId(), operationId),
                            "删除场次释放库存失败");
                }
            }

            // 5. 物理删除关联和场次
            seckillSkuRelationDao.delete(new QueryWrapper<SeckillSkuRelationEntity>()
                    .eq("promotion_session_id", sessionId));
            seckillSessionDao.deleteById(sessionId);

            // 6. 清 Redis key；已结束的 key 本来应该已经删了，这里只做兜底
            for (SeckillSkuRelationEntity relation : relations) {
                try {
                    deleteSeckillKey(sessionId, relation.getSkuId());
                } catch (Exception e) {
                    log.error("[seckill-delete] 删除秒杀 key 失败 sessionId={} skuId={}",
                            sessionId, relation.getSkuId(), e);
                }
                refreshNormalStock(relation.getSkuId());
            }

            // 7. 物理删除后，商品信息 hash 也删掉
            stringRedisTemplate.delete(SeckillConstant.sessionSkusKey(sessionId));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RRException("删除秒杀场次被中断");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 场次进入停止中后删掉库存 key，阻止停止中继续抢购。
     * 结算已经改为按数据库剩余释放，不依赖这个 key。
     */
    private void deleteStockKeys(Long sessionId) {
        List<SeckillSkuRelationEntity> relations = seckillSkuRelationDao.selectList(
                new QueryWrapper<SeckillSkuRelationEntity>()
                        .eq("promotion_session_id", sessionId));
        for (SeckillSkuRelationEntity relation : relations) {
            try {
                stringRedisTemplate.delete(SeckillConstant.stockKey(sessionId, relation.getSkuId()));
            } catch (Exception e) {
                log.error("[seckill-stop] 删除秒杀库存 key 失败 sessionId={} skuId={}",
                        sessionId, relation.getSkuId(), e);
            }
        }
    }

    private void deleteSeckillKey(Long sessionId, Long skuId) {
        // 库存 key 和每人已购计数一起清理
        stringRedisTemplate.delete(SeckillConstant.stockKey(sessionId, skuId));
        stringRedisTemplate.delete(SeckillConstant.userCountKey(sessionId, skuId));
    }

    private void refreshNormalStock(Long skuId) {
        try {
            wareFeignClient.refreshStock(skuId);
        } catch (Exception e) {
            log.error("[seckill-settle] 刷新普通库存缓存失败 skuId={}", skuId, e);
        }
    }

    private void check(R result, String defaultMsg) {
        if (result == null || result.getCode() == null || result.getCode() != 0) {
            Object msg = result == null ? null : result.get("msg");
            throw new RRException(msg == null ? defaultMsg : String.valueOf(msg));
        }
    }
}
