package com.atguigu.gulimall.api.ware.vo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 运费计算入参：收货地址 + 商品清单
 *
 * <p><b>为什么另建一个精简类、而不是直接复用 {@code OrderItemVo}：</b>
 * 页面上的商品对象带着图片、标题、销售属性等一大堆字段，算运费一个都用不上。
 * 用这个类只传「skuId + 件数 + 重量」，请求体小一个数量级 ——
 * 那些字段在浏览器和服务器之间来回搬纯属浪费，也让接口的意图变得模糊。
 *
 * <p>放在 gulimall-api 是因为两边都要用它：order（页面所在服务）负责接收和转发，
 * ware（真正算运费的服务）负责消费。
 *
 * @param addrId 收货地址 id（member 服务的收货地址主键）
 * @param items  要算运费的商品清单
 */
public record FareRequestVo(Long addrId, List<Item> items) {

    /**
     * 单个商品：算运费只需要这三样
     *
     * @param skuId  商品 id
     * @param count  购买件数
     * @param weight 单件重量（与仓储侧保持一致，一般是 kg）
     */
    public record Item(Long skuId, Integer count, BigDecimal weight) {
    }

}
