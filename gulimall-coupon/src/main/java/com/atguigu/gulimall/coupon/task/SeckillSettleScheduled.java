package com.atguigu.gulimall.coupon.task;

import com.atguigu.gulimall.common.constant.SeckillSessionState;
import com.atguigu.gulimall.coupon.dao.SeckillSessionDao;
import com.atguigu.gulimall.coupon.entity.SeckillSessionEntity;
import com.atguigu.gulimall.coupon.service.SeckillSettleService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 秒杀结算兜底：每天凌晨 3 点扫停止中且宽限期已到的场次
 */
@Slf4j
@Component
public class SeckillSettleScheduled {

    @Autowired
    private SeckillSessionDao seckillSessionDao;

    @Autowired
    private SeckillSettleService seckillSettleService;

    /** 和订单支付超时共用 30 分钟 */
    @Value("${gulimall.ware.stock-release.delay-ms}")
    private long graceMs;

    @Scheduled(cron = "0 0 3 * * ?")
    public void settleStoppingSessions() {
        // 1. 宽限期截止时间：现在往前推 30 分钟
        Date deadline = new Date(System.currentTimeMillis() - graceMs);

        // 2. 查停止中、且 stop_time/end_time 已经早于截止时间的场次，直接结算
        List<SeckillSessionEntity> stopping = seckillSessionDao.selectList(
                new QueryWrapper<SeckillSessionEntity>()
                        .eq("seckill_state", SeckillSessionState.STOPPING)
                        .apply("COALESCE(stop_time, end_time) <= {0}", deadline));

        for (SeckillSessionEntity session : stopping) {
            try {
                seckillSettleService.settleSession(session.getId());
            } catch (Exception e) {
                log.error("[seckill-settle-task] 停止中场次兜底结算失败 sessionId={}", session.getId(), e);
            }
        }

        // 3. 兜底自然到点但结束消息丢了的场次：状态还是 0/1、启用中、end_time+宽限期已过
        List<SeckillSessionEntity> missedEnd = seckillSessionDao.selectList(
                new QueryWrapper<SeckillSessionEntity>()
                        .in("seckill_state", SeckillSessionState.NOT_STARTED, SeckillSessionState.RUNNING)
                        .eq("status", 1)
                        .apply("end_time <= {0}", deadline));

        for (SeckillSessionEntity session : missedEnd) {
            try {
                // 先补一次“到点进停止中”，再立即结算；markEnding 内部有锁和幂等
                seckillSettleService.markEnding(session.getId(), session.getEndTime().getTime());
                seckillSettleService.settleSession(session.getId());
            } catch (Exception e) {
                log.error("[seckill-settle-task] 漏消息场次兜底结算失败 sessionId={}", session.getId(), e);
            }
        }

        log.info("[seckill-settle-task] 兜底扫描：停止中 {} 个，漏结束消息 {} 个",
                stopping.size(), missedEnd.size());
    }
}
