package com.atguigu.gulimall.order.exception;

import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 订单模块统一异常处理。
 *
 * <p>没有这个处理器时，服务层抛出的 RRException（例如退款失败、退货单不存在）
 * 会被 Spring 当成未处理异常，直接返回 HTTP 500，前端只能看到“修改失败”，
 * 看不到具体原因。这里统一转成 {code, msg}。
 */
@Slf4j
@RestControllerAdvice(basePackages = {
        "com.atguigu.gulimall.order.controller",
        "com.atguigu.gulimall.order.web"
})
public class OrderExceptionControllerAdvice {

    @ExceptionHandler(RRException.class)
    public R handleRRException(RRException e) {
        log.warn("[order] 业务异常：code={}, msg={}", e.getCode(), e.getMsg());
        return R.error(e.getCode(), e.getMsg());
    }

    @ExceptionHandler(Throwable.class)
    public R handleException(Throwable throwable) {
        log.error("[order] 系统异常：{}", throwable.getMessage(), throwable);
        return R.error(BizCodeEnum.UNKNOW_EXCEPTION.getCode(),
                BizCodeEnum.UNKNOW_EXCEPTION.getMsg());
    }
}
