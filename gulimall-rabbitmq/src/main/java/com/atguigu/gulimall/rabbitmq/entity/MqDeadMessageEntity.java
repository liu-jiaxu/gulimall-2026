package com.atguigu.gulimall.rabbitmq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 消费端失败归档表：消息重试 N 次仍失败后落库，程序到此为止，转人工处理。
 *
 * <p>它和 {@link MqMessageEntity} 是两件事：
 * <ul>
 *   <li>{@code mq_message} —— 发送端可靠性：消息有没有成功发到 Broker/队列</li>
 *   <li>{@code mq_dead_message} —— 消费端失败归档：谁消费失败了、为什么、重试了几次</li>
 * </ul>
 */
@Data
@TableName("mq_dead_message")
public class MqDeadMessageEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的原始消息 ID，可能为空（别的应用发来的消息、或没走 sendReliable） */
    private String messageId;

    /** 原交换机，人工重放时按这个投回去 */
    private String fromExchange;

    /** 原路由键 */
    private String routingKey;

    /** 消息体全限定类名，重放时把 JSON 还原成对象 */
    private String classType;

    /** 完整消息体 JSON */
    private String content;

    /** 完整消息头 JSON（含重试次数等），排查时最有价值的一列 */
    private String headers;

    /** 最后一次失败原因（异常类名 + 消息） */
    private String failReason;

    /** 总共重试了几次 */
    private Integer retryCount;

    private Date createTime;

    private Date updateTime;

}
