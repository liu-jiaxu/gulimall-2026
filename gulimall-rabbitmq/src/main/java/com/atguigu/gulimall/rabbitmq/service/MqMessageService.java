package com.atguigu.gulimall.rabbitmq.service;

import com.atguigu.gulimall.rabbitmq.entity.MqMessageEntity;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 本地消息表：发送端可靠性的统一入口。
 *
 * <p>业务方发消息应该调 {@link #sendReliable}，不要直接用 RabbitTemplate：
 * sendReliable 会先把消息落库，事务提交后再投递，投递失败由补偿任务重发。
 */
public interface MqMessageService extends IService<MqMessageEntity> {

    /** 新建：已入库，还没收到 Broker 的 confirm */
    int STATUS_NEW = 0;

    /** 已抵达 Broker（confirm 返回 ack=true） */
    int STATUS_CONFIRMED = 1;

    /** 投递失败（confirm nack 或 return 触发），等待补偿重发 */
    int STATUS_FAILED = 2;

    /**
     * 可靠发送普通消息（不延时）。
     *
     * <p>如果当前有事务，消息记录会和业务数据在<b>同一个本地事务</b>里提交，
     * 事务提交后才真正投递；业务回滚则消息记录一起回滚，不会出现"业务失败消息却发出去"。
     *
     * @param exchange   目标交换机
     * @param routingKey 路由键
     * @param payload    消息体对象
     */
    void sendReliable(String exchange, String routingKey, Object payload);

    /**
     * 可靠发送延时消息。
     *
     * @param delayMillis 延时毫秒数（0 表示不延时）
     *                    <p>本地消息表会保存这个值，补偿重发时原样带上，
     *                    避免消费者失败重发时把库存提前解锁。
     */
    void sendReliable(String exchange, String routingKey, Object payload, long delayMillis);

    /**
     * 可靠发送，但<b>不立即投递</b>：只把消息落库，交给补偿任务在
     * {@code gulimall.mq.reliable.send-delay-ms} 之后发送。
     *
     * <p>用在 Seata 全局事务里：全局事务结束前不能发送，否则 confirm 回调会
     * UPDATE mq_message.message_status，而这一行还在 undo_log 里，全局回滚时
     * Seata 校验 afterImage 会判成 dirty undo log。
     *
     * @param delayMillis 消息本身的延时（例如库存解锁 30 分钟），
     *                    补偿任务发送时会扣掉已经等待的时间，保证仍按原定时间到期
     */
    void sendReliableDeferred(String exchange, String routingKey, Object payload, long delayMillis);

    /** confirm 回调：Broker 已收到消息，把状态改成"已抵达" */
    void markConfirmed(String messageId);

    /** 投递失败（confirm nack 或 return 回调），标记为待补偿 */
    void markFailed(String messageId, String cause);

    /**
     * 补偿重发：扫描"长时间没确认"和"投递失败"的消息重新投递。
     *
     * @param maxRetry 单条消息最多重发几次
     * @return 本次实际重发的条数
     */
    int resendFailedMessages(int maxRetry);

}
