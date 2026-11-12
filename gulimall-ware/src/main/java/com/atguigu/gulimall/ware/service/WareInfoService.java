package com.atguigu.gulimall.ware.service;

import com.atguigu.gulimall.api.ware.vo.FareRequestVo;
import com.atguigu.gulimall.api.ware.vo.FareResponseVo;
import com.atguigu.gulimall.api.ware.vo.SkuShippingFromVo;
import com.baomidou.mybatisplus.extension.service.IService;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.ware.entity.WareInfoEntity;

import java.util.List;
import java.util.Map;

/**
 * 仓库信息
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 20:18:03
 */
public interface WareInfoService extends IService<WareInfoEntity> {

    PageUtils queryPage(Map<String, Object> params);

    PageUtils queryPageByCondition(Map<String, Object> params);

    /** 算运费：入参是收货地址 + 商品清单，出参是逐商品明细 + 整单总运费 */
    FareResponseVo getFare(FareRequestVo fareRequestVo);

    /** 查询商品可发货的仓库及可用库存 */
    List<SkuShippingFromVo> getShippingFrom(List<FareRequestVo.Item> items);
}

