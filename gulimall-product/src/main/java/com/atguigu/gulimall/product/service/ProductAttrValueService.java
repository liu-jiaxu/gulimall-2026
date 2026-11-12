package com.atguigu.gulimall.product.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.product.entity.ProductAttrValueEntity;

import java.util.List;
import java.util.Map;

/**
 * spu属性值
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 17:11:04
 */
public interface ProductAttrValueService extends IService<ProductAttrValueEntity> {

    PageUtils queryPage(Map<String, Object> params);

    /**
     * 查询 spu 的规格属性
     */
    List<ProductAttrValueEntity> baseAttrListForSpu(Long spuId);

    /**
     * 修改 spu 的规格属性
     */
    void updateSpuAttr(Long spuId, List<ProductAttrValueEntity> entities);
}

