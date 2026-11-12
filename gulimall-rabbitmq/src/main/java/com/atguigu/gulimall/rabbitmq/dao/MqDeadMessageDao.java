package com.atguigu.gulimall.rabbitmq.dao;

import com.atguigu.gulimall.rabbitmq.entity.MqDeadMessageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消费端失败归档表
 */
@Mapper
public interface MqDeadMessageDao extends BaseMapper<MqDeadMessageEntity> {

}
