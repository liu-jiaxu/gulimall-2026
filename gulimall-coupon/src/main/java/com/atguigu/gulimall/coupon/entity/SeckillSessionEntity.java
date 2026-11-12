package com.atguigu.gulimall.coupon.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 秒杀活动场次
 * 
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 19:44:40
 */
@Data
@TableName("sms_seckill_session")
public class SeckillSessionEntity implements Serializable {
	private static final long serialVersionUID = 1L;

	/**
	 * id
	 */
	@TableId
	private Long id;
	/**
	 * 秒杀活动id
	 */
	private Long promotionId;
	/**
	 * 场次名称
	 */
	private String name;
	/**
	 * 开始时间
	 */
	private Date startTime;
	/**
	 * 结束时间
	 */
	private Date endTime;
	/**
	 * 启用状态
	 */
	private Integer status;
	/**
	 * 秒杀状态：0未开始 1进行中 2停止中 3已结束 4已取消
	 */
	private Integer seckillState;
	/**
	 * 提前下架时间
	 */
	private Date stopTime;
	/**
	 * 停止后最终状态：1已结束 2已取消
	 */
	private Integer settleType;
	/**
	 * 创建时间
	 */
	private Date createTime;

}
