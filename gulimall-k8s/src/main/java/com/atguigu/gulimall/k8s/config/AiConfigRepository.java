package com.atguigu.gulimall.k8s.config;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * ai_default_config 表的读写。
 *
 * <p>表里只放一行默认配置：默认模型和系统提示词。
 * 聊天时每次都读一次，改了数据库不需要重启服务。
 */
@Repository
@RequiredArgsConstructor
public class AiConfigRepository {

    /**
     * 数据库里没有配置时使用的系统提示词
     */
    private static final String FALLBACK_PROMPT = "你是一个乐于助人的AI助手。请直接、准确地回答用户问题。";

    private final JdbcTemplate jdbcTemplate;
    private final AiProperties aiProperties;

    /**
     * 读取默认 AI 配置；表里没有数据时自动插入一行。
     *
     * @return 默认 AI 配置
     */
    public AiConfig load() {
        // 1. 取第一行；这张表设计上只有一行
        List<AiConfig> rows = jdbcTemplate.query(
                "SELECT default_model, system_prompt FROM ai_default_config ORDER BY id LIMIT 1",
                (rs, rowNum) -> new AiConfig(
                        rs.getString("default_model"),
                        rs.getString("system_prompt")
                )
        );
        if (!rows.isEmpty()) {
            return rows.get(0);
        }

        // 2. 没有就按 application.yaml 的默认模型插入一行，保证服务第一次启动也能用
        String defaultModel = aiProperties.getDefaultModel();
        jdbcTemplate.update(
                "INSERT INTO ai_default_config (default_model, system_prompt) VALUES (?, ?)",
                defaultModel,
                FALLBACK_PROMPT
        );
        return new AiConfig(defaultModel, FALLBACK_PROMPT);
    }
}
