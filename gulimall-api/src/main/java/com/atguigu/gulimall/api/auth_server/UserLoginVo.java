package com.atguigu.gulimall.api.auth_server;

import lombok.Data;

@Data
public class UserLoginVo {

    private String loginacct;

    private String password;
}
