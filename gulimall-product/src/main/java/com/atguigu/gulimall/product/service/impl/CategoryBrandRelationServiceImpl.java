package com.atguigu.gulimall.product.service.impl;

import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.Query;
import com.atguigu.gulimall.product.dao.BrandDao;
import com.atguigu.gulimall.product.dao.CategoryBrandRelationDao;
import com.atguigu.gulimall.product.dao.CategoryDao;
import com.atguigu.gulimall.product.entity.BrandEntity;
import com.atguigu.gulimall.product.entity.CategoryBrandRelationEntity;
import com.atguigu.gulimall.product.entity.CategoryEntity;
import com.atguigu.gulimall.product.service.CategoryBrandRelationService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toList;


@Service("categoryBrandRelationService")
@Slf4j
public class CategoryBrandRelationServiceImpl extends ServiceImpl<CategoryBrandRelationDao, CategoryBrandRelationEntity> implements CategoryBrandRelationService {

    @Autowired
    private CategoryDao categoryDao;
    @Autowired
    private BrandDao brandDao;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<CategoryBrandRelationEntity> page = this.page(
                new Query<CategoryBrandRelationEntity>().getPage(params),
                new QueryWrapper<CategoryBrandRelationEntity>()
        );

        return new PageUtils(page);
    }

    /**
     * 检查是否存在相同的品牌和分类记录
     *
     * @param brandId   品牌ID
     * @param catelogId 分类ID
     * @return 相同记录的数量
     */
    @Override
    public long checkSameRecord(Long brandId, Long catelogId) {
        return this.count(
                new QueryWrapper<CategoryBrandRelationEntity>()
                        .eq("brand_id", brandId)
                        .eq("catelog_id", catelogId)
        );
    }

    /**
     * 保存品牌和分类关联关系的详细信息
     *
     * @param categoryBrandRelation 品牌分类关联实体对象
     */
    @Override
    public void saveDetails(CategoryBrandRelationEntity categoryBrandRelation) {
        CategoryEntity categoryEntity = categoryDao.selectById(categoryBrandRelation.getCatelogId());
        log.info("categoryEntity: {}", categoryEntity);
        if (categoryEntity == null) {
            throw new IllegalArgumentException("保存品牌分类关联失败：分类不存在，catelogId="
                    + categoryBrandRelation.getCatelogId());
        }
        categoryBrandRelation.setCatelogName(categoryEntity.getName());

        BrandEntity brandEntity = brandDao.selectById(categoryBrandRelation.getBrandId());
        log.info("brandEntity: {}", brandEntity);
        if (brandEntity == null) {
            throw new IllegalArgumentException("保存品牌分类关联失败：品牌不存在，brandId="
                    + categoryBrandRelation.getBrandId());
        }
        categoryBrandRelation.setBrandName(brandEntity.getName());

        // 关键修复：原实现只补全了名称字段，没有真正写库，
        // 导致接口返回成功但 pms_category_brand_relation 表里查不到新记录
        this.save(categoryBrandRelation);
    }

    // 获取分类关联的品牌
    @Override
    public List<BrandEntity> getBrandsByCatId(Long catId) {
        List<CategoryBrandRelationEntity> categoryBrandRelationEntities =
                this.list(new QueryWrapper<CategoryBrandRelationEntity>().eq("catelog_id", catId));
        return categoryBrandRelationEntities.stream()
                .map(entity -> brandDao.selectById(entity.getBrandId())).toList();
    }

}