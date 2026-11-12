package com.atguigu.gulimall.rabbitmq.config;

import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 消息转换器：对象 &lt;-&gt; JSON，以及反序列化白名单。
 *
 * <p>消费端按消息头 {@code __TypeId__} 把 JSON 还原成对象，这是危险动作，
 * 所以 Spring AMQP 默认只信任 {@code java.util} / {@code java.lang}。
 * 业务消息所在包必须由使用方通过
 * {@code gulimall.rabbitmq.trusted-packages} 显式声明：
 * <pre>
 * gulimall:
 *   rabbitmq:
 *     trusted-packages: com.atguigu.gulimall.ware.mq.message,java.math,java.time
 * </pre>
 * 不要写 {@code "*"} —— 那等于关掉反序列化防线。
 *
 * <p>⚠️ 转换器单独一个类、不依赖 RabbitTemplate：否则
 * RabbitCallbackConfig（依赖 RabbitTemplate）→ MessageConverter → RabbitTemplateConfigurer
 * 会形成 Bean 循环，应用启动直接失败。
 */
@Configuration
public class RabbitConverterConfig {

    @Value("${gulimall.rabbitmq.trusted-packages:java.time}")
    private String trustedPackages;

    @Bean
    public MessageConverter messageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        DefaultJackson2JavaTypeMapper javaTypeMapper = new DefaultJackson2JavaTypeMapper();
        if (StringUtils.hasText(trustedPackages)) {
            javaTypeMapper.setTrustedPackages(
                    StringUtils.commaDelimitedListToStringArray(trustedPackages));
        }
        converter.setJavaTypeMapper(javaTypeMapper);
        return converter;
    }

}
