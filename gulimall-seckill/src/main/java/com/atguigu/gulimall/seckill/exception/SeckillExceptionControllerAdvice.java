package com.atguigu.gulimall.seckill.exception;

import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 秒杀模块统一异常处理：业务异常转成 {code, msg}，不让前端只看到 500。
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.atguigu.gulimall.seckill.controller")
public class SeckillExceptionControllerAdvice {

    @ExceptionHandler(RRException.class)
    public R handleRRException(RRException e) {
        // 1. 记一条告警日志，保留错误码和提示，方便定位是哪条业务规则没通过
        log.warn("[seckill] 业务异常：code={}, msg={}", e.getCode(), e.getMsg());
        // 2. 按异常自带的错误码和提示返回，前端拿到的仍是标准 R 结构
        return R.error(e.getCode(), e.getMsg());
    }

    @ExceptionHandler(Throwable.class)
    public R handleException(Throwable throwable) {
        // 1. 错误日志带上异常堆栈，方便排查未知的系统异常
        log.error("[seckill] 系统异常：{}", throwable.getMessage(), throwable);
        // 2. 对外只返回统一的未知异常提示，不把内部堆栈信息暴露给前端
        return R.error(BizCodeEnum.UNKNOW_EXCEPTION.getCode(),
                BizCodeEnum.UNKNOW_EXCEPTION.getMsg());
    }
}
