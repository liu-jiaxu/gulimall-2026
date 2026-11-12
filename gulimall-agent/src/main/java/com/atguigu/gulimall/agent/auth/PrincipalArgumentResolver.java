package com.atguigu.gulimall.agent.auth;

import com.atguigu.gulimall.agent.exception.AgentErrorCode;
import com.atguigu.gulimall.agent.exception.AgentException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 让 Controller 方法直接写 {@code Principal principal} 参数。
 *
 * <p>没有 JWT Cookie 时抛 15001；JWT 存在但无效/过期时由 PrincipalResolver 抛 15012。
 */
@Component
@RequiredArgsConstructor
public class PrincipalArgumentResolver implements HandlerMethodArgumentResolver {

    private final PrincipalResolver principalResolver;

    /**
     * 判断当前参数是否需要由本解析器处理。
     *
     * @param parameter 待解析的 Controller 方法参数
     * @return 参数类型为 Principal 时返回 true
     */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Principal.class.equals(parameter.getParameterType());
    }

    /**
     * 从当前请求的 agent JWT Cookie 中解析登录身份。
     *
     * @param parameter     待解析的 Controller 方法参数
     * @param mavContainer  ModelAndView 容器，本方法不使用
     * @param webRequest    当前原生 Web 请求
     * @param binderFactory 数据绑定工厂，本方法不使用
     * @return 解析成功的当前请求身份
     */
    @Override
    public Object resolveArgument(@NonNull MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        // 1. 从当前请求解析身份
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        Principal principal = request == null ? null : principalResolver.resolve(request);

        // 2. 没登录直接拒绝，不进入业务逻辑
        if (principal == null) {
            throw new AgentException(AgentErrorCode.NOT_LOGIN, "请先登录后再使用 AI 助手");
        }
        return principal;
    }
}
