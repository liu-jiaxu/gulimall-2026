package com.atguigu.gulimall.rabbitmq.service;

import com.atguigu.gulimall.rabbitmq.entity.MqDeadMessageEntity;
import com.atguigu.gulimall.rabbitmq.entity.MqMessageEntity;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.amqp.core.Message;

/**
 * 消费端失败归档表：重试到头的消息落在 mq_dead_message，程序撒手，转人工。
 */
public interface MqDeadMessageService extends IService<MqDeadMessageEntity> {

    /**
     * 归档一条消费重试到上限仍失败的消息（消息已经进过 MQ）。
     *
     * @param message    原始消息（消息体和头部都存下来，重放要用）
     * @param failReason 最后一次失败原因
     * @param retryCount 总共重试了几次
     */
    void archive(Message message, String failReason, int retryCount);

    /**
     * 发送端失败归档：消息重发到上限、始终没送达 Broker。
     * 数据源是 mq_message 那一行，调用方归档后应把它删掉。
     */
    void archiveFromSendFailure(MqMessageEntity source, String failReason);

    /**
     * 人工重放：把死信表里所有记录重新投回它们的原始交换机。
     *
     * <p>重放会清掉重试次数等"上次失败的痕迹"；成功的记录从死信表删除，
     * 失败的保留，下次再试。
     *
     * @return 本次成功重放的条数
     */
    int replayAll();

}
