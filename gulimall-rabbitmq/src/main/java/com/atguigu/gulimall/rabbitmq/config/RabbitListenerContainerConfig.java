package com.atguigu.gulimall.rabbitmq.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消费端监听容器工厂：统一强制手动确认。
 *
 * <p>共通模块的重试/归档链路建立在"代码自己决定什么时候 ack"之上：
 * {@code MqRetryService.handleFailure} 负责 basicAck / basicNack，
 * 业务监听器成功时才显式 ack。
 *
 * <p>⚠️ 自动确认和手动确认不能混用，混用会出现
 * {@code PRECONDITION_FAILED - unknown delivery tag}。
 * 所以这里不提供开关，引入共通模块就按手动确认的方式写消费者。
 *
 * <p>Bean 名必须是 {@code rabbitListenerContainerFactory} ——
 * {@code @RabbitListener} 没指定 containerFactory 时默认找这个名字。
 */
@Configuration
public class RabbitListenerContainerConfig {

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        // 先套用 Spring Boot 默认装配（含 yml 的 spring.rabbitmq.listener.simple.*），再覆盖确认模式
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);

        // prefetch 不写死，沿用业务模块 yml 的 spring.rabbitmq.listener.simple.prefetch
        return factory;
    }

}
