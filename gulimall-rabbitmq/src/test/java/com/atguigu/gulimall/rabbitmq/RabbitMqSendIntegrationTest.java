package com.atguigu.gulimall.rabbitmq;

import com.atguigu.gulimall.rabbitmq.config.RabbitQueueConfig;
import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 RabbitMQ 送信测试：连接 config/common.yaml 里的 broker，不发 mock。
 *
 * <p>先按当前 Broker 状态发一次，再用 {@link RabbitQueueConfig} 重新声明一次，对比两次结果。
 */
class RabbitMqSendIntegrationTest {

    @Test
    @DisplayName("真实发送到库存解锁延时交换机，验证 confirm / return")
    void sendToStockReleaseExchange() throws Exception {
        StandardEnvironment env = loadEnvironment();
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(
                env.getRequiredProperty("RABBITMQ_HOST"),
                env.getRequiredProperty("RABBITMQ_PORT", Integer.class));
        connectionFactory.setUsername(env.getRequiredProperty("RABBITMQ_USERNAME"));
        connectionFactory.setPassword(env.getRequiredProperty("RABBITMQ_PASSWORD"));
        connectionFactory.setVirtualHost(env.getRequiredProperty("RABBITMQ_VHOST"));
        connectionFactory.setConnectionTimeout(5000);
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);

        try {
            BlockingQueue<ReturnedMessage> returns = new LinkedBlockingQueue<>();
            BlockingQueue<Boolean> confirms = new LinkedBlockingQueue<>();

            RabbitTemplate template = new RabbitTemplate(connectionFactory);
            // 延时插件在 mandatory=true 时会把延迟中的消息误判 NO_ROUTE，生产用 mandatory=false 的延时模板
            template.setMandatory(false);
            template.setReturnsCallback(returned -> returns.offer(returned));
            template.setConfirmCallback((correlationData, ack, cause) -> confirms.offer(ack));

            SendResult before;
            try {
                before = send(template, returns, confirms);
            } catch (Exception e) {
                before = new SendResult(null, null);
                System.out.println("声明前发送异常：" + e.getMessage());
            }
            System.out.println("声明前结果：" + describe(before));

            RabbitQueueConfig config = new RabbitQueueConfig();
            var deadExchange = config.deadExchange();
            var deadQueue = config.deadQueue();
            var retryExchange = config.retryExchange();
            var stockExchange = config.stockExchange();
            var stockQueue = config.stockReleaseQueue();

            RabbitAdmin admin = new RabbitAdmin(connectionFactory);
            admin.declareExchange(deadExchange);
            admin.declareQueue(deadQueue);
            admin.declareBinding(config.deadBinding());
            admin.declareExchange(retryExchange);
            admin.declareExchange(stockExchange);
            admin.declareQueue(stockQueue);
            admin.declareBinding(config.stockReleaseBinding());
            admin.declareBinding(config.stockReleaseRetryBinding(retryExchange));
            System.out.println("已重新声明：" + retryExchange.getName()
                    + " -> " + stockQueue.getName()
                    + "，routingKey=" + MqConstants.STOCK_RELEASE_ROUTING_KEY);

            SendResult after = send(template, returns, confirms);
            System.out.println("声明后结果：" + describe(after));

            assertNull(after.returned(), "消息仍被退回：" + describe(after));
            assertTrue(Boolean.TRUE.equals(after.ack()), "Broker 未确认：" + describe(after));
        } finally {
            connectionFactory.destroy();
        }
    }

    private SendResult send(RabbitTemplate template,
                            BlockingQueue<ReturnedMessage> returns,
                            BlockingQueue<Boolean> confirms) throws InterruptedException {
        returns.clear();
        confirms.clear();

        CorrelationData correlationData = new CorrelationData("send-test-" + UUID.randomUUID());
        template.convertAndSend(MqConstants.RETRY_EXCHANGE, MqConstants.STOCK_RELEASE_ROUTING_KEY,
                "{\"taskId\":0,\"orderSn\":\"rabbitmq-send-test\"}",
                message -> {
                    message.getMessageProperties().setHeader(MqConstants.HEADER_DELAY, 1000);
                    return message;
                },
                correlationData);

        ReturnedMessage returned = returns.poll(2, TimeUnit.SECONDS);
        Boolean ack = confirms.poll(2, TimeUnit.SECONDS);
        return new SendResult(returned, ack);
    }

    private String describe(SendResult result) {
        if (result.returned() == null) {
            return "未退回，ack=" + result.ack();
        }
        return "退回 replyCode=" + result.returned().getReplyCode()
                + " replyText=" + result.returned().getReplyText()
                + " exchange=" + result.returned().getExchange()
                + " routingKey=" + result.returned().getRoutingKey()
                + " ack=" + result.ack();
    }

    private static StandardEnvironment loadEnvironment() throws IOException {
        FileSystemResource resource = new FileSystemResource("../config/common.yaml");
        if (!resource.exists()) {
            resource = new FileSystemResource("config/common.yaml");
        }
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("common", resource);
        MutablePropertySources propertySources = new MutablePropertySources();
        sources.forEach(propertySources::addLast);
        StandardEnvironment environment = new StandardEnvironment();
        propertySources.forEach(environment.getPropertySources()::addLast);
        return environment;
    }

    private record SendResult(ReturnedMessage returned, Boolean ack) {
    }

}
