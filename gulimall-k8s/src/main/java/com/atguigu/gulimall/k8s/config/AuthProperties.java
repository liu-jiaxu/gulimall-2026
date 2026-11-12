package com.atguigu.gulimall.k8s.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 初始化账号与登录邀请码配置，对应 application.yaml 的 gulimall.k8s.auth。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "gulimall.k8s.auth")
public class AuthProperties {

    /**
     * 首次启动时自动创建的用户名
     */
    private String seedUsername = "admin";

    /**
     * 首次启动时自动创建的用户密码
     */
    private String seedPassword = "<your-password>";

    /**
     * 登录和注册邀请码；每次登录/注册都必须输入。
     *
     * <p>k8s 部署时用环境变量 INVITE_CODE 覆盖。
     */
    @NotBlank
    private String inviteCode = "<your-invite-code>";
}
