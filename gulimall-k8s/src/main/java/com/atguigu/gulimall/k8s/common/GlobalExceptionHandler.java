package com.atguigu.gulimall.k8s.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常响应：前端只需要读 code 和 message。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常。
     *
     * @param ex 业务异常
     * @return 带业务状态码的 JSON
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        // 1. 业务异常直接按约定的状态码返回，不打印堆栈
        return ResponseEntity.status(ex.getStatus()).body(body(ex.getStatus(), ex.getMessage()));
    }

    /**
     * 处理请求体 JSON 解析失败。
     *
     * @param ex 解析异常
     * @return 400 响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleBadJson(HttpMessageNotReadableException ex) {
        // 1. 请求体格式错误属于客户端问题，返回 400
        return ResponseEntity.badRequest().body(body(400, "请求体格式不正确"));
    }

    /**
     * 处理未预期的异常。
     *
     * @param ex 原始异常
     * @return 500 响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        // 1. 未预期异常打印堆栈，方便排查
        log.error("请求处理失败", ex);

        // 2. 只把最外层信息返回给前端，避免泄露内部细节
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return ResponseEntity.internalServerError().body(body(500, "服务器内部错误：" + message));
    }

    /**
     * 构造统一的响应体。
     *
     * @param code    业务状态码
     * @param message 提示信息
     * @return 响应体
     */
    private Map<String, Object> body(int code, String message) {
        // 1. 用 LinkedHashMap 保证 code 在 message 前面，方便阅读
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return body;
    }
}
