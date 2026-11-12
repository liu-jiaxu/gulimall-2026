package com.atguigu.gulimall.ware.vo;

import lombok.Data;

import java.util.List;

/**
 * 合并采购需求参数
 *
 * @author liujiaxu
 */
@Data
public class MergeVo {

    /** 整单id：为空表示没有选中已有采购单，需要新建一个 */
    private Long purchaseId;

    /** 要合并的采购需求（wms_purchase_detail 的 id）集合 */
    private List<Long> items;
}
