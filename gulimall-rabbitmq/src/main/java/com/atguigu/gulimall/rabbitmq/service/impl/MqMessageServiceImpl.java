package com.atguigu.gulimall.rabbitmq.service.impl;

import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.atguigu.gulimall.rabbitmq.dao.MqMessageDao;
import com.atguigu.gulimall.rabbitmq.entity.MqMessageEntity;
import com.atguigu.gulimall.rabbitmq.service.MqDeadMessageService;
import com.atguigu.gulimall.rabbitmq.service.MqMessageService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ClassUtils;

import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 本地消息表实现。
 *
 * <p>发送流程：
 * <ol>
 *   <li>业务事务内把消息写进 mq_message（status=0）</li>
 *   <li>事务提交后投递到 RabbitMQ；发送失败不影响业务，记录留给补偿任务</li>
 *   <li>confirm/return 回调更新状态：1=已抵达，2=待补偿</li>
 *   <li>补偿任务每分钟扫 status=0/2，重发到上限后挪进 mq_dead_message 转人工</li>
 * </ol>
 */
@Slf4j
@Service("mqMessageService")
public class MqMessageServiceImpl extends ServiceImpl<MqMessageDao, MqMessageEntity>
        implements MqMessageService {

    /** 入库后等 confirm 的宽限期：3 分钟还没确认才认为可能丢了、该重发 */
    private static final long CONFIRM_GRACE_MILLIS = 3 * 60 * 1000L;

    /** 两次重发之间的最小间隔：1 分钟，防止重试风暴 */
    private static final long RESEND_INTERVAL_MILLIS = 60 * 1000L;

    /** 全局事务结束后，再等多久才投递；必须大于 Seata 全局事务超时 */
    @Value("${gulimall.mq.reliable.send-delay-ms:60000}")
    private long sendDelayMs;

    @Autowired
    @Qualifier("rabbitTemplate")
    private RabbitTemplate rabbitTemplate;

    @Autowired
    @Qualifier("delayedRabbitTemplate")
    private RabbitTemplate delayedRabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MqDeadMessageService mqDeadMessageService;

    @Override
    public void sendReliable(String exchange, String routingKey, Object payload) {
        sendReliable(exchange, routingKey, payload, 0);
    }

    @Override
    public void sendReliable(String exchange, String routingKey, Object payload, long delayMillis) {
        MqMessageEntity entity = buildAndSave(exchange, routingKey, payload,
                delayMillis, CONFIRM_GRACE_MILLIS);

        // 事务提交后再投递。事务回滚时 afterCommit 不会执行，消息也不会发出去
        afterCommit(() -> {
            try {
                doSend(entity.getToExchange(), entity.getRoutingKey(), payload,
                        entity.getMessageId(), entity.getDelayMillis());
            } catch (Exception e) {
                // 发送失败不能影响已提交的业务；记录留在 status=0，补偿任务会重发
                log.error("【MQ发送】投递失败，等待补偿任务重发，messageId={} exchange={} routingKey={}",
                        entity.getMessageId(), entity.getToExchange(), entity.getRoutingKey(), e);
            }
        });
    }

    @Override
    public void sendReliableDeferred(String exchange, String routingKey, Object payload, long delayMillis) {
        // 只落库，不发送：全局事务还没结束，此时发送或改状态会让 undo_log 变脏
        MqMessageEntity entity = buildAndSave(exchange, routingKey, payload,
                delayMillis, sendDelayMs);

        log.info("【MQ发送】消息只落库，等补偿任务延迟 {}ms 投递，messageId={}",
                sendDelayMs, entity.getMessageId());
    }

    /**
     * 构造消息实体并落库，供“立即发送”和“延迟发送”共用。
     *
     * @param nextRetryDelayMillis 落库后多久允许补偿任务处理：
     *                             立即发送用 3 分钟等待 confirm；
     *                             延迟发送用 send-delay-ms，等全局事务结束
     */
    private MqMessageEntity buildAndSave(String exchange, String routingKey,
                                         Object payload, long delayMillis,
                                         long nextRetryDelayMillis) {
        if (payload == null) {
            throw new IllegalArgumentException("消息体不能为空");
        }

        String messageId = UUID.randomUUID().toString().replace("-", "");
        long safeDelay = Math.max(0, delayMillis);

        MqMessageEntity entity = new MqMessageEntity();
        entity.setMessageId(messageId);
        entity.setToExchange(exchange);
        entity.setRoutingKey(routingKey);
        entity.setClassType(payload.getClass().getName());
        entity.setMessageStatus(STATUS_NEW);
        entity.setRetryCount(0);
        entity.setNextRetryTime(new Date(System.currentTimeMillis() + nextRetryDelayMillis));
        entity.setDelayMillis(safeDelay);
        entity.setCreateTime(new Date());
        entity.setUpdateTime(new Date());
        try {
            entity.setContent(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("消息体无法序列化成JSON：" + payload.getClass().getName(), e);
        }

        // 先落库：如果当前在业务事务里，这一行和业务数据一起提交/回滚
        this.save(entity);

        return entity;
    }

    @Override
    public void markConfirmed(String messageId) {
        if (messageId == null) {
            return;
        }
        // 只把"新建(0)"改成"已抵达(1)"，不动"失败(2)"：
        // 路由失败时 return 回调先到（置2）、confirm 后到（想置1），
        // 不加条件会把失败状态覆盖成成功，消息就"消失"了
        boolean updated = this.update(new UpdateWrapper<MqMessageEntity>()
                .eq("message_id", messageId)
                .eq("message_status", STATUS_NEW)
                .set("message_status", STATUS_CONFIRMED)
                .set("update_time", new Date()));
        if (updated) {
            log.info("【MQ确认】消息已抵达Broker，messageId={}", messageId);
        }
    }

    @Override
    public void markFailed(String messageId, String cause) {
        if (messageId == null) {
            log.error("【MQ失败】回调没有messageId，无法定位消息，cause={}", cause);
            return;
        }
        this.update(new UpdateWrapper<MqMessageEntity>()
                .eq("message_id", messageId)
                .set("message_status", STATUS_FAILED)
                .set("next_retry_time", new Date(System.currentTimeMillis() + RESEND_INTERVAL_MILLIS))
                .set("update_time", new Date()));
        log.error("【MQ失败】消息投递失败，等待补偿重发，messageId={}，原因：{}", messageId, cause);
    }

    @Override
    public int resendFailedMessages(int maxRetry) {
        // 两种需要补偿：
        //   status=0 投递后一直没等到 confirm（发送瞬间宕机 / Broker 出问题）
        //   status=2 confirm=nack 或路由失败
        List<MqMessageEntity> pendingList = this.list(new QueryWrapper<MqMessageEntity>()
                .in("message_status", STATUS_NEW, STATUS_FAILED)
                .le("next_retry_time", new Date())
                .orderByAsc("create_time"));

        int resentCount = 0;
        for (MqMessageEntity entity : pendingList) {
            if (entity.getRetryCount() != null && entity.getRetryCount() >= maxRetry) {
                // 重发到上限仍失败：挪进死信归档表，从本地消息表删掉，程序不再管
                mqDeadMessageService.archiveFromSendFailure(entity,
                        "重发 " + maxRetry + " 次仍未收到 Broker 确认（发送端失败）");
                this.removeById(entity.getId());
                continue;
            }

            try {
                // 存的是 JSON，必须先还原成原类型再发；
                // 直接发字符串会把 __TypeId__ 变成 java.lang.String，消费端拿到的是 String
                Class<?> payloadType = ClassUtils.forName(entity.getClassType(), null);
                Object payload = objectMapper.readValue(entity.getContent(), payloadType);

                // 重置成"新建"，随后到达的 confirm 才认它（markConfirmed 只更新状态0的记录）
                this.update(new UpdateWrapper<MqMessageEntity>()
                        .eq("message_id", entity.getMessageId())
                        .set("message_status", STATUS_NEW)
                        .set("retry_count", (entity.getRetryCount() == null ? 0 : entity.getRetryCount()) + 1)
                        .set("next_retry_time", new Date(System.currentTimeMillis() + CONFIRM_GRACE_MILLIS))
                        .set("update_time", new Date()));

                // 按原定到期时间算剩余延时：这条消息可能是全局事务后延迟发送的，
                // 已经等过 send-delay-ms，不能把完整的 delay-millis 再等一遍
                long plannedDelay = entity.getDelayMillis() == null ? 0 : entity.getDelayMillis();
                long waited = entity.getCreateTime() == null ? 0
                        : System.currentTimeMillis() - entity.getCreateTime().getTime();
                long remainingDelay = Math.max(0, plannedDelay - waited);

                doSend(entity.getToExchange(), entity.getRoutingKey(), payload,
                        entity.getMessageId(), remainingDelay);
                resentCount++;
                log.warn("【MQ补偿】重发消息 messageId={} exchange={} routingKey={} delay={}ms 第{}次",
                        entity.getMessageId(), entity.getToExchange(), entity.getRoutingKey(),
                        remainingDelay,
                        (entity.getRetryCount() == null ? 0 : entity.getRetryCount()) + 1);
            } catch (Exception e) {
                // 单条失败不影响其它消息
                log.error("【MQ补偿】重发失败 messageId={}", entity.getMessageId(), e);
            }
        }
        return resentCount;
    }

    /**
     * 真正调 RabbitTemplate 投递的地方，发送和补偿重发共用。
     *
     * @param messageId  业务消息 ID，塞进 CorrelationData 和消息头
     * @param delayMillis 延时毫秒数；大于 0 时设置 x-delay，由延时交换机负责等待
     */
    private void doSend(String exchange, String routingKey, Object payload,
                        String messageId, long delayMillis) {
        MessagePostProcessor headerProcessor = (Message message) -> {
            message.getMessageProperties().setHeader(MqConstants.HEADER_MESSAGE_ID, messageId);
            if (delayMillis > 0) {
                message.getMessageProperties().setHeader(MqConstants.HEADER_DELAY, delayMillis);
            }
            return message;
        };

        // 延时消息走 mandatory=false 的模板，避免延时插件把延迟中的消息误判 NO_ROUTE
        RabbitTemplate template = delayMillis > 0 ? delayedRabbitTemplate : rabbitTemplate;
        template.convertAndSend(exchange, routingKey, payload, headerProcessor,
                new CorrelationData(messageId));
    }

    /**
     * 有事务就等提交后执行，没有事务就立即执行。
     *
     * <p>afterCommit 里的异常必须自己吞掉：事务已经提交，不能因为 MQ 发送失败
     * 让业务方以为业务失败了。失败记录留给补偿任务。
     */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

}
