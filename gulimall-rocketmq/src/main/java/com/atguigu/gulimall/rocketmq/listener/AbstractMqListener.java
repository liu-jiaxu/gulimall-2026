package com.atguigu.gulimall.rocketmq.listener;

import com.alibaba.fastjson2.JSON;
import org.apache.rocketmq.spring.core.RocketMQListener;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * 消费端半封装基类：业务模块只需要继承本类并实现 {@link #handle(Object)}，
 * 不用自己解析 JSON，也不用碰 RocketMQ 的底层 API。
 *
 * <p>用法（注意类上仍要保留一行原生注解，Topic / Tag / 消费者组用 MqConstants 常量）：
 * <pre>{@code
 * @Component
 * @RocketMQMessageListener(
 *         topic = MqConstants.TOPIC_XXX,
 *         selectorExpression = MqConstants.TAG_XXX,
 *         consumerGroup = MqConstants.GROUP_XXX)
 * public class XxxListener extends AbstractMqListener<XxxMessage> {
 *     @Override
 *     protected void handle(XxxMessage message) {
 *         // 业务处理
 *     }
 * }
 * }</pre>
 *
 * <p>泛型消息类型在构造时自动解析；业务方法抛异常时异常会传给 RocketMQ，
 * 由 RocketMQ 按消费者组配置的重试次数重投（所以业务处理必须幂等）。
 *
 * @param <T> 消息体类型
 */
public abstract class AbstractMqListener<T> implements RocketMQListener<String> {

    private final Class<T> payloadType;

    @SuppressWarnings("unchecked")
    protected AbstractMqListener() {
        this.payloadType = (Class<T>) resolvePayloadType(getClass());
    }

    @Override
    public final void onMessage(String message) {
        T payload = JSON.parseObject(message, payloadType);
        handle(payload);
    }

    /** 业务处理入口 */
    protected abstract void handle(T payload);

    /**
     * 从类继承链里找出 {@code AbstractMqListener<Xxx>} 的泛型实参。
     *
     * <p>不能直接用 {@code getClass().getGenericSuperclass()}：如果业务 Bean 被
     * CGLIB 代理（例如加了事务/切面），getClass() 拿到的是代理类，往上走一层才是业务类。
     */
    private static Class<?> resolvePayloadType(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            Type superType = current.getGenericSuperclass();
            if (superType instanceof ParameterizedType parameterizedType
                    && parameterizedType.getRawType() == AbstractMqListener.class) {
                Type actualType = parameterizedType.getActualTypeArguments()[0];
                if (actualType instanceof Class<?> actualClass) {
                    return actualClass;
                }
                if (actualType instanceof ParameterizedType actualParameterized) {
                    return (Class<?>) actualParameterized.getRawType();
                }
                break;
            }
            current = current.getSuperclass();
        }
        throw new IllegalArgumentException(
                "无法解析消息类型，请确认监听器直接继承了 AbstractMqListener<具体消息类>：" + clazz.getName());
    }

}
