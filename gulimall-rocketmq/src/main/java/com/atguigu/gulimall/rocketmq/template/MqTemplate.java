package com.atguigu.gulimall.rocketmq.template;

import com.alibaba.fastjson2.JSON;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

/**
 * MQ 发送封装：业务模块只依赖本类，不直接碰 RocketMQTemplate。
 *
 * <p>消息体统一序列化成 JSON 字符串发送，消费端由
 * {@link com.atguigu.gulimall.rocketmq.listener.AbstractMqListener} 统一反序列化，
 * 避免各模块自己处理消息转换器。
 */
public class MqTemplate {

    /** 同步发送超时（毫秒） */
    private static final long SEND_TIMEOUT_MILLIS = 3000;

    private final RocketMQTemplate rocketMQTemplate;

    public MqTemplate(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    /** 同步发送普通消息 */
    public void send(String topic, String tag, Object payload) {
        rocketMQTemplate.syncSend(destination(topic, tag), buildMessage(payload), SEND_TIMEOUT_MILLIS);
    }

    /**
     * 同步发送延时消息
     *
     * <p>RocketMQ 5.x 支持任意毫秒延时；4.x 只支持固定的 18 个延时级别。
     * 这里用的是 {@code syncSendDelayTimeMills}，要求 broker 是 5.x 且开启了定时消息。
     *
     * @param delayMillis 延时毫秒数
     */
    public void sendDelay(String topic, String tag, Object payload, long delayMillis) {
        rocketMQTemplate.syncSendDelayTimeMills(destination(topic, tag), buildMessage(payload), delayMillis);
    }

    /** 消息体转 JSON；如果调用方已经给了 JSON 字符串就原样发送，避免二次转义 */
    private Message<String> buildMessage(Object payload) {
        String body = payload instanceof String str ? str : JSON.toJSONString(payload);
        return MessageBuilder.withPayload(body).build();
    }

    /** RocketMQ 的目的地格式：topic:tag */
    private String destination(String topic, String tag) {
        return (tag == null || tag.isEmpty()) ? topic : topic + ":" + tag;
    }

}
