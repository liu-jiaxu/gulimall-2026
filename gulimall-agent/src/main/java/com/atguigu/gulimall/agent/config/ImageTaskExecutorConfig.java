package com.atguigu.gulimall.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 图片生成专用线程池。
 *
 * <p>不能直接用 Tomcat 请求线程同步等图：一次 1～3 分钟，会把聊天和 SSE 一起拖死。
 * 这里单独 1～2 个线程、20 个排队位；满了立即提示“任务繁忙”，不无限堆积。
 */
@Configuration
public class ImageTaskExecutorConfig {

    /**
     * 创建图片生成专用线程池。
     *
     * @return 已初始化、线程名带 ai-image- 前缀的线程池执行器
     */
    @Bean(name = "imageTaskExecutor")
    public ThreadPoolTaskExecutor imageTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 1. 图片模型很占资源，最多同时跑 2 个
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);

        // 2. 线程名带前缀，日志里一眼能看出是图片任务
        executor.setThreadNamePrefix("ai-image-");

        // 3. 停机时给正在生成的图片一点收尾时间
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
