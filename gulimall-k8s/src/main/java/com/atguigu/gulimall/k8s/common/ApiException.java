package com.atguigu.gulimall.k8s.common;

/**
 * 业务异常：带 HTTP 状态码和给前端看的提示。
 */
public class ApiException extends RuntimeException {

    /**
     * HTTP 状态码
     */
    private final int status;

    /**
     * 创建业务异常。
     *
     * @param status  HTTP 状态码
     * @param message 给前端看的提示
     */
    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * 创建带原始异常的的业务异常。
     *
     * @param status  HTTP 状态码
     * @param message 给前端看的提示
     * @param cause   原始异常
     */
    public ApiException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /**
     * 获取 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public int getStatus() {
        return status;
    }
}
