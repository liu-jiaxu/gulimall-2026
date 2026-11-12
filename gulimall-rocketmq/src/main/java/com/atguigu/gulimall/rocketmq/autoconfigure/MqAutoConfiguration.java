package com.atguigu.gulimall.rocketmq.autoconfigure;

import com.atguigu.gulimall.rocketmq.template.MqTemplate;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * MQ 自动配置：业务模块引入依赖并配置好 rocketmq.producer.group 后，
 * 直接注入 {@link MqTemplate} 即可发消息，不用自己声明 Bean。
 *
 * <p>只做消费、不发消息的模块可以不配 producer.group：此时 RocketMQTemplate
 * 不会创建，MqTemplate 也不会注册，不影响消费者启动。
 */
@AutoConfiguration(afterName = "org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration")
@ConditionalOnClass(RocketMQTemplate.class)
public class MqAutoConfiguration {

    @Bean
    @ConditionalOnBean(RocketMQTemplate.class)
    public MqTemplate mqTemplate(RocketMQTemplate rocketMQTemplate) {
        return new MqTemplate(rocketMQTemplate);
    }

}
