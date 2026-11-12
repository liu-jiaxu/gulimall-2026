package com.atguigu.gulimall.agent.exception;

/**
 * gulimall-agent 错误码（模块段 15，对应端口 15000）。
 */
public final class AgentErrorCode {

    /**
     * 私有构造器，禁止实例化，只用于集中存放错误码常量。
     */
    private AgentErrorCode() {
    }

    /**
     * 未登录，缺少 agent JWT
     */
    public static final int NOT_LOGIN = 15001;
    /**
     * AI key 未配置或模型不可用
     */
    public static final int AI_NOT_CONFIGURED = 15003;
    /**
     * 模型不存在
     */
    public static final int MODEL_NOT_FOUND = 15004;
    /**
     * 消息过长/为空
     */
    public static final int MESSAGE_INVALID = 15005;
    /**
     * 会话忙/超过并发限制
     */
    public static final int CONVERSATION_BUSY = 15006;
    /**
     * 自定义模型数量/配置不合法
     */
    public static final int CUSTOM_MODEL_INVALID = 15007;
    /**
     * 图片不合法
     */
    public static final int IMAGE_INVALID = 15008;
    /**
     * RAG 不可用
     */
    public static final int RAG_UNAVAILABLE = 15009;
    /**
     * 模型调用失败
     */
    public static final int MODEL_CALL_FAILED = 15010;
    /**
     * agent JWT 无效/过期/角色不匹配
     */
    public static final int AGENT_TOKEN_INVALID = 15012;
}
