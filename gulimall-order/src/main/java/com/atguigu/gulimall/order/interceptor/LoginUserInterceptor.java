package com.atguigu.gulimall.order.interceptor;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.common.constant.AuthServerConstant;
import com.atguigu.gulimall.order.thread.MemberResponseVoThreadLocal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * @ClassName: LoginUserInterceptor
 * @Package: com.atguigu.gulimall.order.interceptor
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/14 - 09:50
 * @Version: v1.0
 */
@Component
public class LoginUserInterceptor implements HandlerInterceptor {

    @Value("${gulimall.auth-url}")
    private String authUrl;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(false);
        MemberResponseVo memberResponseVo = session == null ? null : (MemberResponseVo) session.getAttribute(AuthServerConstant.LOGIN_USER);
        if (memberResponseVo != null) {
            // 登录
            MemberResponseVoThreadLocal.set(memberResponseVo);
            return true;
        } else {
            // 未登录
            request.getSession().setAttribute("msg", "请先进行登录！");
            response.sendRedirect(authUrl + "/login.html");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        MemberResponseVoThreadLocal.remove();
    }
}
