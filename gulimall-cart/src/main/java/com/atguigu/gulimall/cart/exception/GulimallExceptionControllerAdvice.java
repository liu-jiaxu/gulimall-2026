package com.atguigu.gulimall.cart.exception;

import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.exception.RRException;
import com.atguigu.gulimall.common.utils.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * @ClassName: GulimallExceptionControllerAdvice
 * @Package: com.atguigu.gulimall.cart.exception
 * @Description: 购物车模块的异常处理 —— 统一转成 {code, msg} 的 JSON
 * @Author: 刘家旭
 * @Create: 2026/10/8
 * @Version: v1.0
 */
@RestControllerAdvice(basePackages = "com.atguigu.gulimall.cart.web")
@Slf4j
public class GulimallExceptionControllerAdvice {

    /**
     * 业务异常：把 RRException 里带的 BizCodeEnum 原样返回给前端
     *
     * <p>加购时商品无货走的就是这里，页面会直接显示 {"code":13001,"msg":"商品库存不足"}
     */
    @ExceptionHandler(value = RRException.class)
    public R handleRRException(RRException e) {
        log.warn("业务异常：错误码 {}，信息 {}", e.getCode(), e.getMsg());
        return R.error(e.getCode(), e.getMsg());
    }

    /**
     * 兜底：非预期异常只给前端一个通用提示，详细信息留在日志里
     */
    @ExceptionHandler(value = Throwable.class)
    public R handleException(Throwable throwable) {
        log.error("系统出现异常：{}，异常类型：{}", throwable.getMessage(), throwable.getClass(), throwable);
        return R.error(BizCodeEnum.UNKNOW_EXCEPTION.getCode(), BizCodeEnum.UNKNOW_EXCEPTION.getMsg());
    }

}
