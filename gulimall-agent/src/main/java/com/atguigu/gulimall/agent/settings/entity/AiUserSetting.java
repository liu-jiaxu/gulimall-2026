package com.atguigu.gulimall.agent.settings.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户/管理员的 AI 偏好设置（窗口颜色、默认模型、自定义提示词等）。
 *
 * <p>一个身份只有一行：principal_type + principal_id 唯一。
 */
@Data
@TableName("ai_user_setting")
public class AiUserSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * USER / ADMIN
     */
    private String principalType;

    private Long principalId;

    /**
     * 聊天窗主色，默认谷粒红
     */
    private String themeColor;

    /**
     * 是否默认开启流式
     */
    private Boolean streamEnabled;

    private String defaultModel;

    /**
     * 用户自定义提示词，追加在安全规则之后
     */
    private String customPrompt;

    private Integer petPositionX;

    private Integer petPositionY;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
