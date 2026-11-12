package com.atguigu.gulimall.rabbitmq.dao;

import com.atguigu.gulimall.rabbitmq.entity.MqMessageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 本地消息表
 */
@Mapper
public interface MqMessageDao extends BaseMapper<MqMessageEntity> {

}
