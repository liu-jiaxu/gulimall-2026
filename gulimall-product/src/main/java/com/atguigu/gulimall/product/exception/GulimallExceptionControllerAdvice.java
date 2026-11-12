package com.atguigu.gulimall.product.exception;

import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.utils.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * @ClassName: GulimallExceptionControllerAdvice
 * @Package: com.atguigu.gulimall.product.exception
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/3 - 12:26
 * @Version: v1.0
 */
@RestControllerAdvice(basePackages = "com.atguigu.gulimall.product.controller")
@Slf4j
public class GulimallExceptionControllerAdvice {

    // 处理数据校验异常
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public R handleValidException(MethodArgumentNotValidException e){
        // @Valid 校验失败后会抛出 MethodArgumentNotValidException 异常
        // 异常中包含了 BindingResult 对象，BindingResult 对象中包含了所有的校验错误信息
        BindingResult bindingResult = e.getBindingResult();
        Map<String, String> errorMap = new HashMap<>();
        bindingResult.getFieldErrors().forEach((fieldError)->{
            errorMap.put(fieldError.getField(), fieldError.getDefaultMessage());
        });

        log.error("数据校验出现问题：{}，异常类型：{}", e.getMessage(), e.getClass());
        return R.error(BizCodeEnum.VALID_EXCEPTION.getCode(), BizCodeEnum.VALID_EXCEPTION.getMsg()).put("data", errorMap);
    }

    //处理全局异常
    @ExceptionHandler(value = Throwable.class)
    public R handleException(Throwable throwable){
        log.error("系统出现异常：{}，异常类型：{}", throwable.getMessage(), throwable.getClass());
        return R.error(BizCodeEnum.UNKNOW_EXCEPTION.getCode(), BizCodeEnum.UNKNOW_EXCEPTION.getMsg());
    }

}
