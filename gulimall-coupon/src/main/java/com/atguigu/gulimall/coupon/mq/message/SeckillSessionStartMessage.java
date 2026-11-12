package com.atguigu.gulimall.coupon.mq.message;

import lombok.Data;

import java.io.Serializable;

/**
 * 秒杀场次到点开始消息：到 start_time 后把场次改成进行中
 */
@Data
public class SeckillSessionStartMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long sessionId;

    /** 发送消息时的开始时间；场次时间改过后，旧消息按这个值判断要忽略 */
    private Long startTimeMillis;

    public SeckillSessionStartMessage() {
    }

    public SeckillSessionStartMessage(Long sessionId, Long startTimeMillis) {
        this.sessionId = sessionId;
        this.startTimeMillis = startTimeMillis;
    }
}
