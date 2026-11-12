package com.atguigu.gulimall.product.method;

import com.atguigu.gulimall.product.dao.AttrAttrgroupRelationDao;
import com.atguigu.gulimall.product.dao.AttrDao;
import com.atguigu.gulimall.product.entity.AttrAttrgroupRelationEntity;
import com.atguigu.gulimall.product.entity.AttrEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @ClassName: AttrMethod
 * @Package: com.atguigu.gulimall.product.method
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/11 - 10:21
 * @Version: v1.0
 */
@Component
public class AttrMethod {

    @Autowired
    private AttrAttrgroupRelationDao attrAttrgroupRelationDao;
    @Autowired
    private AttrDao attrDao;

    /**
     * 获取指定属性分组下【已关联】的所有属性
     * 思路：先查关联表拿到该分组下所有 attr_id，再按 id 批量查属性
     *
     * @param attrgroupId 属性分组 id
     */
    public List<AttrEntity> getRelationAttr(Long attrgroupId) {
        // 1、查询该分组下的所有关联记录
        List<AttrAttrgroupRelationEntity> relationEntities = attrAttrgroupRelationDao.selectList(
                new QueryWrapper<AttrAttrgroupRelationEntity>().eq("attr_group_id", attrgroupId));

        // 2、取出关联的属性 id 集合
        List<Long> attrIds = relationEntities.stream()
                .map(AttrAttrgroupRelationEntity::getAttrId)
                .collect(Collectors.toList());

        // 3、按 id 批量查询属性详情（空集合会查空，直接返回）
        if (attrIds.isEmpty()) {
            return Collections.emptyList();
        }
        return attrDao.selectByIds(attrIds);
    }

}
