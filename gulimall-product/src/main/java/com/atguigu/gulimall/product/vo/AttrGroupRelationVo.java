package com.atguigu.gulimall.product.vo;

import lombok.Data;

/**
 * 属性与属性分组 关联关系 Vo（接收前端新增/删除关联时传的 属性id + 分组id）
 *
 * @author liujiaxu
 */
@Data
public class AttrGroupRelationVo {

    /** 属性 id */
    private Long attrId;

    /** 属性分组 id */
    private Long attrGroupId;
}
