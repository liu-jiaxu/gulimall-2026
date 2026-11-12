package com.atguigu.gulimall.common.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Controller 与定时任务日志切面
 * <p>
 * 拦截所有微服务的 Controller 方法和 {@code @Scheduled} 定时任务方法，在方法执行前后打印日志：
 * <ul>
 *     <li>方法开始：{@code 类名.方法名 start, 入参: [...]}，无入参只打印 {@code 类名.方法名 start}</li>
 *     <li>方法结束：{@code 类名.方法名 end, 出参: {...}, 耗时: xxms}，无出参只打印 {@code 类名.方法名 end, 耗时: xxms}</li>
 * </ul>
 * 由于本类位于 {@code com.atguigu.gulimall.common}（业务模块默认扫不到），
 * 由 {@link com.atguigu.gulimall.common.aspect.ControllerLogAutoConfiguration} 通过 {@code AutoConfiguration.imports} 注册为 Bean 生效。
 *
 * <p><b>关于 Mono / Flux 返回值</b>：响应式返回值是「懒」的 —— 方法返回时流还没开始执行，
 * 所以不能在方法返回处直接打印出参和耗时（那样只会得到 {@code MonoOnErrorResume} 这样的
 * 对象名字符串，耗时恒为 0）。必须把日志挂到流上，等数据真正到达 / 出错时再打印。
 *
 * @author liujiaxu
 */
@Aspect
@Slf4j
public class ControllerLogAspect {

    /**
     * 切点：三类业务入口，三者【并列】：
     * <ul>
     *     <li>{@code @RestController} 标注的类 —— 所有模块的 REST 接口，
     *         包括 controller 包，以及 agent/rag、auth_server/agent、k8s/* 等非标准包</li>
     *     <li>{@code @Controller} 标注的类 —— 返回页面的传统控制器，
     *         如 {@code com.atguigu.gulimall.product.web.IndexController}（Thymeleaf 页面 + 页面 AJAX 接口）</li>
     *     <li>{@code @Scheduled} 标注的方法 —— 所有定时任务，
     *         不依赖 task、scheduled、component 等包名</li>
     * </ul>
     * 不匹配 {@code @Configuration}、{@code @Bean}、{@code @Service}、{@code @ConfigurationProperties}，
     * 避免切面代理创建切面自身的配置类，形成循环依赖。
     */
    @Pointcut("within(@org.springframework.web.bind.annotation.RestController *)"
            + " || within(@org.springframework.stereotype.Controller *)"
            + " || execution(@org.springframework.scheduling.annotation.Scheduled * *(..))")
    public void controllerPointcut() {
    }

    /**
     * 环绕通知：Controller 与定时任务方法执行前后打印 start / end 日志 + 耗时
     */
    @Around("controllerPointcut()")
    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {

        // 类名 + 方法名（如 ProductController.list）
        String classAndMethod = joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName();

        // 1、方法开始：打印 start + 入参（无入参只打印 start）
        Object[] args = joinPoint.getArgs();
        if (args != null && args.length > 0) {
            log.info("{}. start, 入参: {}", classAndMethod, Arrays.toString(args));
        } else {
            log.info("{}. start", classAndMethod);
        }

        long start = System.currentTimeMillis();

        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable e) {
            // 同步抛出的异常：在这里记一笔再往外抛（交给全局异常处理器）
            log.error("{}. 抛出异常, 耗时: {}ms, 异常: {}", classAndMethod,
                    System.currentTimeMillis() - start, e.toString());
            throw e;
        }

        // ==================== 响应式返回值：Mono ====================
        // doOnSuccess 在 Mono 成功结束时触发【一次】（空 Mono 也会触发，value 为 null），
        // doOnError 在流内出错时触发 —— 这才能真正拿到结果和真实耗时
        if (result instanceof Mono<?> mono) {
            return mono
                    .doOnSuccess(value -> logEnd(classAndMethod, value, start))
                    .doOnError(e -> logReactiveError(classAndMethod, e, start));
        }

        // ==================== 响应式返回值：Flux ====================
        // Flux 可能有大量元素，把内容全打进日志会刷屏，所以只统计元素个数
        if (result instanceof Flux<?> flux) {
            AtomicLong count = new AtomicLong();
            return flux
                    .doOnNext(v -> count.incrementAndGet())
                    .doOnComplete(() -> log.info("{}. end(Flux), 元素数: {}, 耗时: {}ms",
                            classAndMethod, count.get(), System.currentTimeMillis() - start))
                    .doOnError(e -> logReactiveError(classAndMethod, e, start));
        }

        // ==================== 普通返回值：返回时结果已就绪 ====================
        logEnd(classAndMethod, result, start);
        return result;
    }

    /**
     * 打印结束日志（出参 + 耗时）
     */
    private void logEnd(String classAndMethod, Object result, long start) {
        long cost = System.currentTimeMillis() - start;
        if (result != null) {
            log.info("{}. end, 出参: {}\n耗时: {}ms", classAndMethod, result, cost);
        } else {
            log.info("{}. end, 耗时: {}ms", classAndMethod, cost);
        }
    }

    /**
     * 打印响应式流内部的异常
     *
     * <p>注意：流内异常不会传播到切面（它是异步发生的，切面早已返回），
     * 所以必须单独记一笔，否则这类错误在日志里会完全不可见。
     */
    private void logReactiveError(String classAndMethod, Throwable e, long start) {
        log.error("{}. 流式异常, 耗时: {}ms, 异常: {}", classAndMethod,
                System.currentTimeMillis() - start, e.toString());
    }

}
