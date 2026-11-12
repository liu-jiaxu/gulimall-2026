package com.atguigu.gulimall.order.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 后台交易统计。
 *
 * <p>统计口径：已支付订单（status 1/2/3），时间按 payment_time 过滤。
 */
@Data
public class OrderStatVo {

    /** 交易额合计 */
    private BigDecimal totalAmount;

    /** 订单数 */
    private Long orderCount;

    /** 商品件数 */
    private Long itemCount;

    /** 每日交易趋势 */
    private List<TrendPoint> dailyTrend;

    /** 商品销量 Top N */
    private List<ProductRank> topProducts;

    /** 订单状态分布 */
    private List<NameValue> statusDistribution;

    /** 支付方式分布 */
    private List<NameValue> payTypeDistribution;

    @Data
    public static class TrendPoint {
        private String date;
        private BigDecimal amount;
        private Long orderCount;
    }

    @Data
    public static class ProductRank {
        private Long skuId;
        private String skuName;
        private Long quantity;
        private BigDecimal amount;
    }

    @Data
    public static class NameValue {
        private String name;
        private Long value;
    }
}
