package com.atguigu.gulimall.ware.dao;

import com.atguigu.gulimall.ware.entity.SeckillStockOperationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 秒杀库存操作幂等记录
 */
@Mapper
public interface SeckillStockOperationDao extends BaseMapper<SeckillStockOperationEntity> {

    /**
     * 插入操作记录；operation_id 重复时返回 0，不抛异常
     */
    @Insert("INSERT IGNORE INTO wms_seckill_stock_operation " +
            "(operation_id, operation_type, sku_id, ware_id, quantity, create_time) " +
            "VALUES (#{operationId}, #{operationType}, #{skuId}, #{wareId}, #{quantity}, #{createTime})")
    int insertIgnore(SeckillStockOperationEntity operation);
}
