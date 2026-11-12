package com.atguigu.gulimall.seckill.interceptor;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.common.constant.AuthServerConstant;
import com.atguigu.gulimall.common.enums.BizCodeEnum;
import com.atguigu.gulimall.common.utils.R;
import com.atguigu.gulimall.seckill.thread.MemberResponseVoThreadLocal;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 秒杀抢购接口的登录校验。
 *
 * <p>抢购是 JSON 接口，未登录直接返回错误码，不 302 到登录页。
 */
@Component
public class LoginUserInterceptor implements HandlerInterceptor {

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        // 1. 从共享 session 里取登录会员
        HttpSession session = request.getSession(false);
        MemberResponseVo member = session == null
                ? null : (MemberResponseVo) session.getAttribute(AuthServerConstant.LOGIN_USER);
        if (member != null) {
            MemberResponseVoThreadLocal.set(member);
            return true;
        }

        // 2. 未登录：返回统一错误 JSON
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                R.error(BizCodeEnum.SECKILL_NOT_LOGIN.getCode(),
                        BizCodeEnum.SECKILL_NOT_LOGIN.getMsg())));
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 1. 请求结束后清掉 ThreadLocal，防止线程池复用线程时把上一个会员的信息带给下一个请求
        MemberResponseVoThreadLocal.remove();
    }
}
