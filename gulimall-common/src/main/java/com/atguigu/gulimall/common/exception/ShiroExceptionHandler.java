package com.atguigu.gulimall.common.exception;

import com.atguigu.gulimall.common.utils.R;
import org.apache.shiro.authz.UnauthenticatedException;
import org.apache.shiro.authz.UnauthorizedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Shiro 权限异常统一处理：
 * <ul>
 *     <li>未认证（未登录/登录过期）→ 401</li>
 *     <li>无权限 → 403</li>
 * </ul>
 * 由 {@link com.atguigu.gulimall.common.config.ShiroConfig} 通过 @Bean 注册，
 * 开关（gulimall.shiro.enabled）由 ShiroConfig 统一控制。
 *
 * @author liujiaxu
 */
@RestControllerAdvice
public class ShiroExceptionHandler {

    @ExceptionHandler(UnauthenticatedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public R handleUnauthenticated(UnauthenticatedException e) {
        return R.error(HttpStatus.UNAUTHORIZED.value(), "未登录或登录已过期");
    }

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public R handleUnauthorized(UnauthorizedException e) {
        return R.error(HttpStatus.FORBIDDEN.value(), "没有操作权限");
    }
}
