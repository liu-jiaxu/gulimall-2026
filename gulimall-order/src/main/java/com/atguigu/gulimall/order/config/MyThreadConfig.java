package com.atguigu.gulimall.order.config;

import com.atguigu.gulimall.order.property.ThreadPoolConfigProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * @ClassName: MyThreadConfig
 * @Package: com.atguigu.gulimall.product
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/1 - 10:19
 * @Version: v1.0
 */
@Configuration
@EnableConfigurationProperties(ThreadPoolConfigProperties.class)
public class MyThreadConfig {

    /**
     * 业务线程池
     *
     * <p><b>为什么用 Spring 的 {@link ThreadPoolTaskExecutor}，而不是自己 new ThreadPoolExecutor：</b>
     * 它内部就是一个 ThreadPoolExecutor（需要原生对象时用 {@code getThreadPoolExecutor()} 取），
     * 但多了两样东西 ——
     * <ol>
     *   <li>实现了 {@code DisposableBean}：容器关闭时<b>自动</b>走优雅关闭流程，
     *       不需要自己写 {@code @PreDestroy} + {@code awaitTermination}</li>
     *   <li>优雅关闭的两个行为可配置（见下面「优雅关闭」两行）</li>
     * </ol>
     *
     * <p><b>七大参数：</b>
     * <pre>
     *   corePoolSize    核心线程数，一直存活（除非开 allowCoreThreadTimeOut）
     *   maximumPoolSize 最大线程数
     *   keepAliveTime   空闲存活时间：线程数超过 core 后，空闲这么久就回收
     *   unit            时间单位
     *   workQueue       阻塞队列：核心线程忙时，新任务先排队
     *   threadFactory   线程工厂：决定线程怎么创建、叫什么名字
     *   handler         拒绝策略：队列满且线程也满时怎么办
     * </pre>
     *
     * <p><b>工作顺序：</b>
     * <ol>
     *   <li>先创建 core 个核心线程接收任务</li>
     *   <li>核心线程忙 → 新任务进队列</li>
     *   <li>队列满 → 继续开线程，最多开到 max</li>
     *   <li>max 也满 → 走拒绝策略</li>
     *   <li>任务变少 → 超过 core 的空闲线程在 keepAliveTime 后被回收</li>
     * </ol>
     *
     * <p>举例：core=7、max=20、queue=50，同时来 100 个任务 →
     * 7 个立即执行、50 个进队列、再开 13 个执行，剩下 30 个被拒绝。
     */
    @Bean
    public ThreadPoolTaskExecutor threadPoolExecutor(ThreadPoolConfigProperties props) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // ---------- 七大参数 ----------
        executor.setCorePoolSize(props.getCorePoolSize());
        executor.setMaxPoolSize(props.getMaxPoolSize());
        executor.setKeepAliveSeconds(props.getKeepAliveTime());
        executor.setQueueCapacity(1000);
        // 线程名前缀：框架会自动补序号（gulimall-thread-1、gulimall-thread-2 …）
        executor.setThreadNamePrefix(props.getThreadNamePrefix() + "-");
        /* 拒绝策略：
             AbortPolicy         抛出异常（默认）—— 队列一满就直接报错给用户，压测时最先炸
             CallerRunsPolicy    调用者线程自己执行 —— 跑不动就让调用线程慢下来（背压），任务不丢
             DiscardPolicy       静默丢弃
             DiscardOldestPolicy 丢弃最旧的任务
         */
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // ---------- 请求上下文传递（异步任务里也要能拿到 RequestAttributes） ----------
        /*
           不挂这一行会怎样：
             FeignCookieConfig 的拦截器靠 RequestContextHolder.getRequestAttributes() 取 Cookie，
             而它内部就是个 ThreadLocal —— 线程池线程上没人 set 过，取出来永远是 null，
             于是 Cookie 不转发，cart 认不出登录用户（confirm.html 商品列表为空，还会凭空多一个游客车）。
           ⚠️ setTaskDecorator 必须放在 initialize() 之前：线程池是在 initialize() 里创建时就读取了它。
         */
        executor.setTaskDecorator(requestAttributesTaskDecorator());

        // ---------- 优雅关闭 ----------
        // 这两行等价于原来手写的 @PreDestroy + shutdownAndAwait：
        //   关闭时先 shutdown()，等在途任务跑完；超时再 shutdownNow() 强制中断
        executor.setWaitForTasksToCompleteOnShutdown(true);   // 等任务跑完，而不是立刻关
        executor.setAwaitTerminationSeconds(60);              // 最多等 60 秒

        // ★ 必须调用：不调的话线程池不会真正创建，第一次提交任务时才初始化
        executor.initialize();

        return executor;
    }

    /**
     * 【当前生效】手写 TaskDecorator：把「提交任务的线程」的 RequestAttributes 复制给「执行任务的线程池线程」。
     *
     * <p>三个关键点：
     * <ol>
     *   <li>{@code decorate} 是在<b>提交任务的主线程</b>上执行的 —— 所以这里取得到 attributes；
     *       它返回的那个 Runnable 才是在<b>线程池线程</b>上跑的</li>
     *   <li>{@code setRequestAttributes(attributes, true)} 里的 true = 同时写一份 inheritable 的，
     *       子线程再往下提交任务时还能继续传递（不加 true 只有当前这一层有效）</li>
     *   <li>{@code resetRequestAttributes()} 必须写在 finally —— 线程池的线程是<b>复用</b>的，
     *       不清理的话上一个请求的 attributes 会残留给下一个用这条线程的任务，
     *       表现就是 A 用户的 Cookie 被转发进了 B 用户的请求（越权看到别人的购物车）</li>
     * </ol>
     *
     * <p>为什么没用下面那个官方实现，见 {@link #contextPropagatingTaskDecorator()}。
     */
    static TaskDecorator requestAttributesTaskDecorator() {
        return runnable -> {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            return () -> {
                try {
                    RequestContextHolder.setRequestAttributes(attributes, true);
                    runnable.run();
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            };
        };
    }

    /**
     * 【备选】Spring 6.1+ 官方实现：{@code ContextPropagatingTaskDecorator}。
     *
     * <p>想换成它，上面 executor 里改成 {@code executor.setTaskDecorator(contextPropagatingTaskDecorator());} 即可，
     * 但要先补两样东西，否则这个「一行顶十行」的官方版根本跑不起来：
     * <ol>
     *   <li><b>加依赖</b>：order 的 pom.xml 里补 {@code io.micrometer:context-propagation}
     *       （版本交给 Spring Boot BOM 管）。当前 order 的依赖树里没有它，
     *       直接 new 这个类会在启动时抛 {@code NoClassDefFoundError: io.micrometer.context.ContextSnapshotFactory}
     *       —— 编译能过（构造器签名里没有这个类型），运行才炸</li>
     *   <li><b>注册 accessor</b>：它内部是 {@code ContextSnapshotFactory.captureAll()}，
     *       只会捕获<b>已注册到 Micrometer ContextRegistry</b> 的上下文。
     *       Spring 6.2 提供了适配器 {@code RequestAttributesThreadLocalAccessor}，
     *       但它<b>不会被自动注册</b>（spring-web 和 Spring Boot 里都没有对应的注册代码），
     *       要自己加一行：
     *       <pre>{@code ContextRegistry.getInstance().registerThreadLocalAccessor(new RequestAttributesThreadLocalAccessor());}</pre>
     *       对比一下：Spring Security 的 SecurityContext accessor 是自带 ServiceLoader 注册文件的，所以那个开箱即用</li>
     * </ol>
     *
     * <p>它的好处是内部用 {@code SnapshotServletRequestAttributes} 对 request 做了快照，
     * 比手写版把 request 对象带过去更抗「主线程先结束」的场景。
     */
    static TaskDecorator contextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }

}
