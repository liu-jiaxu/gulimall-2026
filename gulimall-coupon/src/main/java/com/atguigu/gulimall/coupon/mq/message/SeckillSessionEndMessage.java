package com.atguigu.gulimall.coupon.mq.message;

import lombok.Data;

import java.io.Serializable;

/**
 * 秒杀场次到点结束消息：到 end_time 后把场次改成停止中
 */
@Data
public class SeckillSessionEndMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long sessionId;

    /** 发送消息时的结束时间；场次时间改过后，旧消息按这个值判断要忽略 */
    private Long endTimeMillis;

    public SeckillSessionEndMessage() {
    }

    public SeckillSessionEndMessage(Long sessionId, Long endTimeMillis) {
        this.sessionId = sessionId;
        this.endTimeMillis = endTimeMillis;
    }
}
