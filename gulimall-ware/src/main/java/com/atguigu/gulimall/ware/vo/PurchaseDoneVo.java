package com.atguigu.gulimall.ware.vo;

import lombok.Data;

import java.util.List;

/**
 * 完成采购参数
 *
 * @author liujiaxu
 */
@Data
public class PurchaseDoneVo {

    /** 采购单 id（wms_purchase.id） */
    private Long id;

    /** 本次采购单下各采购需求的完成情况 */
    private List<PurchaseItemDoneVo> items;
}
