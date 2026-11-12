package com.atguigu.gulimall.product.dao;

import com.atguigu.gulimall.product.entity.AttrEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品属性
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 17:11:04
 */
@Mapper
public interface AttrDao extends BaseMapper<AttrEntity> {

    /**
     * 分页查询指定属性分组【未关联】的属性
     * <p>条件：基本属性 + 属于该分组所属分类 + 关系表中没有任何关联记录</p>
     * @param page        MyBatis-Plus 分页对象（由分页插件自动拼接 LIMIT，并生成 COUNT）
     * @param attrGroupId 属性分组 id
     * @param key         模糊查询关键字（按属性 id / 属性名），可为 null
     * @return 未关联的属性分页结果
     */
    IPage<AttrEntity> selectNoRelationAttr(IPage<AttrEntity> page,
                                           @Param("attrGroupId") Long attrGroupId,
                                           @Param("key") String key);

    String getAttrNameById(@Param("attrId") Long attrId);

    /**
     * 根据attrId查询当前attr是否可被检索
     * @param attrIds
     * @return
     */
    List<Long> selectSearchAttrs(@Param("attrIds") List<Long> attrIds);
}
