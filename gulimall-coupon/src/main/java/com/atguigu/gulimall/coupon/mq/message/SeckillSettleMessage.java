package com.atguigu.gulimall.coupon.mq.message;

import lombok.Data;

import java.io.Serializable;

/**
 * 秒杀结束结算消息：宽限期到期后按场次 id 结算
 */
@Data
public class SeckillSettleMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long sessionId;

    public SeckillSettleMessage() {
    }

    public SeckillSettleMessage(Long sessionId) {
        this.sessionId = sessionId;
    }
}
