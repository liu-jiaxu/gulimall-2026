package com.atguigu.gulimall.rabbitmq.component;

import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.atguigu.gulimall.rabbitmq.service.MqMessageService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 本地消息表补偿任务：把"该发出去但没收到确认"的消息捞出来重发。
 *
 * <p>它和 confirm/return 回调是一对：回调实时改状态，它兜底 ——
 * 应用在发送瞬间宕机时回调根本不会执行，只有定时扫描能发现。
 *
 * <p>多实例部署时用 Redisson 锁保证同一时刻只有一个实例在补偿。
 * 锁 key 带上 {@code spring.application.name}：不同微服务都有自己的 mq_message 表，
 * 不能共用同一把锁，否则会互相等待、跳过自己的补偿。
 */
@Slf4j
@Component
public class MqMessageCompensateTask {

    @Autowired
    private MqMessageService mqMessageService;

    @Autowired
    private RedissonClient redissonClient;

    @Value("${spring.application.name:gulimall}")
    private String applicationName;

    /** 每分钟的第 0 秒扫一次 */
    @Scheduled(cron = "0 * * * * ?")
    public void resend() {
        String lockKey = "gulimall:rabbitmq:mq:compensate-lock:" + applicationName;
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            // 不传 leaseTime → Redisson 看门狗自动续期，补偿跑得久也不会锁超时
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) {
                log.debug("【MQ补偿】其它实例正在执行补偿，本轮跳过");
                return;
            }

            int count = mqMessageService.resendFailedMessages(MqConstants.MAX_RETRY);
            if (count > 0) {
                log.warn("【MQ补偿】本轮重发了 {} 条未确认的消息", count);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("【MQ补偿】获取补偿锁时被中断", e);
        } catch (Exception e) {
            // 定时任务抛异常不会影响下一次触发，这里兜一下把完整堆栈打出来
            log.error("【MQ补偿】补偿任务执行异常", e);
        } finally {
            // 只有真正拿到锁的线程才有资格解锁
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

}
