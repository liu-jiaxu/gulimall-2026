package com.atguigu.gulimall.agent.chat.dto;

import lombok.Data;

/**
 * 页面模型下拉框的一项。
 */
@Data
public class ModelView {

    /**
     * 页面提交的模型 id，例如 deepseek-v4.1-flash 或 custom:12
     */
    private String id;

    /**
     * 下拉框展示名
     */
    private String displayName;

    /**
     * 真正发给模型服务的 model 字段
     */
    private String modelName;

    /**
     * 是否支持图片输入
     */
    private boolean vision;

    /**
     * 是否支持流式输出
     */
    private boolean streaming;

    /**
     * 是否支持 function calling
     */
    private boolean tools;

    /**
     * 是否是用户自己添加的模型，页面会加“自定义”标记
     */
    private boolean custom;
}
