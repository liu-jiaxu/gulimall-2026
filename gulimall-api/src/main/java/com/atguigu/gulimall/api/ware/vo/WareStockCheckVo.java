package com.atguigu.gulimall.api.ware.vo;

import java.util.Map;

/**
 * 按指定仓库校验库存：skuId → 选中的仓库 id + 购买数量。
 */
public record WareStockCheckVo(Map<Long, Long> skuWareMap, Map<Long, Integer> skuQuantities) {
}
