-- ============================================================
-- gulimall-agent AI 配置库
-- 执行方式：mysql -uroot -p < sql/gulimall_ai.sql
-- 聊天记录不落 MySQL，按需求存 Redis 3 天；这里只存设置和自定义模型。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `gulimall_ai`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE `gulimall_ai`;

-- 用户/管理员的 AI 偏好：窗口颜色、默认模型、流式开关、自定义提示词、宠物位置
CREATE TABLE IF NOT EXISTS `ai_user_setting` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `principal_type` VARCHAR(16) NOT NULL COMMENT 'USER=商城会员，ADMIN=管理端用户',
  `principal_id` BIGINT NOT NULL COMMENT '会员 id 或管理员 id',
  `theme_color` VARCHAR(16) NOT NULL DEFAULT '#e4393c' COMMENT '聊天窗主色',
  `stream_enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '默认是否流式输出',
  `default_model` VARCHAR(64) NOT NULL DEFAULT 'deepseek-v4.1-flash' COMMENT '默认模型 id',
  `custom_prompt` VARCHAR(1000) DEFAULT NULL COMMENT '用户自定义提示词，只能补充语气',
  `pet_position_x` INT DEFAULT NULL COMMENT '宠物上次的横坐标',
  `pet_position_y` INT DEFAULT NULL COMMENT '宠物上次的纵坐标',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_principal` (`principal_type`, `principal_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 用户设置';

-- 用户自己录入的 OpenAI 兼容模型；apiKey 存 AES-GCM 密文
CREATE TABLE IF NOT EXISTS `ai_custom_model` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `principal_type` VARCHAR(16) NOT NULL COMMENT 'USER / ADMIN',
  `principal_id` BIGINT NOT NULL COMMENT '会员 id 或管理员 id',
  `model_id` VARCHAR(80) NOT NULL COMMENT '对外模型 id，格式 custom:xxxx',
  `display_name` VARCHAR(64) NOT NULL COMMENT '页面展示名',
  `provider` VARCHAR(32) NOT NULL DEFAULT 'openai-compatible' COMMENT '协议类型',
  `model_name` VARCHAR(128) NOT NULL COMMENT '请求体里的 model',
  `base_url` VARCHAR(512) NOT NULL COMMENT 'OpenAI 兼容地址，必须 https',
  `api_key_cipher` VARCHAR(2048) DEFAULT NULL COMMENT 'AES-GCM 密文；使用公共 key 时为空',
  `api_key_mask` VARCHAR(32) NOT NULL COMMENT '页面展示的掩码',
  `use_shared_key` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=使用公共 closeai key',
  `supports_vision` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否支持图片输入',
  `supports_streaming` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否支持流式',
  `temperature` DECIMAL(3,2) NOT NULL DEFAULT 0.70 COMMENT '温度',
  `timeout_seconds` INT NOT NULL DEFAULT 10 COMMENT '普通对话超时秒数',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_owner_model` (`principal_type`, `principal_id`, `model_id`),
  KEY `idx_owner` (`principal_type`, `principal_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 自定义模型';
