package com.atguigu.gulimall.k8s.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 会话与 Redis 历史配置，对应 application.yaml 的 gulimall.k8s.conversation。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "gulimall.k8s.conversation")
public class ConversationProperties {

    /**
     * 会话、消息、模型记忆的过期时间；每次写入滑动续期
     */
    @NotNull
    private Duration ttl;

    /**
     * 模型记忆窗口保留多少条消息
     */
    @NotNull
    @Positive
    private Integer memoryMaxMessages;

    /**
     * 页面一次加载多少条历史消息
     */
    @NotNull
    @Positive
    private Integer uiPageSize;

    /**
     * 单条用户消息最大字符数
     */
    @NotNull
    @Positive
    private Integer maxMessageChars;

    /**
     * 每个用户最多保留多少个会话
     */
    @NotNull
    @Positive
    private Integer maxConversationsPerUser;
}
