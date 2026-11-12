package com.atguigu.gulimall.rocketmq.constant;

/**
 * MQ 用到的 Topic / Tag / 消费者组，集中定义，避免各模块各写一份字符串。
 *
 * <p>RocketMQ 没有「交换机 / 路由绑定」的概念，消息按 Topic 归类、按 Tag 过滤；
 * Topic 本身在开发环境交给 broker 自动创建，生产环境由运维提前建好。
 */
public final class MqConstants {

    private MqConstants() {
    }

    // ==================== 库存解锁 ====================

    /** 库存解锁 Topic：下单锁库存后发延时消息，到期检查工作单是否还在「已锁定」状态 */
    public static final String TOPIC_STOCK_RELEASE = "gulimall-stock-release";

    /** 库存解锁 Tag */
    public static final String TAG_STOCK_RELEASE = "release";

    /** 库存解锁消费者组（同一组的多个实例分摊消息，不会重复消费） */
    public static final String GROUP_STOCK_RELEASE = "gulimall-ware-stock-release-consumer";

}
