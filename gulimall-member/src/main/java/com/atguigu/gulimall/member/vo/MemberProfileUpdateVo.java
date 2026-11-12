package com.atguigu.gulimall.member.vo;

import lombok.Data;

/**
 * 个人中心允许会员修改的资料字段
 */
@Data
public class MemberProfileUpdateVo {

    private String nickname;

    private String mobile;

    private String email;

    private String header;

    private Integer gender;

    private String city;

    private String job;

    private String sign;
}
