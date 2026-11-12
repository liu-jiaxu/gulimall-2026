package com.atguigu.gulimall.ware.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 秒杀库存操作幂等记录
 */
@Data
@TableName("wms_seckill_stock_operation")
public class SeckillStockOperationEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作唯一ID，coupon 每次调 ware 前生成 */
    private String operationId;

    /** LOCK / RELEASE */
    private String operationType;

    private Long skuId;

    private Long wareId;

    private Integer quantity;

    private Date createTime;
}
