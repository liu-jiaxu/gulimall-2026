package com.atguigu.gulimall.api.thirdparty;

import java.math.BigDecimal;

/**
 * 单个商品的距离结果
 *
 * <p><b>skuId 直接放在这个 record 里，而不是用 {@code Map<Long, DistanceItem>} 包一层：</b>
 * 返回一个普通 List 更省事 —— 调用方既能按 skuId 找，也能直接遍历；
 * 序列化成 JSON 后的结构也更平：{@code [{skuId:1, distance:21.87}, ...]}
 * 比起 {@code {"1": {...}}} 好处理（后者的 key 到了 JS 里全变成字符串，还得手工转回来）。
 *
 * <p>把两个地址一起回传是为了排查方便：某个 sku 的距离明显不对时，
 * 直接看结果就知道它当时用的是哪对地址，不用再去翻请求、翻日志对账。
 *
 * @param skuId              商品 id
 * @param wareId             发货仓库 id（一个 sku 多仓时用来区分）
 * @param originAddress      该仓库的发货地址
 * @param destinationAddress 收货地址
 * @param distance           驾车距离，单位<b>公里</b>，保留两位小数
 * @param duration           预计耗时，单位<b>秒</b>（百度原始单位，没做换算）。
 *                           百度偶尔不返回这个字段，取不到时为 {@code null}，调用方记得判空
 */
public record DistanceItem(Long skuId, Long wareId, String originAddress, String destinationAddress,
                           BigDecimal distance, Long duration) {
}
