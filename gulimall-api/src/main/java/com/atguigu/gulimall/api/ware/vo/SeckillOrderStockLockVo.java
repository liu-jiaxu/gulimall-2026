package com.atguigu.gulimall.api.ware.vo;

import lombok.Data;

/**
 * 秒杀订单建单时，把活动锁定的库存转成订单锁定。
 */
@Data
public class SeckillOrderStockLockVo {

    private Long orderId;

    private String orderSn;

    private Long sessionId;

    private Long memberId;

    private Long skuId;

    /** 关联时锁定的仓库 id */
    private Long wareId;

    private Integer quantity;
}
