package com.atguigu.gulimall.seckill.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 秒杀订单预扣记录：抢购时先落这一行，取消/超时靠它把库存还给原场次。
 */
@Data
@TableName("sms_seckill_order")
public class SeckillOrderEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    /** 秒杀订单号，全链路幂等键 */
    private String orderSn;

    /** 客户端请求 id，防重复提交 */
    private String requestId;

    /** oms_order.id，建单成功后回填 */
    private Long orderId;

    private Long memberId;

    private String memberUsername;

    private Long sessionId;

    private Long skuId;

    private String skuName;

    private String skuPic;

    /** 关联时锁定的仓库 id */
    private Long wareId;

    private Integer quantity;

    private BigDecimal seckillPrice;

    private BigDecimal payAmount;

    private Long addrId;

    private Integer payType;

    /** 0待建单 1已建单 2已释放 3建单失败 4已支付 */
    private Integer status;

    /** 释放方式：1回秒杀池 2回普通池 */
    private Integer releaseMode;

    private String releaseReason;

    private String failReason;

    private Integer buildRetryCount;

    private Date createTime;

    private Date updateTime;
}
