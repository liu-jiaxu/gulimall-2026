package com.atguigu.gulimall.api.thirdparty;

import java.math.BigDecimal;

/**
 * 某个收货地址的距离计算结果
 *
 * <p>同一个地址上的多件商品，返回时共用同一个实例（地址只算一次）。
 *
 * @param address  清洗后的收货地址 —— 回传一份是为了让调用方核对"算的确实是我给的那个地址"，
 *                 地址清洗（去空格、「-」等）发生在服务端，调用方手里的原文和这里可能不完全一样
 * @param distance 驾车距离（公里，两位小数）
 */
public record LogisticsDistanceItem(String address, BigDecimal distance) {
}
