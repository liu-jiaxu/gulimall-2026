package com.atguigu.gulimall.cart.thread;

import com.atguigu.gulimall.api.cart.UserInfoTo;

/**
 * @ClassName: UserThreadLocal
 * @Package: com.atguigu.gulimall.cart.thread
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/7 - 19:09
 * @Version: v1.0
 */
public class UserThreadLocal {

    /**
     * ThreadLocal  同一个线程共享数据
     *
     * <p>⚠️ 普通 ThreadLocal 不会传到 threadPoolExecutor 里的异步任务：子线程取出来永远是 null。
     * 所以异步任务（CompletableFuture.supplyAsync 那些）里不能调 UserThreadLocal.get()，
     * 需要用户信息就显式当参数传进去
     */
    public static final ThreadLocal<UserInfoTo> userThreadLocal = new ThreadLocal<>();

    public static UserInfoTo get() {
        return userThreadLocal.get();
    }

    public static void set(UserInfoTo userInfoTo) {
        userThreadLocal.set(userInfoTo);
    }

    public static void remove() {
        userThreadLocal.remove();
    }

}
