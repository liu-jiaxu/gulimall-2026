package com.atguigu.gulimall.seckill.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 秒杀活动商品关联
 */
@Data
@TableName("sms_seckill_sku_relation")
public class SeckillSkuRelationEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private Long promotionId;

    private Long promotionSessionId;

    private Long skuId;

    /** 关联时实际锁定的仓库 id */
    private Long wareId;

    private BigDecimal seckillPrice;

    /** 秒杀总量 */
    private Integer seckillCount;

    /** 每人限购数量 */
    private Integer seckillLimit;

    private Integer seckillSort;

    /** 乐观锁版本 */
    private Integer version;
}
