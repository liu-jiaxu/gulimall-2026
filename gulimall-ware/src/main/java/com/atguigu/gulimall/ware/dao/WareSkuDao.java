package com.atguigu.gulimall.ware.dao;

import com.atguigu.gulimall.ware.entity.WareSkuEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 商品库存
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 20:18:03
 */
@Mapper
public interface WareSkuDao extends BaseMapper<WareSkuEntity> {

    /** 某个 sku 在所有仓库的可用库存总和（对账用） */
    @Select("SELECT COALESCE(SUM(stock - COALESCE(stock_locked, 0) - COALESCE(seckill_stock_locked, 0)), 0) " +
            "FROM wms_ware_sku WHERE sku_id = #{skuId}")
    Long getAvailableStock(@Param("skuId") Long skuId);

}
