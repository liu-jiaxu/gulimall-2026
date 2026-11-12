package com.atguigu.gulimall.product.config;

import com.atguigu.gulimall.product.service.CategoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * @ClassName: CacheWarmUpRunner
 * @Package: com.atguigu.gulimall.product.config
 * @Description: 启动预热分类树缓存
 * @Author: 刘家旭
 * @Create: 2026/9/22
 * @Version: v1.0
 */
@Slf4j
@Component
public class CacheWarmUpRunner implements ApplicationRunner {

    private final CategoryService categoryService;

    public CacheWarmUpRunner(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * 应用启动完成后立刻调一次查询，把分类树预热进 L1 + L2 缓存。
     * <p>
     * 背景：@Cacheable(sync = true) 会让冷缓存时的并发请求全部阻塞、等待同一个加载线程，
     * 所以"第一次访问"表现为一批请求集体等待一次完整加载。实测首个 JMeter 压测 P99 达 1130ms
     * （其中还混着 MyBatis 惰性初始化、JIT、GZIP 类加载等一次性开销）。
     * 把这次加载挪到启动阶段，用户请求就基本看不到冷缓存了。
     */
    @Override
    public void run(ApplicationArguments args) {
        long begin = System.currentTimeMillis();
        try {
            categoryService.getCatelogJson();
            log.info("[catelog] 启动预热完成，耗时 {} ms", System.currentTimeMillis() - begin);
        } catch (Exception e) {
            // 预热失败不能拖垮启动：缓存没热起来，第一个业务请求会自行加载
            log.warn("[catelog] 启动预热失败，不影响启动，首个请求将自行加载", e);
        }
    }
}
