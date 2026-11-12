package com.atguigu.gulimall.product.entity;

import com.atguigu.gulimall.common.valid.AddGroup;
import com.atguigu.gulimall.common.valid.showStatusValid.ListValue;
import com.atguigu.gulimall.common.valid.UpdateGroup;
import com.atguigu.gulimall.common.valid.UpdateGroupStatus;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.*;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

import java.io.Serial;
import java.io.Serializable;

/**
 * 品牌
 *
 * @author liujiaxu
 * @email 15866718620@163.com
 * @date 2026-08-24 17:11:04
 */
@Data
@TableName("pms_brand")
public class BrandEntity implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /*
      补充：
      实际开发中，不会将一个实体类同时作为entity和vo使用，
      通常会将entity作为数据库映射的实体类，而vo则用于前端传输数据的对象。
     */

    // Jakarta Validation参数校验
    // document/Jakarta Validation参数校验.md

    /**
     * 品牌id
     */
    @TableId
    private Long brandId;
    /**
     * 品牌名
     */
    @NotBlank(message = "品牌名必须提交", groups = {AddGroup.class, UpdateGroup.class})
    private String name;
    /**
     * 品牌logo地址
     */
    // 注意：logo 现在存的是【根相对路径】/oss/gulimall/brand/xxx.png，不是完整 URL。
    // @URL 用的是 java.net.URL 校验，相对路径没有协议头会直接判定不合法（MalformedURLException），
    // 所以这个校验必须去掉，否则新增/修改品牌永远保存不了。
    // @URL(message = "logo必须是一个合法的url地址", groups = {AddGroup.class, UpdateGroup.class})
    @NotEmpty(groups = {AddGroup.class})
    private String logo;
    /**
     * 介绍
     */
    private String descript;
    /**
     * 显示状态[0-不显示；1-显示]
     */
    @NotNull(groups = {AddGroup.class, UpdateGroup.class, UpdateGroupStatus.class})
    @ListValue(values = {0, 1}, groups = {AddGroup.class, UpdateGroup.class, UpdateGroupStatus.class})
    private Integer showStatus;
    /**
     * 检索首字母
     */
    @NotEmpty(groups = {AddGroup.class})
    @Pattern(regexp = "^[a-zA-Z]$", message = "检索首字母必须是一个字母", groups = {AddGroup.class, UpdateGroup.class})
    private String firstLetter;
    /**
     * 排序
     */
    @NotNull(groups = {AddGroup.class})
    @Min(value = 0, message = "排序必须大于等于0", groups = {AddGroup.class, UpdateGroup.class})
    private Integer sort;

}
