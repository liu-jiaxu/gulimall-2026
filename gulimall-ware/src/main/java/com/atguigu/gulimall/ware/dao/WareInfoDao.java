package com.atguigu.gulimall.ware.dao;

import com.atguigu.gulimall.api.ware.vo.FareRequestVo;
import com.atguigu.gulimall.api.ware.vo.SkuShippingFromVo;
import com.atguigu.gulimall.ware.entity.WareInfoEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 仓库信息
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 20:18:03
 */
@Mapper
public interface WareInfoDao extends BaseMapper<WareInfoEntity> {

    List<SkuShippingFromVo> getShippingFromList(@Param("items") List<FareRequestVo.Item> items);

}
