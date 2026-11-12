package com.atguigu.gulimall.product.service;

import com.atguigu.gulimall.product.entity.BrandEntity;
import com.baomidou.mybatisplus.extension.service.IService;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.product.entity.CategoryBrandRelationEntity;

import java.util.List;
import java.util.Map;

/**
 * 品牌分类关联
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 17:11:04
 */
public interface CategoryBrandRelationService extends IService<CategoryBrandRelationEntity> {

    PageUtils queryPage(Map<String, Object> params);

    /**
     * 检查是否存在相同的品牌和分类记录
     * @param brandId 品牌ID
     * @param catelogId 分类ID
     * @return 相同记录的数量
     */
    long checkSameRecord(Long brandId, Long catelogId);

    /**
     * 保存品牌和分类关联关系的详细信息
     * @param categoryBrandRelation 品牌分类关联实体对象
     */
    void saveDetails(CategoryBrandRelationEntity categoryBrandRelation);

    List<BrandEntity> getBrandsByCatId(Long catId);
}

