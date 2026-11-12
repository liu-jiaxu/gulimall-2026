package com.atguigu.gulimall.k8s.config;

/**
 * ai_default_config 表的一行数据。
 *
 * @param defaultModel 默认模型 id
 * @param systemPrompt 系统提示词
 */
public record AiConfig(String defaultModel, String systemPrompt) {
}
