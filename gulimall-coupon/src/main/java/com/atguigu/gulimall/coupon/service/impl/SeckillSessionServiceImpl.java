package com.atguigu.gulimall.coupon.service.impl;

import com.atguigu.gulimall.common.constant.SeckillSessionState;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.coupon.dao.SeckillSkuRelationDao;
import com.atguigu.gulimall.coupon.entity.SeckillSessionEntity;
import com.atguigu.gulimall.coupon.entity.SeckillSkuRelationEntity;
import com.atguigu.gulimall.coupon.mq.message.SeckillSessionEndMessage;
import com.atguigu.gulimall.coupon.mq.message.SeckillSessionStartMessage;
import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.atguigu.gulimall.rabbitmq.service.MqMessageService;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.Query;

import com.atguigu.gulimall.coupon.dao.SeckillSessionDao;
import com.atguigu.gulimall.coupon.service.SeckillSessionService;


@Slf4j
@Service("seckillSessionService")
public class SeckillSessionServiceImpl extends ServiceImpl<SeckillSessionDao, SeckillSessionEntity> implements SeckillSessionService {

    /**
     * 场次最大跨度：RabbitMQ 延时插件的 x-delay 是 32 位毫秒值，最大约 49.7 天；
     * 业务按天取整限制 30 天，留足安全余量，避免超长延时变成立即投递。
     */
    private static final int MAX_SESSION_DAYS = 30;

    @Autowired
    private MqMessageService mqMessageService;

    @Autowired
    private SeckillSkuRelationDao seckillSkuRelationDao;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<SeckillSessionEntity> page = this.page(
                new Query<SeckillSessionEntity>().getPage(params),
                new QueryWrapper<SeckillSessionEntity>()
        );

        return new PageUtils(page);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveSession(SeckillSessionEntity session) {
        // 1. 校验开始/结束时间：必须存在，结束晚于开始
        validateTime(session);

        // 2. 新建场次一律从未开始开始，启用状态固定为 1；前端传什么状态都不认
        session.setSeckillState(SeckillSessionState.NOT_STARTED);
        session.setStatus(1);

        // 3. 创建时间由后端生成
        if (session.getCreateTime() == null) {
            session.setCreateTime(new Date());
        }

        // 4. 保存场次
        this.save(session);

        // 5. 发开始和结束两条延时消息
        sendStartMessage(session);
        sendEndMessage(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSession(SeckillSessionEntity session) {
        // 1. id 校验，查旧场次；已经开始/结束/停止/取消的场次不允许修改
        if (session.getId() == null) {
            throw new RRException("场次 id 不能为空");
        }
        SeckillSessionEntity old = this.getById(session.getId());
        if (old == null) {
            throw new RRException("秒杀场次不存在");
        }
        if (effectiveState(old) != SeckillSessionState.NOT_STARTED) {
            throw new RRException("场次已经开始或已结束，不能修改；请先走停止流程");
        }

        // 2. 前端没传的时间用旧值兜底
        if (session.getStartTime() == null) {
            session.setStartTime(old.getStartTime());
        }
        if (session.getEndTime() == null) {
            session.setEndTime(old.getEndTime());
        }

        // 3. 状态、启用标记、创建时间都不允许前端直接改
        session.setSeckillState(old.getSeckillState() == null
                ? SeckillSessionState.NOT_STARTED : old.getSeckillState());
        session.setStatus(old.getStatus() == null ? 1 : old.getStatus());
        session.setCreateTime(old.getCreateTime());

        // 4. 校验时间，并检查改完后是否和别的启用场次在同一个 SKU 上重叠
        validateTime(session);
        checkNoOverlappingSession(session);

        // 5. 更新场次
        this.updateById(session);

        // 6. 时间可能改过，重新发开始/结束延时消息；旧消息按时间忽略
        sendStartMessage(session);
        sendEndMessage(session);
    }

    @Override
    public void markRunning(Long sessionId, Long expectedStartTimeMillis) {
        // 1. 场次必须存在、启用，且还没进入停止/结束/取消
        if (sessionId == null) {
            return;
        }
        SeckillSessionEntity session = this.getById(sessionId);
        if (session == null || !Integer.valueOf(1).equals(session.getStatus())) {
            return;
        }
        int stored = session.getSeckillState() == null
                ? SeckillSessionState.NOT_STARTED : session.getSeckillState();
        if (stored == SeckillSessionState.STOPPING
                || stored == SeckillSessionState.ENDED
                || stored == SeckillSessionState.CANCELLED) {
            return;
        }
        if (session.getStartTime() == null || session.getEndTime() == null) {
            return;
        }

        // 2. 旧消息：场次时间改过，按 expectedStartTime 忽略
        if (expectedStartTimeMillis != null
                && !Objects.equals(session.getStartTime().getTime(), expectedStartTimeMillis)) {
            return;
        }

        // 3. 到早了，重新投一条到真实开始时间
        long now = System.currentTimeMillis();
        if (now < session.getStartTime().getTime()) {
            mqMessageService.sendReliable(
                    MqConstants.RETRY_EXCHANGE,
                    MqConstants.SECKILL_START_ROUTING_KEY,
                    new SeckillSessionStartMessage(sessionId, session.getStartTime().getTime()),
                    session.getStartTime().getTime() - now);
            return;
        }

        // 4. 已经过了结束时间，交给结束流程处理
        if (now >= session.getEndTime().getTime()) {
            return;
        }

        // 5. 条件更新：只有未开始/进行中才写进行中，重复消息幂等
        boolean updated = this.update(new UpdateWrapper<SeckillSessionEntity>()
                .set("seckill_state", SeckillSessionState.RUNNING)
                .eq("id", sessionId)
                .eq("status", 1)
                .in("seckill_state", SeckillSessionState.NOT_STARTED, SeckillSessionState.RUNNING)
                .le("start_time", new Date())
                .gt("end_time", new Date()));
        if (updated) {
            log.info("[seckill-start] 场次已进入进行中 sessionId={}", sessionId);
        }
    }

    /**
     * 发场次到点开始消息：只有启用状态才发
     */
    private void sendStartMessage(SeckillSessionEntity session) {
        if (!Integer.valueOf(1).equals(session.getStatus()) || session.getStartTime() == null) {
            return;
        }
        long delay = Math.max(0, session.getStartTime().getTime() - System.currentTimeMillis());
        mqMessageService.sendReliable(
                MqConstants.RETRY_EXCHANGE,
                MqConstants.SECKILL_START_ROUTING_KEY,
                new SeckillSessionStartMessage(session.getId(), session.getStartTime().getTime()),
                delay);
    }

    /**
     * 同一个 SKU 不能同时出现在时间重叠的启用场次里。
     */
    private void checkNoOverlappingSession(SeckillSessionEntity current) {
        if (current.getStartTime() == null || current.getEndTime() == null) {
            return;
        }
        List<SeckillSkuRelationEntity> relations = seckillSkuRelationDao.selectList(
                new QueryWrapper<SeckillSkuRelationEntity>()
                        .eq("promotion_session_id", current.getId()));
        if (relations.isEmpty()) {
            return;
        }

        for (SeckillSkuRelationEntity relation : relations) {
            if (relation.getSkuId() == null) {
                continue;
            }
            List<SeckillSkuRelationEntity> others = seckillSkuRelationDao.selectList(
                    new QueryWrapper<SeckillSkuRelationEntity>()
                            .eq("sku_id", relation.getSkuId())
                            .ne("promotion_session_id", current.getId()));
            if (others.isEmpty()) {
                continue;
            }
            List<Long> otherSessionIds = others.stream()
                    .map(SeckillSkuRelationEntity::getPromotionSessionId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (otherSessionIds.isEmpty()) {
                continue;
            }

            for (SeckillSessionEntity other : this.listByIds(otherSessionIds)) {
                if (!Integer.valueOf(1).equals(other.getStatus())
                        || other.getStartTime() == null || other.getEndTime() == null) {
                    continue;
                }
                int otherState = SeckillSessionState.effectiveState(
                        other.getSeckillState(), other.getStartTime(), other.getEndTime(), new Date());
                if (otherState == SeckillSessionState.ENDED
                        || otherState == SeckillSessionState.CANCELLED) {
                    continue;
                }
                if (other.getStartTime().before(current.getEndTime())
                        && other.getEndTime().after(current.getStartTime())) {
                    throw new RRException("商品 " + relation.getSkuId()
                            + " 已参加时间重叠的场次 " + other.getId() + "，不能修改成这个时间");
                }
            }
        }
    }

    /**
     * 发场次到点结束消息：只有启用状态才发
     */
    private void sendEndMessage(SeckillSessionEntity session) {
        // 1. 下架或不完整的数据不发
        if (!Integer.valueOf(1).equals(session.getStatus()) || session.getEndTime() == null) {
            return;
        }
        // 2. 延时到 end_time
        long delay = Math.max(0, session.getEndTime().getTime() - System.currentTimeMillis());
        // 3. 写本地消息表，事务提交后再投递
        mqMessageService.sendReliable(
                MqConstants.RETRY_EXCHANGE,
                MqConstants.SECKILL_END_ROUTING_KEY,
                new SeckillSessionEndMessage(session.getId(), session.getEndTime().getTime()),
                delay);
    }

    @Override
    public int effectiveState(SeckillSessionEntity session) {
        // 1. 场次为空按未开始处理
        if (session == null) {
            return SeckillSessionState.NOT_STARTED;
        }
        // 2. 存的状态 + 开始/结束时间一起算
        return SeckillSessionState.effectiveState(
                session.getSeckillState(),
                session.getStartTime(),
                session.getEndTime(),
                new Date());
    }

    /**
     * 场次时间校验：开始/结束不能为空，结束必须晚于开始，且不能超过 30 天
     */
    private void validateTime(SeckillSessionEntity session) {
        // 1. 开始/结束时间必须存在
        if (session.getStartTime() == null) {
            throw new RRException("场次开始时间不能为空");
        }
        if (session.getEndTime() == null) {
            throw new RRException("场次结束时间不能为空");
        }

        // 2. 结束时间必须晚于开始时间
        if (!session.getEndTime().after(session.getStartTime())) {
            throw new RRException("场次结束时间必须晚于开始时间");
        }

        // 3. 开始/结束时间都不能超过 30 天。
        //    原因：sendStartMessage/sendEndMessage 把 start_time/end_time - now 直接
        //    塞进 RabbitMQ 的 x-delay，超过 32 位上限（约 49.7 天）会立即投递，
        //    消费者再次续投，形成开始/结束消息死循环。这里按天取整限制 30 天。
        Date maxTime = maxSessionTime();
        if (session.getStartTime().after(maxTime)) {
            throw new RRException("场次开始时间不能超过 " + MAX_SESSION_DAYS + " 天");
        }
        if (session.getEndTime().after(maxTime)) {
            throw new RRException("场次结束时间不能超过 " + MAX_SESSION_DAYS + " 天");
        }
    }

    /**
     * 允许的最大场次时间：从今天算起第 30 天的 23:59:59.999。
     * 按天取整，和前端日期选择器的限制保持一致。
     */
    private Date maxSessionTime() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        calendar.add(Calendar.DAY_OF_MONTH, MAX_SESSION_DAYS);
        return calendar.getTime();
    }

}
