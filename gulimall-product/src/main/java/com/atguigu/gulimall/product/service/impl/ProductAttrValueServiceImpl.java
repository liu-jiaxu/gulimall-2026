package com.atguigu.gulimall.product.service.impl;

import com.atguigu.gulimall.product.method.ProductAttrValueMethod;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.Query;

import com.atguigu.gulimall.product.dao.ProductAttrValueDao;
import com.atguigu.gulimall.product.entity.ProductAttrValueEntity;
import com.atguigu.gulimall.product.service.ProductAttrValueService;


@Service("productAttrValueService")
public class ProductAttrValueServiceImpl extends ServiceImpl<ProductAttrValueDao, ProductAttrValueEntity> implements ProductAttrValueService {

    @Autowired
    private ProductAttrValueMethod productAttrValueMethod;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<ProductAttrValueEntity> page = this.page(
                new Query<ProductAttrValueEntity>().getPage(params),
                new QueryWrapper<ProductAttrValueEntity>()
        );

        return new PageUtils(page);
    }

    @Override
    public List<ProductAttrValueEntity> baseAttrListForSpu(Long spuId) {
        return productAttrValueMethod.baseAttrListForSpu(spuId);
    }

    /**
     * 修改的时候有新增、有修改、有删除，分不清谁是谁，
     * 所以先把该 spuId 下的属性全删了再按前端传来的整体重新插入。
     */
    @Transactional
    @Override
    public void updateSpuAttr(Long spuId, List<ProductAttrValueEntity> entities) {
        // 1、删除这个 spuId 对应的所有属性
        this.baseMapper.delete(new QueryWrapper<ProductAttrValueEntity>().eq("spu_id", spuId));

        if (CollectionUtils.isEmpty(entities)) {
            return;
        }

        // 2、新增回去。id 置空，交给数据库自增，避免前端把旧 id 回传造成主键冲突
        for (ProductAttrValueEntity entity : entities) {
            entity.setId(null);
            entity.setSpuId(spuId);
        }
        this.saveBatch(entities);
    }

}