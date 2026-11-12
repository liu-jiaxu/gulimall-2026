package com.atguigu.gulimall.rabbitmq.service.impl;

import com.atguigu.gulimall.rabbitmq.constant.MqConstants;
import com.atguigu.gulimall.rabbitmq.dao.MqDeadMessageDao;
import com.atguigu.gulimall.rabbitmq.entity.MqDeadMessageEntity;
import com.atguigu.gulimall.rabbitmq.entity.MqMessageEntity;
import com.atguigu.gulimall.rabbitmq.service.MqDeadMessageService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.AbstractJavaTypeMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 消费端失败归档 + 人工重放。
 */
@Slf4j
@Service("mqDeadMessageService")
public class MqDeadMessageServiceImpl extends ServiceImpl<MqDeadMessageDao, MqDeadMessageEntity>
        implements MqDeadMessageService {

    /** fail_reason 列 varchar(1024)，异常堆栈可能很长，存之前截断，避免归档本身失败 */
    private static final int MAX_REASON_LENGTH = 1000;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("rabbitTemplate")
    private RabbitTemplate rabbitTemplate;

    @Override
    public void archive(Message message, String failReason, int retryCount) {
        MessageProperties props = message.getMessageProperties();

        MqDeadMessageEntity entity = new MqDeadMessageEntity();
        Object messageId = props.getHeaders().get(MqConstants.HEADER_MESSAGE_ID);
        entity.setMessageId(messageId == null ? null : messageId.toString());

        // 原目的地：优先用 MqRetryServiceImpl 显式带的 mq_origin_*；
        // 兜底才用 received*（被 basicNack 直接丢进死信的情况没有 origin 头）
        String originExchange = readHeaderAsString(props, MqConstants.HEADER_ORIGIN_EXCHANGE);
        String originRoutingKey = readHeaderAsString(props, MqConstants.HEADER_ORIGIN_ROUTING_KEY);
        entity.setFromExchange(originExchange != null ? originExchange : props.getReceivedExchange());
        entity.setRoutingKey(originRoutingKey != null ? originRoutingKey : props.getReceivedRoutingKey());

        // 消息体类型，重放时把 JSON 还原成对象要用
        Object typeId = props.getHeaders().get(AbstractJavaTypeMapper.DEFAULT_CLASSID_FIELD_NAME);
        entity.setClassType(typeId == null ? null : typeId.toString());
        entity.setContent(new String(message.getBody(), StandardCharsets.UTF_8));
        entity.setHeaders(writeHeadersAsJson(props));
        entity.setFailReason(truncate(failReason));
        entity.setRetryCount(retryCount);
        entity.setCreateTime(new Date());
        entity.setUpdateTime(new Date());

        this.save(entity);

        log.error("【MQ归档】消息重试{}次仍失败，已落库 mq_dead_message，程序不再重试，转人工处理。"
                        + "messageId={} exchange={} routingKey={} 最后一次失败原因={}",
                retryCount, entity.getMessageId(), entity.getFromExchange(),
                entity.getRoutingKey(), failReason);
    }

    @Override
    public void archiveFromSendFailure(MqMessageEntity source, String failReason) {
        MqDeadMessageEntity entity = new MqDeadMessageEntity();
        entity.setMessageId(source.getMessageId());
        entity.setFromExchange(source.getToExchange());
        entity.setRoutingKey(source.getRoutingKey());
        entity.setClassType(source.getClassType());
        entity.setContent(source.getContent());
        // 发都没发出去，自然没有 AMQP 消息头；replay 会用 class_type 补出 __TypeId__
        entity.setHeaders(null);
        entity.setFailReason(truncate(failReason));
        entity.setRetryCount(source.getRetryCount());
        entity.setCreateTime(new Date());
        entity.setUpdateTime(new Date());

        this.save(entity);

        log.error("【MQ归档】消息重发{}次仍未送达 Broker（发送端失败），"
                        + "已从 mq_message 挪进 mq_dead_message，程序不再重试，转人工处理。"
                        + "messageId={} exchange={} routingKey={}",
                source.getRetryCount(), source.getMessageId(),
                source.getToExchange(), source.getRoutingKey());
    }

    @Override
    public int replayAll() {
        List<MqDeadMessageEntity> all = this.list();
        if (all.isEmpty()) {
            log.info("【MQ重放】死信表里没有记录，无需重放");
            return 0;
        }

        int success = 0;
        for (MqDeadMessageEntity entity : all) {
            try {
                replay(entity);
                // 重放成功 → 已经回到正常链路，从死信表删掉
                this.removeById(entity.getId());
                success++;
            } catch (Exception e) {
                // 单条失败不影响其它条，原样保留下次再试
                log.error("【MQ重放】重放失败，记录保留 id={} messageId={}",
                        entity.getId(), entity.getMessageId(), e);
            }
        }
        log.info("【MQ重放】共 {} 条，成功重放 {} 条", all.size(), success);
        return success;
    }

    /**
     * 把一条死信记录还原成消息，投回原始交换机。
     */
    private void replay(MqDeadMessageEntity entity) {
        if (entity.getFromExchange() == null || entity.getRoutingKey() == null) {
            throw new IllegalStateException("死信记录缺少原始交换机或路由键，无法确定投回哪里：id=" + entity.getId());
        }

        byte[] body = entity.getContent() == null
                ? new byte[0]
                : entity.getContent().getBytes(StandardCharsets.UTF_8);

        Map<String, Object> headers = readHeaders(entity.getHeaders());
        // ⚠️ 清掉上次失败留下的痕迹；不清重试次数，消息一回去再失败会直接判"到上限"又进死信
        headers.remove(MqConstants.HEADER_RETRY_COUNT);
        headers.remove(MqConstants.HEADER_FAIL_REASON);
        headers.remove(MqConstants.HEADER_ORIGIN_EXCHANGE);
        headers.remove(MqConstants.HEADER_ORIGIN_ROUTING_KEY);
        headers.remove(MqConstants.HEADER_DELAY);
        if (!headers.containsKey(AbstractJavaTypeMapper.DEFAULT_CLASSID_FIELD_NAME)
                && entity.getClassType() != null) {
            headers.put(AbstractJavaTypeMapper.DEFAULT_CLASSID_FIELD_NAME, entity.getClassType());
        }

        MessageProperties props = new MessageProperties();
        headers.forEach(props::setHeader);
        // contentType 不在 headers 里（是独立属性），必须补回来，否则消费端认不出 JSON
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());

        rabbitTemplate.send(entity.getFromExchange(), entity.getRoutingKey(),
                new Message(body, props));

        log.info("【MQ重放】已投回 messageId={} exchange={} routingKey={}",
                entity.getMessageId(), entity.getFromExchange(), entity.getRoutingKey());
    }

    private Map<String, Object> readHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(headersJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("死信记录的 headers 解析失败，重放时不带任何原始头部：{}", e.getMessage());
            return new HashMap<>();
        }
    }

    private String writeHeadersAsJson(MessageProperties props) {
        try {
            return objectMapper.writeValueAsString(props.getHeaders());
        } catch (Exception e) {
            log.warn("消息头序列化成JSON失败，退化为toString，原因={}", e.getMessage());
            return String.valueOf(props.getHeaders());
        }
    }

    private String readHeaderAsString(MessageProperties props, String headerName) {
        Object value = props.getHeaders().get(headerName);
        return value == null ? null : value.toString();
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_REASON_LENGTH ? text : text.substring(0, MAX_REASON_LENGTH);
    }

}
