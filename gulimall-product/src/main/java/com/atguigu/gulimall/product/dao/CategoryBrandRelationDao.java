package com.atguigu.gulimall.product.dao;

import com.atguigu.gulimall.product.entity.CategoryBrandRelationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 品牌分类关联
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 17:11:04
 */
@Mapper
public interface CategoryBrandRelationDao extends BaseMapper<CategoryBrandRelationEntity> {

    /**
     * 品牌改名时，级联更新品牌分类关联表中的冗余品牌名 brand_name
     * @param brandId 品牌 id
     * @param name    新品牌名
     */
    void updateBrandName(@Param("brandId") Long brandId, @Param("name") String name);

    /**
     * 分类改名时，级联更新品牌分类关联表中的冗余分类名 catelog_name
     * @param catelogId 分类 id
     * @param name      新分类名
     */
    void updateCatelogName(@Param("catelogId") Long catelogId, @Param("name") String name);
}
