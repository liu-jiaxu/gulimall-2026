package com.atguigu.gulimall.api.member;

import lombok.Data;

/**
 * @ClassName: MemberReceiveAddressVo
 * @Package: com.atguigu.gulimall.api.member
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/16 - 17:26
 * @Version: v1.0
 */
@Data
public class MemberReceiveAddressVo {

    private Long id;
    /**
     * member_id
     */
    private Long memberId;
    /**
     * 收货人姓名
     */
    private String name;
    /**
     * 电话
     */
    private String phone;
    /**
     * 邮政编码
     */
    private String postCode;
    /**
     * 省份/直辖市
     */
    private String province;
    /**
     * 城市
     */
    private String city;
    /**
     * 区
     */
    private String region;
    /**
     * 详细地址(街道)
     */
    private String detailAddress;
    /**
     * 省市区代码
     */
    private String areacode;
    /**
     * 是否默认
     */
    private Integer defaultStatus;

}
