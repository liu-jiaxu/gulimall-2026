-- ============================================================
-- gulimall-k8s 独立 AI 服务数据库
-- ------------------------------------------------------------
-- 执行方式：
--   mysql -uroot -p < gulimall-k8s/sql/init.sql
--
-- 说明：
-- 1. 聊天记录不落 MySQL，全部存 Redis，滑动过期 3 天；
-- 2. MySQL 只保存用户信息和 AI 默认配置；
-- 3. 默认管理员由应用首次启动时创建（admin/<your-password>），
--    也可以在这里手动插入，密码必须是 BCrypt 哈希。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `gulimall_k8s_ai`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE `gulimall_k8s_ai`;

-- ------------------------------------------------------------
-- 1. 用户表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_user` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`      VARCHAR(64)     NOT NULL COMMENT '登录名',
    `password_hash` VARCHAR(100)    NOT NULL COMMENT 'BCrypt 密码哈希',
    `display_name`  VARCHAR(64)     NOT NULL COMMENT '页面展示名',
    `enabled`       TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '1=启用，0=禁用',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 用户';

-- ------------------------------------------------------------
-- 2. AI 默认配置表
--    设计上只保留一行；聊天时每次读取，改库不用重启服务。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_default_config` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `default_model` VARCHAR(64)     NOT NULL COMMENT '默认模型 id',
    `system_prompt` VARCHAR(2000)   NOT NULL COMMENT '系统提示词',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI 默认配置';

-- ------------------------------------------------------------
-- 3. 默认配置
--    default_model 必须是 application.yaml 里 models 的一个 id；
--    如果数据库配了不存在的模型，应用会回退到 YAML 的 default-model。
-- ------------------------------------------------------------
INSERT INTO `ai_default_config` (`id`, `default_model`, `system_prompt`)
SELECT 1,
       'deepseek-v4.1-flash',
       '你是一个乐于助人的AI助手。请直接、准确地回答用户问题。'
WHERE NOT EXISTS (
    SELECT 1 FROM `ai_default_config` WHERE `id` = 1
);
