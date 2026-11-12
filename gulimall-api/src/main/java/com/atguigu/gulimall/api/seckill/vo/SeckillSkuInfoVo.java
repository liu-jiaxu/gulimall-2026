package com.atguigu.gulimall.api.seckill.vo;

import lombok.Data;

/**
 * 商品详情页秒杀模式需要的完整信息
 */
@Data
public class SeckillSkuInfoVo {

    private SeckillSkuVo sku;

    /** Redis 里当前剩余库存 */
    private Integer stock;

    /** 当前会员已购数量，游客为 0 */
    private Integer bought;

    /** 每人限购 */
    private Integer limit;
}
