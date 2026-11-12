package com.atguigu.gulimall.seckill.thread;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;

/**
 * 当前请求的登录会员。只在拦截器覆盖的请求线程里有效，异步任务要显式传参。
 */
public class MemberResponseVoThreadLocal {

    private static final ThreadLocal<MemberResponseVo> HOLDER = new ThreadLocal<>();

    public static MemberResponseVo get() {
        // 1. 读取当前线程绑定的登录会员，没绑定过就返回 null
        return HOLDER.get();
    }

    public static void set(MemberResponseVo member) {
        // 1. 把拦截器解析出的会员绑定到当前线程，后续业务直接从这里取
        HOLDER.set(member);
    }

    public static void remove() {
        // 1. 请求结束时清掉线程变量，防止线程池复用时把上一个用户的登录信息带出来
        HOLDER.remove();
    }

    private MemberResponseVoThreadLocal() {
    }
}
