package com.atguigu.gulimall.ware.vo;

import lombok.Data;

/**
 * 完成采购——单条采购需求的结果
 *
 * @author liujiaxu
 */
@Data
public class PurchaseItemDoneVo {

    /** 采购需求 id（wms_purchase_detail.id） */
    private Long itemId;

    /** 采购需求最终状态：3 已完成 / 4 采购失败 */
    private Integer status;

    /** 失败原因 */
    private String reason;
}
