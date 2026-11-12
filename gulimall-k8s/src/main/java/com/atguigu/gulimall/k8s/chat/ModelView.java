package com.atguigu.gulimall.k8s.chat;

/**
 * 页面模型下拉框的一项。
 *
 * @param id          模型 id
 * @param displayName 展示名
 * @param modelName   请求体里的模型名
 */
public record ModelView(String id, String displayName, String modelName) {
}
