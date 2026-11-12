package com.atguigu.gulimall.seckill.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 秒杀活动场次
 */
@Data
@TableName("sms_seckill_session")
public class SeckillSessionEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private String name;

    private Date startTime;

    private Date endTime;

    /** 启用状态：1启用 0下架 */
    private Integer status;

    /** 秒杀状态：0未开始 1进行中 2停止中 3已结束 4已取消 */
    private Integer seckillState;

    private Date stopTime;

    /** 停止后最终状态：1已结束 2已取消 */
    private Integer settleType;

    private Date createTime;
}
