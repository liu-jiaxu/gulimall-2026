package com.atguigu.gulimall.rabbitmq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 本地消息表（Local Message Table）：消息投递前先落库，confirm/return 回调再改状态，
 * 定时任务靠它找出"该发但没确认"的消息重发。
 *
 * <p>必须和使用方的业务表在<b>同一个数据库</b>里，这样 sendReliable 才能和业务写入
 * 在同一个本地事务中提交；放到单独的 MQ 库就不叫本地消息表了。
 *
 * <p>{@code delay_millis} 是 gulimall 库存解锁链路新增的：本地消息表重发时要保留
 * "延时 30 分钟"这个属性，否则补偿重发会把库存提前解锁。
 */
@Data
@TableName("mq_message")
public class MqMessageEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 自增主键：随机 UUID 做主键会造成 InnoDB 页分裂，message_id 只做唯一索引 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 消息唯一 ID（UUID 去掉横线）：CorrelationData、消息头、回调定位都用它 */
    private String messageId;

    /** 消息体 JSON 字符串，重发时用它还原对象 */
    private String content;

    /** 目标交换机 */
    private String toExchange;

    /** 路由键 */
    private String routingKey;

    /** 消息体的全限定类名，重发时靠它把 JSON 反序列化回原类型 */
    private String classType;

    /** 消息状态【0->新建待确认；1->已抵达 Broker；2->投递失败待补偿】 */
    private Integer messageStatus;

    /** 已重发次数 */
    private Integer retryCount;

    /** 下次可重发时间 */
    private Date nextRetryTime;

    /** 延时毫秒数（0 = 不延时）；重发时原样带上，避免提前投递 */
    private Long delayMillis;

    private Date createTime;

    private Date updateTime;

}
