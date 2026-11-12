package com.atguigu.gulimall.agent.exception;

import com.atguigu.gulimall.common.utils.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理：所有 JSON 接口都返回项目统一的 R 结构。
 */
@Slf4j
@RestControllerAdvice
public class AgentExceptionHandler {

    /**
     * 把业务异常转换成统一 R 结构，HTTP 状态保持 200。
     *
     * @param ex 捕获到的 AI 业务异常
     * @return 携带业务错误码和提示信息的统一响应
     */
    @ExceptionHandler(AgentException.class)
    public R handleAgentException(AgentException ex) {
        // 1. 业务异常按自己的错误码返回，HTTP 仍然 200，前端只看 code
        log.warn("AI 业务异常 code={} msg={}", ex.getCode(), ex.getMessage());
        return R.error(ex.getCode(), ex.getMessage());
    }

    /**
     * 兜底处理未预期异常，统一返回 99000 且不向前端暴露细节。
     *
     * @param ex 捕获到的未预期异常
     * @return 携带 99000 错误码和通用提示的统一响应
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public R handleException(Exception ex) {
        // 1. 未预期异常统一 99000，日志保留完整堆栈便于排查
        log.error("AI 模块未知异常", ex);
        return R.error(99000, "AI 服务暂时不可用，请稍后再试");
    }
}
