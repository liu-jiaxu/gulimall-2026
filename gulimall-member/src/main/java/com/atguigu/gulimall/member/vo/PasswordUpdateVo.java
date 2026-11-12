package com.atguigu.gulimall.member.vo;

import lombok.Data;

/**
 * 个人中心修改密码
 */
@Data
public class PasswordUpdateVo {

    private String oldPassword;

    private String newPassword;
}
