package com.atguigu.gulimall.api.ware.vo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 运费计算结果：逐商品运输明细 + 整单总运费
 *
 * <p>不只是一个总运费数字。页面上要按商品展示「从哪个仓发、多远、预计多久」，
 * 光有总额做不到；真出了运费争议，也能直接看到每一段是怎么来的。
 *
 * @param fares 逐个商品的运输明细（发货地址、运输距离、运费、耗时消息）
 * @param totalFare 整单总运费
 */
public record FareResponseVo(List<FareItem> fares, BigDecimal totalFare) {
    /**
     * 商品运输明细
     * @param skuId skuId
     * @param wareId 选中的发货仓库 id
     * @param originAddress 发货地址
     * @param distance 运输距离（公里）
     * @param fare 运费（元）
     * @param timeInfo 耗时消息
     */
    public record FareItem(Long skuId, Long wareId, String originAddress,
                           BigDecimal distance, BigDecimal fare, String timeInfo) {
    }
}
