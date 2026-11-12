package com.atguigu.gulimall.seckill.dao;

import com.atguigu.gulimall.seckill.entity.SeckillOrderEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 秒杀订单预扣记录
 */
@Mapper
public interface SeckillOrderDao extends BaseMapper<SeckillOrderEntity> {
}
