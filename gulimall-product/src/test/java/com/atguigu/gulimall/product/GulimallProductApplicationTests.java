package com.atguigu.gulimall.product;

import com.atguigu.gulimall.product.entity.BrandEntity;
import com.atguigu.gulimall.product.service.BrandService;
import com.atguigu.gulimall.product.service.CategoryService;
import com.atguigu.gulimall.product.vo.Catelog2Vo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.redisson.RedissonLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class GulimallProductApplicationTests {

    @Autowired
    BrandService brandService;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    RedissonClient redissonClient;

    @Autowired
    CategoryService categoryService;

    @Test
    void contextLoads() {
        BrandEntity brandEntity = new BrandEntity();
        brandEntity.setDescript("这是测试数据2");
        brandEntity.setName("oppo");
        boolean save = brandService.save(brandEntity);
        System.out.println(save);

        List<BrandEntity> list = brandService.list(
                new LambdaQueryWrapper<BrandEntity>().eq(BrandEntity::getBrandId, 1L));
        list.forEach(System.out::println);
    }

    @Test
    void redisTest() {
        ValueOperations<String, String> ops = stringRedisTemplate.opsForValue();
        ops.set("hello", "word" + UUID.randomUUID());
        System.out.println(ops.get("hello"));
        ops.getAndDelete("hello");
    }

    @Test
    void redissonClientTest() {
        System.out.println(redissonClient);
        try (ExecutorService threadPool = Executors.newFixedThreadPool(10)) {

            threadPool.execute(() -> {
                // 获取锁
                // Redisson锁实现JUC包的Lock（可重入锁），用法都一样
                RLock lock = redissonClient.getLock("mt-lock");
                // 加锁
                try {
                    /*
                    自动续期：
                        lock.lock() 获取锁后，Redisson 的 Watchdog 会定期给锁重新设置过期时间（默认30s ），
                        只要持锁的客户端还正常运行，锁 TTL 到期后就会自动续期（默认30 / 3 = 10s）。
                    自动删除：
                        业务正常结束调用 unlock() 时，Redisson 会立即删除 Redis 中的锁；
                        如果客户端/JVM 崩溃无法 unlock()，Watchdog 停止续期，锁的 TTL 到期后 Redis 会自动删除锁。
                     */
                    lock.lock();
                    // 实际开发：手动指定过期时间，省掉了看门狗和续期操作
                    // 或者自动设置锁TTL时间，到期后自动删除锁，但是TTL时间一定要大于业务执行时间，
                    // 否则到期锁自动unlock，其它线程立即获得锁进入业务，导致多线程相互影响
                    lock.lock(10, TimeUnit.SECONDS);
                    Thread.sleep(3000);
                    System.out.println(Thread.currentThread().threadId());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    lock.unlock();
                    System.out.println(Thread.currentThread().threadId());
                }
            });
        }

    }

    /**
     * 缓存击穿压测：200 个虚拟线程同时访问 getCatelogJson()
     * <p>
     * 预期：只有抢到 catelog-lock 的那 1 个线程真正查了 DB，其余 199 个线程在锁内二次检查 Redis 后直接命中缓存。
     * 若分布式锁失效，会看到大量线程同时压 DB —— 表现为单线程耗时普遍偏高、且 SQL 日志刷屏。
     * <p>
     * 想精确统计 DB 查询次数，可在 application.yaml 打开 SQL 日志：
     * <pre>
     * logging:
     *   level:
     *     com.atguigu.gulimall.product.dao: debug
     * </pre>
     * 正常情况下本次运行只应打印一轮查询 SQL，而不是 200 轮。
     */
    @Test
    void getCatelogJsonConcurrentTest() throws Exception {
        final int THREAD_COUNT = 200;

        // 0.清空缓存，保证 200 个线程都从"缓存未命中"这个临界点开始，否则测不出击穿。
        //  catelog-lock 的 TTL 是 30 分钟，若上次测试被中途打断会残留在 Redis 里，
        //  导致本次全部线程阻塞到锁过期（最长 30 分钟），因此一并清理兜底。
        stringRedisTemplate.delete("catelogJSON");
        stringRedisTemplate.delete("catelog-lock");

        // 三把闩锁协同：ready 确认 200 个线程已就位，fire 是发令枪，done 用于等待全部结束。
        // 没有 fire 的话，先提交的线程可能早已跑完并写好缓存，后提交的线程直接命中缓存，
        // 就不是"同时并发"了，测不出击穿场景。
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        // 记录每个线程的耗时（ms），用于观察是"1 次 DB + 199 次缓存"还是"雪崩式打 DB"
        List<Long> costs = Collections.synchronizedList(new ArrayList<>(THREAD_COUNT));

        // Java 21 虚拟线程：每个任务分配一个虚拟线程，提交 200 个任务即 200 个虚拟线程。
        // 相比 Executors.newFixedThreadPool(200)，虚拟线程由 JVM 调度到少量载体线程上，
        // 不需要为每个线程预留约 1MB 的栈空间，且阻塞（等待 Redis / 锁）时会自动让出载体线程。
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            for (int i = 0; i < THREAD_COUNT; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        fire.await();   // 所有线程卡在这里，确保在同一瞬间发起请求

                        long begin = System.currentTimeMillis();
                        Map<String, List<Catelog2Vo>> result = categoryService.getCatelogJson();
                        costs.add(System.currentTimeMillis() - begin);

                        if (result != null && !result.isEmpty()) {
                            success.incrementAndGet();
                        } else {
                            failure.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failure.incrementAndGet();
                        System.err.println("线程执行异常: " + e.getClass().getSimpleName()
                                + " - " + e.getMessage());
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();                  // 等 200 个虚拟线程全部就位
            System.out.println(">>> " + THREAD_COUNT + " 个虚拟线程已就绪，发令并发...");

            long start = System.currentTimeMillis();
            fire.countDown();               // 发令，200 个线程同时冲

            boolean finished = done.await(3, TimeUnit.MINUTES);
            long total = System.currentTimeMillis() - start;

            System.out.println(">>> 全部完成=" + finished + "，总耗时=" + total + " ms");
            System.out.println(">>> 成功=" + success.get() + "，失败=" + failure.get());

            if (!costs.isEmpty()) {
                LongSummaryStatistics stat = costs.stream()
                        .mapToLong(Long::longValue)
                        .summaryStatistics();
                System.out.printf(">>> 单线程耗时  min=%d ms  max=%d ms  avg=%.1f ms%n",
                        stat.getMin(), stat.getMax(), stat.getAverage());
            }

            // 运行后缓存应已写入，TTL 接近 1 天（86400 秒）
            Long ttl = stringRedisTemplate.getExpire("catelogJSON", TimeUnit.SECONDS);
            System.out.println(">>> 运行后 catelogJSON 剩余 TTL（秒）= " + ttl);

            assertEquals(THREAD_COUNT, success.get(), "200 个线程应全部成功获取到分类数据");
        }
    }

}
