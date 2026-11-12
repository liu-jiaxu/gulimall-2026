package com.atguigu.gulimall.rabbitmq.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 两个发送模板：
 * <ul>
 *   <li>rabbitTemplate：普通消息，mandatory=true，能收到真实路由失败</li>
 *   <li>delayedRabbitTemplate：延时消息，mandatory=false，避开延时插件的误退回</li>
 * </ul>
 */
@Configuration
public class RabbitTemplateConfig {

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                          MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setMandatory(true);
        return template;
    }

    @Bean
    public RabbitTemplate delayedRabbitTemplate(ConnectionFactory connectionFactory,
                                                 MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setMandatory(false);
        return template;
    }

}
