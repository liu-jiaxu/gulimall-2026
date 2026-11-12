package com.atguigu.gulimall.api.ware.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 下单锁库存入参：订单号 + 每个 sku 要锁定的数量。
 *
 * <p>放在 gulimall-api 是因为 order（调用方）和 ware（被调用方）都要用；
 * 工作单必须能关联回订单，所以不能只传 skuId → 数量。
 */
@Data
public class StockLockVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单 id（wms_ware_order_task.order_id，便于排查问题） */
    private Long orderId;

    /** 订单号（wms_ware_order_task.order_sn，工作单的业务主键） */
    private String orderSn;

    /** skuId → 本次要锁定的数量 */
    private Map<Long, Integer> skuQuantities;

    /** skuId → 运费页选中的仓库 id（必须传） */
    private Map<Long, Long> skuWareMap;

}
