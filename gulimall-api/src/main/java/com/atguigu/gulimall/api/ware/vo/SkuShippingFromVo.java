package com.atguigu.gulimall.api.ware.vo;

import lombok.Data;

/**
 * @ClassName: SkuShippingFromVo
 * @Package: com.atguigu.gulimall.api.ware.vo
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/16 - 23:09
 * @Version: v1.0
 */
@Data
public class SkuShippingFromVo {
    private Long skuId;
    private Long wareId;
    private String address;
    /** 该仓库可用库存（stock - stock_locked） */
    private Integer availableStock;
}
