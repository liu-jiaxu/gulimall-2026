package com.atguigu.gulimall.agent.settings.dto;

import lombok.Data;

/**
 * 更新 AI 设置时前端提交的字段，全部可选（null 表示不改）。
 */
@Data
public class SettingsUpdateRequest {

    /**
     * 聊天窗主题色，形如 #0BB2D4；null 表示不改
     */
    private String themeColor;

    /**
     * 是否默认开启流式输出；null 表示不改
     */
    private Boolean streamEnabled;

    /**
     * 默认模型 id；null 表示不改
     */
    private String defaultModel;

    /**
     * 用户自定义提示词，只影响语气，不能覆盖安全规则
     */
    private String customPrompt;

    /**
     * 宠物上次停靠的横坐标；null 表示不改
     */
    private Integer petPositionX;

    /**
     * 宠物上次停靠的纵坐标；null 表示不改
     */
    private Integer petPositionY;
}
