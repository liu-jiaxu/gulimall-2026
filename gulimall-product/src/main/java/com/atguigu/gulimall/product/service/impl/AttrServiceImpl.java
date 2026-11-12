package com.atguigu.gulimall.product.service.impl;

import com.atguigu.gulimall.common.enums.product.AttrEnum;
import com.atguigu.gulimall.common.utils.PageUtils;
import com.atguigu.gulimall.common.utils.Query;
import com.atguigu.gulimall.product.dao.AttrAttrgroupRelationDao;
import com.atguigu.gulimall.product.dao.AttrDao;
import com.atguigu.gulimall.product.dao.AttrGroupDao;
import com.atguigu.gulimall.product.dao.CategoryDao;
import com.atguigu.gulimall.product.entity.AttrAttrgroupRelationEntity;
import com.atguigu.gulimall.product.entity.AttrEntity;
import com.atguigu.gulimall.product.entity.AttrGroupEntity;
import com.atguigu.gulimall.product.entity.CategoryEntity;
import com.atguigu.gulimall.product.method.AttrMethod;
import com.atguigu.gulimall.product.method.CategoryMethod;
import com.atguigu.gulimall.product.service.AttrService;
import com.atguigu.gulimall.product.vo.AttrGroupRelationVo;
import com.atguigu.gulimall.api.product.AttrRespVo;
import com.atguigu.gulimall.api.product.AttrVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Service("attrService")
public class AttrServiceImpl extends ServiceImpl<AttrDao, AttrEntity> implements AttrService {

    @Autowired
    private AttrAttrgroupRelationDao attrAttrgroupRelationDao;
    @Autowired
    private AttrGroupDao attrGroupDao;
    @Autowired
    private CategoryDao categoryDao;
    @Autowired
    private CategoryMethod categoryMethod;
    @Autowired
    private AttrMethod attrMethod;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<AttrEntity> page = this.page(
                new Query<AttrEntity>().getPage(params),
                new QueryWrapper<AttrEntity>()
        );

        return new PageUtils(page);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveAttr(AttrVo attrVo) {
        AttrEntity attrEntity = new AttrEntity();
        // 将attrVo中的属性拷贝到attrEntity中
        BeanUtils.copyProperties(attrVo, attrEntity);
        // 保存基本数据
        this.save(attrEntity);
        // 保存关联关系
        if (AttrEnum.ATTR_TYPE_BASE.getCode() == attrVo.getAttrType()) {
            AttrAttrgroupRelationEntity attrAttrgroupRelationEntity = new AttrAttrgroupRelationEntity();
            attrAttrgroupRelationEntity.setAttrId(attrEntity.getAttrId());
            attrAttrgroupRelationEntity.setAttrGroupId(attrVo.getAttrGroupId());
            attrAttrgroupRelationDao.insert(attrAttrgroupRelationEntity);
        }
    }

    /**
     * 获取规格参数列表
     */
    @Override
    public PageUtils queryBaseAttrPage(Map<String, Object> params, Long catelogId, String type) {
        QueryWrapper<AttrEntity> queryWrapper = new QueryWrapper<AttrEntity>()
                .eq("attr_type", "base".equalsIgnoreCase(type) ?
                        AttrEnum.ATTR_TYPE_BASE.getCode() : AttrEnum.ATTR_TYPE_SALE.getCode());
        if (catelogId != 0) {
            queryWrapper.eq("catelog_id", catelogId);
        }
        String key = (String) params.get("key");
        if (StringUtils.isNotBlank(key)) {
            queryWrapper.and((wrapper) -> {
                wrapper.eq("attr_id", key).or().like("attr_name", key);
            });
        }
        IPage<AttrEntity> page = this.page(
                new Query<AttrEntity>().getPage(params),
                queryWrapper
        );
        //封装分页数据
        PageUtils pageUtils = new PageUtils(page);

        //查出新增的属性
        List<AttrEntity> records = page.getRecords();
        List<AttrRespVo> attrRespVoList = records.stream().map(attrEntity -> {
            AttrRespVo attrRespVo = new AttrRespVo();
            BeanUtils.copyProperties(attrEntity, attrRespVo);
            CategoryEntity categoryEntity = categoryDao.selectOne(
                    new QueryWrapper<CategoryEntity>().eq("cat_id", attrEntity.getCatelogId()));
            if (categoryEntity != null) {
                attrRespVo.setCatelogName(categoryEntity.getName());
            }

            AttrAttrgroupRelationEntity attrAttrgroupRelationEntity = attrAttrgroupRelationDao.selectOne(
                    new QueryWrapper<AttrAttrgroupRelationEntity>().eq("attr_id", attrEntity.getAttrId()));
            if (attrAttrgroupRelationEntity != null) {
                AttrGroupEntity attrGroupEntity = attrGroupDao.selectById(attrAttrgroupRelationEntity.getAttrGroupId());
                attrRespVo.setGroupName(attrGroupEntity.getAttrGroupName());
            }
            return attrRespVo;
        }).toList();
        pageUtils.setList(attrRespVoList);
        return pageUtils;
    }

    @Override
    public AttrRespVo getAttrInfo(Long attrId) {
        AttrRespVo respVo = new AttrRespVo();
        AttrEntity attrEntity = this.getById(attrId);
        BeanUtils.copyProperties(attrEntity, respVo);

        if (AttrEnum.ATTR_TYPE_BASE.getCode() == attrEntity.getAttrType()) {
            //1、设置分组信息
            AttrAttrgroupRelationEntity attrgroupRelation = attrAttrgroupRelationDao.selectOne(
                    new QueryWrapper<AttrAttrgroupRelationEntity>().eq("attr_id", attrId));
            if (attrgroupRelation != null) {
                respVo.setAttrGroupId(attrgroupRelation.getAttrGroupId());
                AttrGroupEntity attrGroupEntity = attrGroupDao.selectById(attrgroupRelation.getAttrGroupId());
                if (attrGroupEntity != null) {
                    respVo.setGroupName(attrGroupEntity.getAttrGroupName());
                }
            }
        }

        //2、设置分类信息
        Long catelogId = attrEntity.getCatelogId();
        Long[] catelogPath = categoryMethod.findCatelogPath(catelogId);
        respVo.setCatelogPath(catelogPath);

        CategoryEntity categoryEntity = categoryDao.selectById(catelogId);
        if (categoryEntity != null) {
            respVo.setCatelogName(categoryEntity.getName());
        }

        return respVo;
    }

    @Transactional
    @Override
    public void updateAttr(AttrVo attr) {
        AttrEntity attrEntity = new AttrEntity();
        BeanUtils.copyProperties(attr, attrEntity);
        this.updateById(attrEntity);

        if (AttrEnum.ATTR_TYPE_BASE.getCode() == attrEntity.getAttrType()) {
            // 修改分组关联
            AttrAttrgroupRelationEntity relationEntity = new AttrAttrgroupRelationEntity();

            relationEntity.setAttrGroupId(attr.getAttrGroupId());
            relationEntity.setAttrId(attr.getAttrId());

            Long count = attrAttrgroupRelationDao.selectCount(
                    new QueryWrapper<AttrAttrgroupRelationEntity>().eq("attr_id", attr.getAttrId()));
            if (count > 0) {
                attrAttrgroupRelationDao.update(relationEntity,
                        new UpdateWrapper<AttrAttrgroupRelationEntity>().eq("attr_id", attr.getAttrId()));
            } else {
                attrAttrgroupRelationDao.insert(relationEntity);
            }
        }

    }

    /**
     * 获取指定属性分组下【已关联】的所有属性
     * 思路：先查关联表拿到该分组下所有 attr_id，再按 id 批量查属性
     *
     * @param attrgroupId 属性分组 id
     */
    @Override
    public List<AttrEntity> getRelationAttr(Long attrgroupId) {
        return attrMethod.getRelationAttr(attrgroupId);
    }

    /**
     * 删除属性与属性分组的关联关系
     * 思路：前端传的 Vo 转成实体后，按 attr_id + attr_group_id 批量精确删除
     *
     * @param vos 关联关系（attrId + attrGroupId），可批量
     */
    @Override
    public void deleteRelation(AttrGroupRelationVo[] vos) {
        if (vos == null || vos.length == 0) {
            return;
        }
        // 1、Vo 列表转成关联实体列表（字段同名直接拷贝）
        List<AttrGroupRelationVo> relationVos = Arrays.asList(vos);
        List<AttrAttrgroupRelationEntity> entities = relationVos.stream().map((relationVo) -> {
            AttrAttrgroupRelationEntity relationEntity = new AttrAttrgroupRelationEntity();
            BeanUtils.copyProperties(relationVo, relationEntity);
            return relationEntity;
        }).collect(Collectors.toList());

        // 2、根据 attrId + attrGroupId 批量删除关联关系（XML 中 deleteBatchRelation）
        attrAttrgroupRelationDao.deleteBatchRelation(entities);
    }

    /**
     * 获取指定属性分组下【未关联】的属性（分页）
     * 规则：
     * 1. 当前分组只能关联自己所属分类里的【基本属性】(attr_type=1)
     * 2. 当前分组只能关联【本分类下其它分组都未引用】的属性
     *
     * @param params      分页/模糊查询参数（key: 按属性id/属性名模糊）
     * @param attrgroupId 属性分组 id
     */
    @Override
    public PageUtils getNoRelationAttr(Map<String, Object> params, Long attrgroupId) {

        /*
            表设计的问题导致需要额外进行多次SQL查询
            可以直接删除分组属性关系表，在属性表中新增字段attr_group_id，null说明没有绑定分组
            关联功能分析
                1.新增
                    因为新增属性每次必须指定分组关联，因此属性必定有关联分组，此时新增功能只需额外设置attr_group_id即可
                2.删除
                    删除时设置attr_group_id=null即可
                3.关联查询
                    新增条件attr_group_id is not null过滤非关联的数据
                4.当前功能
                    无需进行多个SQL查询，只需要查询attr_group_id is null的记录即可
         */

        /*
            多表联查SQL
            SELECT *
            FROM pms_attr a
            WHERE a.attr_type = 1
              AND a.catelog_id IN (
                    SELECT g.catelog_id
                    FROM pms_attr_group g
                    WHERE g.attr_group_id = #{attrgroupId}
              )
              -- 这个属性没被任何分组关联
              AND NOT EXISTS (
                    SELECT 1
                    FROM pms_attr_attrgroup_relation r
                    WHERE r.attr_id = a.attr_id
              )
              AND ( a.attr_id = #{key} OR a.attr_name LIKE CONCAT('%', #{key}, '%')
         */
        IPage<AttrEntity> page = new Query<AttrEntity>().getPage(params);
        String key = (String) params.get("key");
        IPage<AttrEntity> result = this.baseMapper.selectNoRelationAttr(page, attrgroupId, key);
        return new PageUtils(result);

//        // 常规思路：单表查询实现
//        // 1、当前分组只能关联自己所属分类里面的所有属性
//        //    先查出当前分组所属的分类
//        AttrGroupEntity attrGroupEntity = attrGroupDao.selectById(attrgroupId);
//        Long catelogId = attrGroupEntity.getCatelogId();
//
//        // 2、当前分组只能关联别的分组没有引用的属性
//        // 2.1 查出当前分类下的所有分组
//        List<AttrGroupEntity> attrGroupEntities = attrGroupDao.selectList(
//                new QueryWrapper<AttrGroupEntity>().eq("catelog_id", catelogId));
//        List<Long> attrGroupIds = attrGroupEntities.stream()
//                .map(AttrGroupEntity::getAttrGroupId)
//                .collect(Collectors.toList());
//
//        // 2.2 查出这些分组（含当前分组自己）已关联的所有属性 id
//        List<AttrAttrgroupRelationEntity> relationEntities = attrAttrgroupRelationDao.selectList(
//                new QueryWrapper<AttrAttrgroupRelationEntity>()
//                        .in(!attrGroupIds.isEmpty(), "attr_group_id", attrGroupIds));
//        List<Long> attrIds = relationEntities.stream()
//                .map(AttrAttrgroupRelationEntity::getAttrId)
//                .collect(Collectors.toList());
//
//        // 3、查询同分类下的基本属性，并排除掉上面已被引用的属性
//        QueryWrapper<AttrEntity> wrapper = new QueryWrapper<AttrEntity>()
//                .eq("catelog_id", catelogId)
//                .eq("attr_type", AttrEnum.ATTR_TYPE_BASE.getCode());
//        if (!attrIds.isEmpty()) {
//            wrapper.notIn("attr_id", attrIds);
//        }
//
//        // 4、模糊查询（按属性 id 精确 or 属性名模糊）
//        String key = (String) params.get("key");
//        if (StringUtils.isNotEmpty(key)) {
//            wrapper.and((w) -> w.eq("attr_id", key).or().like("attr_name", key));
//        }
//
//        // 5、分页查询并返回
//        IPage<AttrEntity> page = this.page(new Query<AttrEntity>().getPage(params), wrapper);
//        return new PageUtils(page);
    }

}