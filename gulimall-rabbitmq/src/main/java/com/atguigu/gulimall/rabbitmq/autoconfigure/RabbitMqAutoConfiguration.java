package com.atguigu.gulimall.rabbitmq.autoconfigure;

import com.atguigu.gulimall.rabbitmq.component.MqDeadMessageListener;
import com.atguigu.gulimall.rabbitmq.component.MqMessageCompensateTask;
import com.atguigu.gulimall.rabbitmq.config.RabbitCallbackConfig;
import com.atguigu.gulimall.rabbitmq.config.RabbitConverterConfig;
import com.atguigu.gulimall.rabbitmq.config.RabbitListenerContainerConfig;
import com.atguigu.gulimall.rabbitmq.config.RabbitQueueConfig;
import com.atguigu.gulimall.rabbitmq.config.RabbitTemplateConfig;
import com.atguigu.gulimall.rabbitmq.service.impl.MqDeadMessageServiceImpl;
import com.atguigu.gulimall.rabbitmq.service.impl.MqMessageServiceImpl;
import com.atguigu.gulimall.rabbitmq.service.impl.MqRetryServiceImpl;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RabbitMQ 共通模块自动配置：业务模块引依赖即可用，不用自己声明任何 Bean。
 *
 * <p>包含：
 * <ul>
 *   <li>交换机/队列/绑定声明（RabbitQueueConfig）</li>
 *   <li>JSON 转换器 + 反序列化白名单（RabbitConverterConfig）</li>
 *   <li>手动 ack 的监听容器工厂（RabbitListenerContainerConfig）</li>
 *   <li>confirm/return 回调 + 本地消息表（RabbitCallbackConfig / MqMessageServiceImpl）</li>
 *   <li>消费失败延迟重试 + 死信归档 + 人工重放</li>
 *   <li>本地消息表补偿定时任务</li>
 * </ul>
 *
 * <p>⚠️ 使用方必须满足：
 * <ol>
 *   <li>有 DataSource，并且业务库里建了 mq_message / mq_dead_message 两张表
 *       （本地消息表必须和业务表同库，才能和业务数据同事务）</li>
 *   <li>有 Redis（Redisson 补偿任务加锁用）</li>
 *   <li>common.yaml 里配好 spring.rabbitmq 连接信息和 publisher confirm/returns</li>
 *   <li>broker 安装 rabbitmq_delayed_message_exchange 插件</li>
 * </ol>
 *
 * <p>放在 RabbitAutoConfiguration 之前：这样本模块的 rabbitListenerContainerFactory
 * 会先注册，Spring Boot 默认的自动配置发现同名 Bean 后自动退让。
 */
@AutoConfiguration(before = RabbitAutoConfiguration.class)
@EnableScheduling
@MapperScan("com.atguigu.gulimall.rabbitmq.dao")
@Import({
        RabbitQueueConfig.class,
        RabbitConverterConfig.class,
        RabbitTemplateConfig.class,
        RabbitListenerContainerConfig.class,
        RabbitCallbackConfig.class,
        MqMessageServiceImpl.class,
        MqDeadMessageServiceImpl.class,
        MqRetryServiceImpl.class,
        MqDeadMessageListener.class,
        MqMessageCompensateTask.class
})
public class RabbitMqAutoConfiguration {

}
