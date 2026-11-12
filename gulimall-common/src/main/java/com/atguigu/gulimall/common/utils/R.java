/**
 * Copyright (c) 2016-2019 人人开源 All rights reserved.
 *
 * https://www.renren.io
 *
 * 版权所有，侵权必究！
 */

package com.atguigu.gulimall.common.utils;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Data;
import org.apache.http.HttpStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * 返回数据
 *
 * @author Mark sunlightcs@gmail.com
 */
public class R extends HashMap<String, Object> {
	private static final long serialVersionUID = 1L;

	public <T> T getData(String key, TypeReference<T> typeReference) {
		Object data = get(key);
		String jsonString = JSON.toJSONString(data);
        return JSON.parseObject(jsonString, typeReference.getType());
	}
	
	public R() {
		put("code", 0);
		put("msg", "success");
	}
	
	public static R error() {
		return error(HttpStatus.SC_INTERNAL_SERVER_ERROR, "未知异常，请联系管理员");
	}
	
	public static R error(String msg) {
		return error(HttpStatus.SC_INTERNAL_SERVER_ERROR, msg);
	}
	
	public static R error(int code, String msg) {
		R r = new R();
		r.put("code", code);
		r.put("msg", msg);
		return r;
	}

	public static R ok(String msg) {
		R r = new R();
		r.put("msg", msg);
		return r;
	}
	
	public static R ok(Map<String, Object> map) {
		R r = new R();
		r.putAll(map);
		return r;
	}
	
	public static R ok() {
		return new R();
	}

	public R put(String key, Object value) {
		super.put(key, value);
		return this;
	}

	public Integer getCode(){
		return (Integer) this.get("code");
	}
}

// 更推荐这种返回结果
@Data
class Result<T> {

	//返回码
	private Integer code;

	//返回消息
	private String message;

	//返回数据
	private T data;

	// 私有化构造
	private Result() {}

	// 返回数据
	public static <T> Result<T> build(T body, Integer code, String message) {
		Result<T> result = new Result<>();
		result.setData(body);
		result.setCode(code);
		result.setMessage(message);
		return result;
	}
	// Result<T>.build(...);
	// save/update方法泛型就写Void

}
