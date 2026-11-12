package com.atguigu.gulimall.common.advice;

import com.atguigu.gulimall.common.constant.AuthServerConstant;
import com.atguigu.gulimall.common.region.CityStore;
import com.atguigu.gulimall.common.region.RegionCatalog;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 给所有模块的所有页面统一注入公共 model 属性（各站点地址 + 登录态）。
 * <p>
 * 以前每个 Controller 方法里都要手写一遍 {@code model.addAttribute("authUrl", authUrl)}、
 * 从 session 里取 loginUser，现在集中到这里，各 Controller 直接删掉那些行即可。
 * <p>
 * 注意：只放【不需要查库】的东西 —— @ModelAttribute 方法每个请求都会跑一遍。
 * <p>
 * ⚠️ 限定 SERVLET 环境：gateway 是 webflux（没有 HttpSession），
 * 不加这个条件注解 gateway 一启动就会因为找不到 HttpSession 而失败。
 */
@ControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GlobalModelAdvice {

    @Value("${gulimall.shop-home-url}")
    private String shopHomeUrl;

    @Value("${gulimall.auth-url}")
    private String authUrl;

    @Value("${gulimall.cart-url}")
    private String cartUrl;

    @Value("${gulimall.order-url}")
    private String orderUrl;

    @Value("${gulimall.member-url}")
    private String memberUrl;

    /** Redis 城市存储；没引入 Redis 的模块为 null，自动退化成只读 cookie */
    @Autowired(required = false)
    private CityStore cityStore;

    /**
     * @ModelAttribute 标注的方法，会在【每个】Controller 方法执行【之前】先跑一遍，
     * 往 model 里塞的东西，页面上直接就能用。
     */
    @ModelAttribute
    public void addCommonAttributes(Model model, HttpServletRequest request) {
        // 1、各站点地址（配置值，直接塞）
        model.addAttribute("shopHomeUrl", shopHomeUrl);
        model.addAttribute("authUrl", authUrl);
        model.addAttribute("cartUrl", cartUrl);
        model.addAttribute("orderUrl", orderUrl);
        model.addAttribute("memberUrl", memberUrl);

        // 2、登录态。
        //    这里的 session 由 Spring Session 提供，底层是 Redis —— 值是 auth-server
        //    登录成功时写进去的。只要两个模块的 namespace / cookie domain / cookie name
        //    一致（见 common.yaml 的 SESSION_*），跨模块、跨实例都能读到同一份会话。
        //    未登录时【不往 model 里塞】，模板统一用 loginUser == null 判断
        //
        // ⚠️ 用 request.getSession(false)：拿不到就返回 null，别凭空创建一个 session
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object loginUser = session.getAttribute(AuthServerConstant.LOGIN_USER);
            if (loginUser != null) {
                model.addAttribute("loginUser", loginUser);
            }
        }

        // 3、当前城市。
        //    城市只由首页自动定位产生，存 Redis（按 visitorId）+ 30 天 cookie。
        //    这里【不碰 Spring Session】，避免每个匿名访问多出一条 session 记录。
        //    获取不到时显示“请选择地址”，不再假装成北京。
        String city = readCityCookie(request);
        if (city == null && cityStore != null) {
            String visitorId = readCookie(request, AuthServerConstant.VISITOR_COOKIE);
            if (visitorId != null) {
                city = cityStore.get(visitorId);
            }
        }
        boolean cityResolved = city != null && !city.isBlank();
        model.addAttribute("currentCity", cityResolved ? city : "请选择地址");
        model.addAttribute("cityResolved", cityResolved);

        // 4、地址下拉框的省级列表：服务端动态生成，HTML 里不再写死假列表
        model.addAttribute("provinces", RegionCatalog.provinces());
    }

    /** 读取城市 cookie（值做了 URL 编码，避免中文非法字符） */
    private String readCityCookie(HttpServletRequest request) {
        String value = readCookie(request, AuthServerConstant.CITY_COOKIE);
        if (value == null) {
            return null;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

}
