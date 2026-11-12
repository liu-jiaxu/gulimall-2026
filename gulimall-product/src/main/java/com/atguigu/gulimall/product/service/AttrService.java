package com.atguigu.gulimall.product.service;

import com.atguigu.gulimall.product.entity.AttrEntity;
import com.atguigu.gulimall.product.vo.AttrGroupRelationVo;
import com.atguigu.gulimall.api.product.AttrRespVo;
import com.atguigu.gulimall.api.product.AttrVo;
import com.baomidou.mybatisplus.extension.service.IService;
import com.atguigu.gulimall.common.utils.PageUtils;

import java.util.List;
import java.util.Map;

/**
 * 商品属性
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 17:11:04
 */
public interface AttrService extends IService<AttrEntity> {

    PageUtils queryPage(Map<String, Object> params);

    void saveAttr(AttrVo attrVo);

    PageUtils queryBaseAttrPage(Map<String, Object> params, Long catelogId, String type);

    AttrRespVo getAttrInfo(Long attrId);

    void updateAttr(AttrVo attr);

    /**
     * 获取指定属性分组下【已关联】的所有属性
     * @param attrgroupId 属性分组 id
     */
    List<AttrEntity> getRelationAttr(Long attrgroupId);

    /**
     * 删除属性与属性分组的关联关系
     * @param vos 关联关系（attrId + attrGroupId），可批量
     */
    void deleteRelation(AttrGroupRelationVo[] vos);

    /**
     * 获取指定属性分组下【未关联】的属性（分页）
     * <p>规则：当前分组只能关联自己所属分类里的基本属性，且这些属性不能被同分类下其它分组已引用</p>
     * @param params     分页/模糊查询参数
     * @param attrgroupId 属性分组 id
     */
    PageUtils getNoRelationAttr(Map<String, Object> params, Long attrgroupId);
}

