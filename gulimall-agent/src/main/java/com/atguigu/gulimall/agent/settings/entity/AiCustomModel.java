package com.atguigu.gulimall.agent.settings.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户自己录入的 OpenAI 兼容模型。
 *
 * <p>api_key_cipher 是 AES-GCM 密文，前端永远拿不到明文；
 * 页面只返回 api_key_mask（例如 sk-****abcd）。
 */
@Data
@TableName("ai_custom_model")
public class AiCustomModel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String principalType;

    private Long principalId;

    /**
     * 对外暴露的模型 id，格式 custom:{id}
     */
    private String modelId;

    private String displayName;

    private String provider;

    private String modelName;

    private String baseUrl;

    private String apiKeyCipher;

    private String apiKeyMask;

    /**
     * 1 = 使用公共 closeai key，此时 apiKeyCipher 可为空
     */
    private Boolean useSharedKey;

    private Boolean supportsVision;

    private Boolean supportsStreaming;

    private BigDecimal temperature;

    private Integer timeoutSeconds;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
