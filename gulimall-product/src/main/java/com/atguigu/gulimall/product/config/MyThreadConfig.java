package com.atguigu.gulimall.product.config;

import com.atguigu.gulimall.product.property.ThreadPoolConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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

        // ---------- 优雅关闭 ----------
        // 这两行等价于原来手写的 @PreDestroy + shutdownAndAwait：
        //   关闭时先 shutdown()，等在途任务跑完；超时再 shutdownNow() 强制中断
        executor.setWaitForTasksToCompleteOnShutdown(true);   // 等任务跑完，而不是立刻关
        executor.setAwaitTerminationSeconds(60);              // 最多等 60 秒

        // ★ 必须调用：不调的话线程池不会真正创建，第一次提交任务时才初始化
        executor.initialize();

        return executor;
    }

}
