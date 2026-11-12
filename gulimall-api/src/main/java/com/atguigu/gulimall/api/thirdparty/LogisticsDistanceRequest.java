package com.atguigu.gulimall.api.thirdparty;

import java.util.List;
import java.util.Map;

/**
 * 物流距离计算入参：一个收货地址 + 每个商品的候选仓库
 *
 * <p><b>为什么按 skuId 组织发货地址：</b>一个订单里的商品常常从<b>不同仓库</b>发出
 * （比如手机从 A 仓、配件从 B 仓），每个商品的实际运输距离都不一样，算运费时得能按商品对号入座。
 * 一个 sku 可能在多个仓库有货，所以每个 sku 可以带多个候选仓；返回结果按
 * 「每个 sku × 每个候选仓」各一条，调用方自己选最优仓。
 *
 * <p>收货地址只有一个 —— 一个订单只送到一个地方。
 *
 * <p>地址写法比较宽容（百度自带分词和行政区划匹配）：
 * <ul>
 *   <li>直辖市省掉"市"：「北京市朝阳区颐堤港购物中心」</li>
 *   <li>多级市 / 县级市：「山东省潍坊市诸城市东武街」</li>
 *   <li>带分隔符：「山东省-潍坊市-青州市-青州古城」—— 服务端会先把「-」这类符号清掉再调百度</li>
 * </ul>
 *
 * <p>⚠️ <b>地址不能过于粗略</b>：只给到区县（如「北京市朝阳区」）时，百度返回的是该行政区
 * <b>中心点</b>，算出来的距离是"中心点到中心点"，不是真实里程。接口不会报错（百度一样返回
 * status=0），只会在服务端日志里留下一条"解析可信度偏低"的告警。
 *
 * @param destination 收货地址（一个订单一个）
 * @param skuOrigins  候选发货仓列表：一个 sku 可以有多个仓库
 */
public record LogisticsDistanceRequest(String destination, List<SkuOrigin> skuOrigins) {

    /**
     * 一个候选发货仓
     *
     * @param skuId  商品 id
     * @param wareId 仓库 id
     * @param address 仓库地址
     */
    public record SkuOrigin(Long skuId, Long wareId, String address) {
    }

    /** 兼容只传 skuId → 地址的旧调用 */
    public LogisticsDistanceRequest(String destination, Map<Long, String> skuOrigins) {
        this(destination, skuOrigins == null ? List.of() : skuOrigins.entrySet().stream()
                .map(entry -> new SkuOrigin(entry.getKey(), null, entry.getValue()))
                .toList());
    }

}
