package com.atguigu.gulimall.product.service;

import com.atguigu.gulimall.product.vo.Catelog2Vo;
import com.baomidou.mybatisplus.extension.service.IService;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.product.entity.CategoryEntity;

import java.util.List;
import java.util.Map;

/**
 * 商品三级分类
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 17:11:04
 */
public interface CategoryService extends IService<CategoryEntity> {

    PageUtils queryPage(Map<String, Object> params);

    List<CategoryEntity> listWithTree();

    void removeMenusByIds(List<Long> longs);

    /**
     * 找到catelogId的完整路径 [父/子/孙]
     * @param catelogId
     * @return
     */
    Long[] findCatelogPath(Long catelogId);

    /**
     * 修改功能<br>
     * 因为存在品牌分类关联表，所以修改品牌信息需要级联更新<br>
     * @param category 分类实体
     */
    void updateDetails(CategoryEntity category);

    List<CategoryEntity> getLevel1CategoryList();

    Map<String, List<Catelog2Vo>> getCatelogJson();
}

