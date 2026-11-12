package com.atguigu.gulimall.seckill.vo;

import lombok.Data;

/**
 * Redis 抢购预扣结果
 */
@Data
public class SeckillReserveResult {

    /** 1成功 2重复请求 -1库存key不存在 -2库存不足 -3超限购 -4未开始 -5已结束 */
    private int code;

    /** 成功时是剩余库存 */
    private long left;

    /** 重复请求时的原订单号 */
    private String existingOrderSn;

    public boolean success() {
        // 1. 状态码为 1 表示这次预扣成功，调用方据此继续发建单消息
        return code == 1;
    }

    public boolean duplicate() {
        // 1. 状态码为 2 表示重复抢购，可以拿 existingOrderSn 告诉前端原订单号
        return code == 2;
    }
}
