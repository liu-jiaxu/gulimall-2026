package com.atguigu.gulimall.common.constant;

/**
 * @ClassName: AuthServerConstant
 * @Package: com.atguigu.gulimall.common.constant
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/4 - 09:14
 * @Version: v1.0
 */
public class AuthServerConstant {

    public static final String SMS_CODE_CACHE_PREFIX = "sms:code:";

    public static final String LOGIN_USER = "loginUser";

    /**
     * 当前定位城市，存在 Spring Session（Redis）里。
     * 所有子域共用同一个 session cookie（domain=gulimall.vip），
     * 所以首页定位一次后，搜索、订单、商品详情等页面都能直接复用。
     */
    public static final String CURRENT_CITY = "currentCity";

    /**
     * 当前城市的持久化 cookie（Domain=gulimall.vip，30 天）。
     * Session 30 分钟不活跃会过期，cookie 用来兜底，避免隔天访问又弹定位。
     */
    public static final String CITY_COOKIE = "gulimall_city";

    /**
     * 匿名访问者 ID（30 天）。只用来作为 Redis 城市记录的 key，
     * 不参与登录态，也不会创建 Spring Session。
     */
    public static final String VISITOR_COOKIE = "gulimall_visitor";

}