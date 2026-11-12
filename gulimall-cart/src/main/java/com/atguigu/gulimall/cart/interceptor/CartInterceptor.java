package com.atguigu.gulimall.cart.interceptor;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;
import com.atguigu.gulimall.api.cart.UserInfoTo;
import com.atguigu.gulimall.cart.thread.UserThreadLocal;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.util.UUID;

import static com.atguigu.gulimall.common.constant.AuthServerConstant.LOGIN_USER;
import static com.atguigu.gulimall.common.constant.CartConstant.TEMP_USER_COOKIE_NAME;
import static com.atguigu.gulimall.common.constant.CartConstant.TEMP_USER_COOKIE_TIMEOUT;

/**
 * @Description: 在执行目标方法之前，判断用户的登录状态.并封装传递给controller目标请求
 **/
public class CartInterceptor implements HandlerInterceptor {

    private final String sessionCookieDomain;

    public CartInterceptor(String sessionCookieDomain) {
        this.sessionCookieDomain = sessionCookieDomain;
    }

    /***
     * 目标方法执行之前
     * @param request
     * @param response
     * @param handler
     * @return
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {

        UserInfoTo userInfoTo = new UserInfoTo();

        HttpSession session = request.getSession(false);
        //获得当前登录用户的信息
        MemberResponseVo memberResponseVo = (session == null) ? null
                : (MemberResponseVo) session.getAttribute(LOGIN_USER);

        if (memberResponseVo != null) {
            //用户登录了
            userInfoTo.setUserId(memberResponseVo.getId());

            // 用户名或昵称存储进Attribute
            request.setAttribute(LOGIN_USER, memberResponseVo);

            // 因为要做游客购物车和用户购物车合并，因此登录用户也需要知道之前临时用户的user-key
            // 所以登录用户还要额外存一个游客用户的user-key，不能直接return
        }

        // 获取所有cookie
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                //user-key
                String name = cookie.getName();
                if (TEMP_USER_COOKIE_NAME.equals(name)) {
                    userInfoTo.setUserKey(cookie.getValue());
                    //标记为已是临时用户
                    userInfoTo.setTempUser(true);

                    UserThreadLocal.set(userInfoTo);
                    return true;
                }
            }
        }

        //如果没有临时用户一定分配一个临时用户  生成UUID
        if (StringUtils.isEmpty(userInfoTo.getUserKey())) {
            String uuid = UUID.randomUUID().toString();
            userInfoTo.setUserKey(uuid);
            userInfoTo.setTempUser(true);
        }

        //目标方法执行之前  使用ThreadLocal共享数据
        UserThreadLocal.set(userInfoTo);
        return true;

    }


    /**
     * 业务执行之后，分配临时用户，让浏览器保存
     * @param request
     * @param response
     * @param handler
     * @param modelAndView
     */
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {

        //获取当前用户的值
        UserInfoTo userInfoTo = UserThreadLocal.get();

        // 浏览器cookie保存当前临时用户
        if (userInfoTo != null && userInfoTo.getTempUser()) {
            //创建一个cookie
            Cookie cookie = new Cookie(TEMP_USER_COOKIE_NAME, userInfoTo.getUserKey());
            //扩大作用域
            cookie.setDomain(sessionCookieDomain);
            //设置过期时间
            cookie.setMaxAge(TEMP_USER_COOKIE_TIMEOUT);
            response.addCookie(cookie);
        }

    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserThreadLocal.remove();
    }
}
