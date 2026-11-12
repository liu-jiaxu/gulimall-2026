package com.atguigu.gulimall.seckill.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.utils.R;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Web 层统一限流/熔断响应：返回项目统一的 R JSON，而不是 Sentinel 默认的纯文本。
 */
@Component
public class SentinelWebBlockHandler implements BlockExceptionHandler {

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       String resourceName, BlockException e) throws Exception {
        // 1. HTTP 状态码仍然返回 200，让前端走正常响应解析，而不是当成服务器错误
        response.setStatus(HttpServletResponse.SC_OK);
        // 2. 声明响应体是 UTF-8 的 JSON，避免中文提示乱码
        response.setContentType("application/json;charset=UTF-8");
        // 3. 用项目统一的 R 结构包装限流错误码和提示，前端按业务错误处理
        R result = R.error(BizCodeEnum.SECKILL_FLOW_LIMIT.getCode(),
                BizCodeEnum.SECKILL_FLOW_LIMIT.getMsg());
        // 4. 把 R 对象序列化成 JSON 写回响应体，替换 Sentinel 默认的纯文本提示
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
