package com.atguigu.gulimall.common.web;

import com.atguigu.gulimall.common.constant.AuthServerConstant;
import com.atguigu.gulimall.common.region.CityStore;
import com.atguigu.gulimall.common.utils.R;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 当前城市保存接口。
 *
 * <p>定位只在商城首页自动触发，定位成功后调这里。存储分三份：
 * <ul>
 *   <li>Redis：{@code gulimall:city:{visitorId}}，30 天，按访问者区分；</li>
 *   <li>cookie {@code gulimall_city}：30 天，服务端渲染时直接读；</li>
 *   <li>cookie {@code gulimall_visitor}：30 天匿名访问者 ID，作为 Redis key。</li>
 * </ul>
 *
 * <p>这里【不再调用 getSession】。之前城市写 Spring Session，每来一个没有 cookie
 * 的请求就多一条 {@code gulimall:session:sessions:*}，Redis 里会堆很多记录。
 */
@RestController
public class CityController {

    private static final int MAX_CITY_LENGTH = 32;
    private static final int COOKIE_MAX_AGE = 30 * 24 * 60 * 60;

    @Value("${server.servlet.session.cookie.domain:}")
    private String cookieDomain;

    @Autowired
    private CityStore cityStore;

    @PostMapping("/city/save")
    public R save(@RequestParam("city") String city,
                  HttpServletRequest request,
                  HttpServletResponse response) {
        if (city == null || city.isBlank()) {
            return R.error("城市不能为空");
        }
        String value = city.trim();
        if (value.length() > MAX_CITY_LENGTH) {
            value = value.substring(0, MAX_CITY_LENGTH);
        }

        String visitorId = readCookie(request, AuthServerConstant.VISITOR_COOKIE);
        if (visitorId == null || visitorId.isBlank()) {
            visitorId = UUID.randomUUID().toString().replace("-", "");
            writeCookie(response, AuthServerConstant.VISITOR_COOKIE, visitorId);
        }

        cityStore.save(visitorId, value);

        writeCookie(response, AuthServerConstant.CITY_COOKIE,
                URLEncoder.encode(value, StandardCharsets.UTF_8));

        return R.ok();
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

    private void writeCookie(HttpServletResponse response, String name, String value) {
        Cookie cookie = new Cookie(name, value);
        cookie.setPath("/");
        cookie.setMaxAge(COOKIE_MAX_AGE);
        cookie.setHttpOnly(true);
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            cookie.setDomain(cookieDomain);
        }
        response.addCookie(cookie);
    }
}
