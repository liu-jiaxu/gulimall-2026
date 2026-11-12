package com.atguigu.gulimall.order.thread;

import com.atguigu.gulimall.api.auth_server.MemberResponseVo;

/**
 * @ClassName: MemberResponseVoThreadLocal
 * @Package: com.atguigu.gulimall.cart.thread
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/14 - 10:09
 * @Version: v1.0
 */
public class MemberResponseVoThreadLocal {

    /**
     * ThreadLocal  同一个线程共享数据
     *
     * <p>⚠️ 普通 ThreadLocal 不会传到 threadPoolExecutor 里的异步任务：子线程取出来永远是 null。
     * 所以异步任务（CompletableFuture.supplyAsync 那些）里不能调 MemberResponseVoThreadLocal.get()，
     * 需要用户信息就显式当参数传进去
     */
    public static final ThreadLocal<MemberResponseVo> memberResponseVoThreadLocal = new ThreadLocal<>();

    public static MemberResponseVo get() {
        return memberResponseVoThreadLocal.get();
    }

    public static void set(MemberResponseVo memberResponseVo) {
        memberResponseVoThreadLocal.set(memberResponseVo);
    }

    public static void remove() {
        memberResponseVoThreadLocal.remove();
    }

}
