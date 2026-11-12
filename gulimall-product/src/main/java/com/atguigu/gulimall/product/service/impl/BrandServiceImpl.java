package com.atguigu.gulimall.product.service.impl;

import com.atguigu.gulimall.product.dao.CategoryBrandRelationDao;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Map;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.Query;

import com.atguigu.gulimall.product.dao.BrandDao;
import com.atguigu.gulimall.product.entity.BrandEntity;
import com.atguigu.gulimall.product.service.BrandService;
import org.springframework.transaction.annotation.Transactional;


@Service("brandService")
public class BrandServiceImpl extends ServiceImpl<BrandDao, BrandEntity> implements BrandService {

    @Autowired
    private CategoryBrandRelationDao categoryBrandRelationDao;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {

        // 模糊查询功能
        String key = (String) params.get("key");
        QueryWrapper<BrandEntity> queryWrapper = new QueryWrapper<>();
        if (StringUtils.isNotEmpty(key)) {
            queryWrapper.like("brand_id", key).or().like("name", key).or().like("first_letter", key);
        }

        IPage<BrandEntity> page = this.page(
                new Query<BrandEntity>().getPage(params),
                queryWrapper
        );

        return new PageUtils(page);
    }

    /**
     * 修改功能<br>
     * 因为存在品牌分类关联表，所以修改品牌信息需要级联更新<br>
     * @param brand 品牌实体
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDetails(BrandEntity brand) {
        // 1.更新品牌基本信息
        this.updateById(brand);

        // 2.若品牌名发生变更，级联更新品牌分类关联表中的冗余 brand_name
        if (StringUtils.isNotBlank(brand.getName())) {
            categoryBrandRelationDao.updateBrandName(brand.getBrandId(), brand.getName());
        }
    }

}