/**
 * Copyright (c) 2016-2019 人人开源 All rights reserved.
 * <p>
 * https://www.renren.io
 * <p>
 * 版权所有，侵权必究！
 */

package com.atguigu.gulimall.common.exception;

import com.atguigu.gulimall.common.enums.BizCodeEnum;
import lombok.Getter;
import lombok.Setter;

import java.text.MessageFormat;

/**
 * 自定义异常
 *
 * @author Mark sunlightcs@gmail.com
 */
@Setter
@Getter
public class RRException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private String msg;
    private int code = 500;

    public RRException(String msg) {
        super(msg);
        this.msg = msg;
    }

    public RRException(String msg, Throwable e) {
        super(msg, e);
        this.msg = msg;
    }

    public RRException(String msg, int code) {
        super(msg);
        this.msg = msg;
        this.code = code;
    }

    public RRException(String msg, int code, Throwable e) {
        super(msg, e);
        this.msg = msg;
        this.code = code;
    }

    public RRException(BizCodeEnum error) {
        this(error.getMsg(), error.getCode());
    }

    public RRException(BizCodeEnum error, Object... args) {
        this(MessageFormat.format(error.getMsg(), args), error.getCode());
    }

}
