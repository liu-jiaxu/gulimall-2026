package com.atguigu.gulimall.agent.exception;

/**
 * AI 模块业务异常。
 *
 * <p>带错误码，统一由 AgentExceptionHandler 转成 R.error(code, msg)，
 * 前端按 15001/15012 判断跳登录或刷新 JWT，其余直接展示 msg。
 */
public class AgentException extends RuntimeException {

    private final int code;

    /**
     * 用错误码和提示信息构造业务异常。
     *
     * @param code    业务错误码，例如 15001
     * @param message 返回给前端的提示信息
     */
    public AgentException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 用错误码、提示信息和原始异常构造业务异常。
     *
     * @param code    业务错误码，例如 15010
     * @param message 返回给前端的提示信息
     * @param cause   导致本次异常的原始异常，便于保留堆栈
     */
    public AgentException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * 获取业务错误码。
     *
     * @return 构造时传入的业务错误码
     */
    public int getCode() {
        return code;
    }
}
