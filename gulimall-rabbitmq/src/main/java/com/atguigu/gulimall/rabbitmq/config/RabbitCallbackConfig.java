package com.atguigu.gulimall.rabbitmq.config;

import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.atguigu.gulimall.rabbitmq.service.MqMessageService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;

/**
 * 发送端可靠性：注册 RabbitTemplate 的 confirm / return 回调。
 *
 * <p>对应 config/common.yaml 里的：
 * <pre>
 * spring.rabbitmq.publisher-confirm-type: correlated
 * spring.rabbitmq.publisher-returns: true
 * spring.rabbitmq.template.mandatory: true
 * </pre>
 *
 * <p>回调只负责把结果写回本地消息表 mq_message，<b>不在回调里重发</b> ——
 * 回调跑在 RabbitMQ 的 IO 线程上，直接重发会拖住连接线程；重发统一交给
 * {@code MqMessageCompensateTask} 定时扫描。
 *
 * <p>⚠️ 本类不能把 MessageConverter 也定义成 Bean：本类依赖 RabbitTemplate，
 * 而 RabbitTemplate 的构建又需要 MessageConverter，会形成 Bean 循环。
 * 转换器在 {@link RabbitConverterConfig}，它不依赖任何东西。
 */
@Slf4j
@Configuration
public class RabbitCallbackConfig {

    @Autowired
    @Qualifier("rabbitTemplate")
    private RabbitTemplate rabbitTemplate;

    @Autowired
    @Qualifier("delayedRabbitTemplate")
    private RabbitTemplate delayedRabbitTemplate;

    @Autowired
    private MqMessageService mqMessageService;

    @PostConstruct
    public void initRabbitTemplate() {
        registerConfirmCallback(rabbitTemplate);
        registerConfirmCallback(delayedRabbitTemplate);

        // return 只注册在普通模板上；延时模板 mandatory=false，不会收到退回
        rabbitTemplate.setReturnsCallback(returned -> {
            String messageId = (String) returned.getMessage().getMessageProperties()
                    .getHeaders().get(MqConstants.HEADER_MESSAGE_ID);
            mqMessageService.markFailed(messageId,
                    String.format("路由失败 replyCode[%s] replyText[%s] exchange[%s] routingKey[%s]",
                            returned.getReplyCode(), returned.getReplyText(),
                            returned.getExchange(), returned.getRoutingKey()));
        });
    }

    private void registerConfirmCallback(RabbitTemplate template) {
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (correlationData == null) {
                log.error("【MQ confirm】回调没有 CorrelationData，无法定位消息，cause={}", cause);
                return;
            }
            String messageId = correlationData.getId();
            if (ack) {
                mqMessageService.markConfirmed(messageId);
            } else {
                mqMessageService.markFailed(messageId, "Broker未确认(ack=false)，cause=" + cause);
            }
        });
    }

}
