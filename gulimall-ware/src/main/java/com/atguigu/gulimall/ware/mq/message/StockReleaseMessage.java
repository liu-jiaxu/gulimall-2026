package com.atguigu.gulimall.ware.mq.message;

import lombok.Data;

import java.io.Serializable;

/**
 * 库存解锁延时消息。
 *
 * <p>消息里只带工作单 id 和订单号，不带商品明细：
 * 明细在 ware 自己的库里，消费时按 taskId 查即可，消息体越小越不容易出错。
 */
@Data
public class StockReleaseMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 库存工作单 id（wms_ware_order_task.id） */
    private Long taskId;

    /** 订单号，只用于日志排查 */
    private String orderSn;

}
