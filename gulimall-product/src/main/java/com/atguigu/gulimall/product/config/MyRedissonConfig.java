package com.atguigu.gulimall.product.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.EqualJitterDelay;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * @ClassName: MyRedissonConfig
 * @Package: com.atguigu.gulimall.product.config
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/22 - 10:16
 * @Version: v1.0
 */
@Configuration
public class MyRedissonConfig {

    @Value("${REDIS_HOST}")
    private String redisHost;

    @Value("${REDIS_PORT}")
    private int redisPort;

    @Value("${REDIS_PASSWORD}")
    private String redisPassword;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redisson() {
        Config config = new Config();

        SingleServerConfig single = config.useSingleServer();
        // redis启动SSH安全时需要用rediss://
        single.setAddress("redis://" + redisHost + ":" + redisPort);
        // Redis 的 requirepass 一旦开启，所有客户端都必须带密码，否则每条命令都返回 NOAUTH
        single.setPassword(redisPassword);
        // 集群模式可以指定多个ip
        // config.useClusterServers().addNodeAddress("redis://" + redisHost + ":" + redisPort, "redis://" + redisHost2 + ":" + redisPort2);

        /*
        ========================== 超时与重试 ==========================
        这一组是本次调优的核心，背景是一次 JMeter 200 并发压测：

            中位数 8ms（170 个样本正常）  第 90 百分位 10435ms
            最大值 13697ms               异常率 0.00%

        即"绝大多数请求很快，但尾部一批请求各自卡住约 10 秒"。

        根因不是慢，而是 Redisson 默认的"超时 + 重试"策略把一个
        偶发的慢命令指数级放大了：

            默认 timeout=3000、retryAttempts=4、retryInterval=1500
            一个重试周期 = 3000(等超时) + 1500(等间隔) = 4500ms
            前 2 次超时后第 3 次成功 = 3000+1500+3000+1500 ≈  9.0s  ← 对应那 30 个 ~10s 的样本
            前 3 次超时后第 4 次成功 = 再加 3000+1500        ≈ 13.5s  ← 对应最大值 13697ms

        这就是"10 秒"的来源。而真正把命令推过 3 秒红线的诱因，是
        catelogJSON 这个缓存值高达 66KB，200 个线程同时取它时，
        总有一部分命令在连接池里排队超过 3 秒。

        当前配置的最坏耗时：timeout(1000) + retryInterval(默认1500) + timeout(1000) ≈ 3.5s
        （原来 4 次重试时最坏 18s，降了约 5 倍）

        ===== 后续已在本文件之外完成的两项（治本手段）=====
        1. 加了 Caffeine 本地缓存（L1）+ 启动预热，绝大多数请求根本不走 Redis
        2. 缓存值 GZIP 压缩后用 byte[] 存储，66KB -> 14.7KB（压缩比 5.2 倍）
        这两项让"偶发慢命令"的概率大幅下降，因此这里的 timeout 后续还能收到 500ms 以内。
         */

        // 单条命令的等待上限，超时即判定失败。默认 3000ms。
        // 局域网内一条正常 GET 只要 5ms，3 秒对单条命令来说过于宽松，
        // 会让"偶发慢"整整阻塞 3 秒才暴露。收到 1000ms，兼顾压缩后约 14.7KB 的正常读取。
        single.setTimeout(1000);

        // 失败后的重试次数。默认 4 次 —— 这是把"慢"放大成"卡死"的元凶：
        // 每次重试都要再等一个完整的 timeout + retryInterval。
        // 缓存读失败时，退化成查一次 DB 远好过死等 13.5 秒，因此只留 1 次重试，
        // 用于吸收真正的瞬时网络抖动，同时不让故障被放大。
        single.setRetryAttempts(1);

        // 建立 TCP 连接的超时。默认 10000ms（10 秒）。
        // 局域网建连正常在 1ms 以内，10 秒纯粹是灾难性的干等 ——
        // 而且"10 秒"和压测中观察到的卡顿量级一致，是同一个坑的另一个入口。
        single.setConnectTimeout(3000);

        /*
        ========================== 连接池 ==========================
        200 并发去抢 64 个连接，排队本身就是延迟的一部分，
        也是把命令推过超时红线的直接原因，所以顺势扩容。
         */

        // 命令连接池上限。默认 64。
        // 高并发读缓存时连接明显不够用，调大到 128 减少"等连接"的排队时间，
        // 从源头降低命令越过 timeout 红线的概率。
        single.setConnectionPoolSize(128);

        // 常驻最小空闲连接数。默认 24。
        // 低于这个数 Redisson 会提前建连。如果等到请求来了才建连，
        // 建连是同步开销，会直接算进请求耗时。调到 32 保证池里随时有货。
        single.setConnectionMinimumIdleSize(32);

        // 连接空闲多久后被回收。默认 10000ms（10 秒）。
        // 压测往往是一阵一阵的，10 秒空闲就把连接全关掉，
        // 下一波请求到来时全部要重新建连 —— 又撞上 connectTimeout。
        // 放宽到 30 秒，避免连接反复销毁重建。
        single.setIdleConnectionTimeout(30000);

        // 定时 PING 保活间隔，默认 30000ms。
        // 防止连接被 Redis 服务端或中间的防火墙/NAT 静默断开
        // （连接看着还在，实际已断，请求会一直卡到超时）。显式写出来表达意图。
        single.setPingConnectionInterval(30000);

        /*
        另外两个保持默认、但值得知道的参数：

        subscriptionConnectionPoolSize（默认 50）
            发布订阅连接池。Redisson 的分布式锁等待正是靠 pub/sub 实现的。
            注意：加了 Caffeine 本地缓存后，缓存未命中时只会放 1 个线程进入方法体
            （@Cacheable 的 sync=true），其余线程阻塞在 Caffeine 的 future 上而不是抢锁，
            所以会用到订阅连接的不再是"199 个线程"，而是 1 个。
            实测锁等待并未拖慢其它 Redis 操作，故暂不调整；
            若将来锁竞争更激烈，这里是第一个要看的参数。

        nettyThreads（默认 32）/ threads（默认 16）
            所有 Redis I/O 与异步回调的执行线程。当前负载下未观察到瓶颈。
         */

        return Redisson.create(config);
    }

}
