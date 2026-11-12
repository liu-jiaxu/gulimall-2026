package com.atguigu.gulimall.product.service;

import com.atguigu.gulimall.product.entity.AttrAttrgroupRelationEntity;
import com.atguigu.gulimall.product.vo.AttrGroupRelationVo;
import com.baomidou.mybatisplus.extension.service.IService;
import com.atguigu.gulimall.common.utils.PageUtils;

import java.util.List;
import java.util.Map;

/**
 * 属性&属性分组关联
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 17:11:04
 */
public interface AttrAttrgroupRelationService extends IService<AttrAttrgroupRelationEntity> {

    PageUtils queryPage(Map<String, Object> params);

    /**
     * 批量保存 属性与属性分组的关联关系（Vo 转实体后批量插入）
     * @param vos 关联关系列表（attrId + attrGroupId）
     */
    void saveBatch(List<AttrGroupRelationVo> vos);
}

