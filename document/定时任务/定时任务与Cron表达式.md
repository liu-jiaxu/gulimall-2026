# 定时任务与 Cron 表达式

> 环境：Spring Boot 3.5.16 / Spring Framework 6.2。
> 文中 `L`、`W`、`#`、日/周同时指定等写法，都用本机 `CronExpression` 实测过。
> 本文解决两件事：① 会写 `@Scheduled(cron = "...")`；② 知道要不要配线程池、集群下怎么防重复执行。

---

## 一、Cron 的 6 个字段

```
0       *       *       *       *       ?
秒      分      时      日      月      周（星期）
```

- 取值范围：秒/分 `0-59`，时 `0-23`，日 `1-31`，月 `1-12` 或 `JAN-DEC`，周 `0-7` 或 `SUN-SAT`（`0` 和 `7` 都表示周日）。
- Spring 的 `@Scheduled` **只支持 6 段**，多写第 7 段年份会直接解析失败。
- Linux `crontab` 是 **5 段，没有秒**，第一位是分钟。把 Linux 的写法直接贴进代码，各字段会整体错位。
- **日和周同时指定时是 AND，不是 OR**：`0 0 0 1 * MON` 表示“每月 1 号并且当天是周一”（实测下一次是 2027-02-01）。所以固定用 `?` 占掉其中一个字段。
- 时区默认取 JVM 默认时区。生产机常常是 UTC，会在时间上差 8 小时，建议显式指定：

```java
@Scheduled(cron = "0 0 2 * * ?", zone = "Asia/Shanghai")
```

---

## 二、常用写法速查

| 需求 | 表达式 | 说明 |
|---|---|---|
| 每分钟一次 | `0 * * * * ?` | 每分钟第 0 秒触发 |
| 每 5 分钟一次 | `0 */5 * * * ?` | `*/5` 表示每 5 个单位 |
| 每 30 秒一次 | `*/30 * * * * ?` | 第 0、30 秒 |
| 每 10 秒一次 | `*/10 * * * * ?` | 第 0、10、20…… 秒 |
| 每小时整点 | `0 0 * * * ?` | 分和秒都为 0 |
| 每天 0 点 | `0 0 0 * * ?` | |
| 每天凌晨 2 点 | `0 0 2 * * ?` | |
| 每天 2:30 | `0 30 2 * * ?` | |
| 每天 8 点和 20 点 | `0 0 8,20 * * ?` | 逗号列举 |
| 工作日每天 9 点 | `0 0 9 ? * MON-FRI` | 日字段用 `?`，周字段写范围 |
| 每周一 0 点 | `0 0 0 ? * MON` | |
| 每周日 0 点 | `0 0 0 ? * SUN` | `0` 和 `7` 也都是周日 |
| 每月 1 号 0 点 | `0 0 0 1 * ?` | |
| 每月最后一天 23 点 | `0 0 23 L * ?` | `L` = last |
| 每月第二个周一 | `0 0 0 ? * MON#2` | `#` = 第几个周几 |
| 每月最后一个周五 | `0 0 0 ? * 5L` | 周字段的 `L`，实测支持 |
| 每月 15 号最近的工作日 | `0 0 0 15W * ?` | `W` = weekday |
| 从第 5 分钟起每 10 分钟 | `0 5/10 * * * ?` | 第 5、15、25…… 分钟 |

> 提示：`0 0 9 ? * MON-FRI` 里的 `?` 也可以写成 `*`，结果一样（`*` 和 MON-FRI 做 AND 等于 MON-FRI）。
> 用 `?` 的写法更明确，不容易让人误以为还有其他约束。

---

## 三、特殊符号

| 符号 | 含义 | 示例 | 备注 |
|---|---|---|---|
| `*` | 任意值 | 分字段写 `*` = 每分钟 | |
| `?` | 不指定 | 配合另一个日/周字段使用 | 只用于「日」「周」 |
| `,` | 列举 | `8,20` = 8 点和 20 点 | |
| `-` | 范围 | `MON-FRI`、`9-17` | |
| `/` | 步长/间隔 | `*/5` = 每 5；`5/10` = 从 5 开始每 10 | 斜杠左边是起点 |
| `L` | 最后 | 日字段 `L` = 月末；周字段 `5L` = 最后一个周五 | 仅日/周字段 |
| `W` | 最近工作日 | `15W` = 离 15 号最近的工作日 | 仅日字段；落在周末会顺延 |
| `#` | 第几个周几 | `MON#2` = 第二个周一；`FRI#5` = 当月第 5 个周五 | 仅周字段 |

补充说明：

- `FRI#5` 只在“有第 5 个周五”的月份触发；想要“每个月最后一个周五”要用 `5L`。
- `L`、`W`、`#` 在 Spring 6 里都支持（实测）；Quartz 还有一个 `C`（日历），Spring 不支持。

---

## 四、容易踩的坑

1. **秒字段最容易漏**：`0 * * * * ?` 是每分钟一次；`* * * * * ?` 才是每秒一次。
2. **日和周不是二选一，而是 AND**：同时写容易得到“几乎不触发”的任务，统一用 `?` 占位。
3. **`*/5` 和 `5/10` 的区别**：斜杠左边是起点，不是偏移量。
4. **定时任务不是精确闹钟**：同一条任务上一轮没跑完时，下一轮不会并发执行（Spring 会等上一轮结束后再排下次），触发时间会顺延。
5. **`@Scheduled` 方法必须无参**，返回值会被忽略；所在类必须是 Spring 管理的 Bean。
6. **集群下每个实例都会跑**：`@Scheduled` 是纯本地的，详见第六、七节。

快速验证某个表达式下次触发时间（本地跑一段 jshell/单元测试即可）：

```java
CronExpression.parse("0 0 23 L * ?")
        .next(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")));
```

---

## 五、本项目现有的定时任务

| 模块 | 位置 | Cron | 作用 |
|---|---|---|---|
| order | `OrderTimeoutTask.java:57` | `0 * * * * ?` | 每分钟关闭超时未支付订单 |
| order | `RefundRetryTask.java:22` | `0 * * * * ?` | 每分钟重试失败的退款 |
| ware | `StockReleaseTask.java:50` | `0 * * * * ?` | 每分钟兜底解锁超时库存工作单 |
| ware | `StockReconcileTask.java:49` | `0 * 2-4 * * ?` | 凌晨 2:00～4:59 每分钟对一批账 |
| rabbitmq | `MqMessageCompensateTask.java:39` | `0 * * * * ?` | 每分钟补偿未确认的 MQ 消息（随 `gulimall-rabbitmq` 依赖进入 ware） |

注意：同一服务的多个任务都写在“每分钟第 0 秒”，触发时刻完全一样，会挤在同一个调度线程上排队。

---

## 六、定时任务需要配置线程池吗？—— 需要

### 默认行为

Spring Boot 3.5 在平台线程模式下，自动配置的是 `ThreadPoolTaskScheduler`，`spring.task.scheduling.pool.size` **默认值为 1**。也就是说：**一个服务里所有 `@Scheduled` 任务共用同一个线程**。

这不是正确性问题，但会造成“慢任务拖住快任务”：

- `gulimall-order`：`OrderTimeoutTask` 每关一单会同步调一次支付宝 `closeTrade`（HTTP 请求）。100 单如果超过 1 分钟，`RefundRetryTask` 就被推迟。
- `gulimall-ware`：`StockReconcileTask` 凌晨抢库存写锁最多阻塞 3 分钟，同一时段 `StockReleaseTask` 和 MQ 补偿任务最多晚 3 分钟才跑。

### 配置方式

放在 `config/common.yaml` 即可被所有服务引用（也可以按服务单独调）：

```yaml
spring:
  task:
    scheduling:
      pool:
        size: 4                        # 默认 1，本项目 4 足够
      thread-name-prefix: ${spring.application.name}-sched-
      shutdown:
        await-termination: true
        await-termination-period: 30s  # 停机时给正在跑的任务 30 秒收尾
```

### 几个要点

- 线程池只解决“**不同任务之间**互相阻塞”。**同一条任务不会并发**：Spring 在上一轮结束后才安排下一轮，所以 `pool.size` 调大不会让同一个任务重复跑。
- 不要盲目调大。任务并行后会同时打 DB / Redis / 支付宝，数量要和服务实际任务数匹配（本项目 2～3 个任务，`4` 足够）。
- 如果开了虚拟线程（`spring.threads.virtual.enabled=true`），Boot 会改用 `SimpleAsyncTaskScheduler`，`pool.size` 不生效，改用 `spring.task.scheduling.simple.concurrency-limit`。
- 如果你自己声明了 `TaskScheduler` Bean，yml 里的 `spring.task.scheduling.*` 就不再作用于它，二选一。

---

## 七、集群下每个实例都会执行，怎么办？

先明确结论：**是的**。`@Scheduled` 没有任何“选主”机制，`@EnableScheduling` 开在哪个实例上，哪个实例就会跑。3 个副本 = 同一任务执行 3 次。

处理分两层，推荐叠加使用。

### 7.1 第一层：幂等（必须做）

目标：即使重复执行，结果也只生效一次。常用手段：

- **条件更新**：`UPDATE ... SET status=4 WHERE id=? AND status=0`，只有一个实例能更新成功。本项目 `OrderTimeoutTask.java:75-78` 就是这么做的。
- **唯一约束 / 固定请求号**：`RefundRetryTask` 依赖支付宝退款使用固定的 `out_request_no`，重复调用不会重复退款。
- **业务状态机**：`StockReleaseTask` 依赖 `releaseStock` 内部的条件更新，保证只解锁一次。

优点：简单、不引入额外中间件。
缺点：每个实例都会扫一遍库，属于“正确但浪费”；调用第三方接口时，还要确认对方接口本身幂等。

### 7.2 第二层：分布式锁（重任务建议加）

目标：一轮只让一个实例执行，减少重复扫描和重复外部调用。本项目已有现成例子：`MqMessageCompensateTask.java:41-66`。

```java
RLock lock = redissonClient.getLock(lockKey);
// 抢不到就跳过本轮，不要阻塞调度线程
if (!lock.tryLock(0, TimeUnit.SECONDS)) {
    return;
}
try {
    // 执行任务
} finally {
    if (lock.isHeldByCurrentThread()) {
        lock.unlock();
    }
}
```

实现要点：

- 用 `tryLock(0, ...)` 非阻塞获取，抢不到直接返回，下一分钟再来。
- 不传 `leaseTime` 时 Redisson 看门狗会自动续期，任务跑得久也不会锁超时。
- `finally` 里先判断 `isHeldByCurrentThread()` 再解锁，避免解掉别人的锁。
- **锁 key 要带业务维度**。`MqMessageCompensateTask` 的 key 带了 `spring.application.name`，因为每个微服务有自己的 `mq_message` 表，共用一个 key 会互相跳过。
- **锁不能替代幂等**：锁过期、Redis 主从切换、Full GC 停顿时，都可能出现两个实例同时跑。锁只是减少重复，兜底仍靠 7.1。

另外注意：`StockReconcileTask` 里用的是“库存读写锁”，目的是让对账和下单互斥，不是防止多实例重复调度。两者目的不同，不要混为一谈。

### 7.3 更彻底的做法

| 方案 | 适用场景 | 说明 |
|---|---|---|
| 只让一个副本开启调度 | 小项目、临时方案 | 加 `@ConditionalOnProperty` 之类的开关，只在某一台配 `true`。滚动发布、配置漂移时容易翻车，不建议长期使用 |
| 独立调度服务（单副本） | 中等规模 | 把任务拆成单独服务，只部署 1 个副本。职责清晰，但多一个部署单元 |
| 调度中间件（XXL-JOB / PowerJob / ElasticJob / Quartz 集群） | 生产推荐 | cron 在控制台配置，支持单机/广播/分片路由、失败重试、执行日志和告警，天然避免重复执行 |

对本项目的建议：

- 当前学习/演示阶段：**幂等（已有）+ 重任务加 Redisson 锁** 就够了。
- 如果按生产标准做：接入 **XXL-JOB**，把 `OrderTimeoutTask`、`StockReleaseTask` 这类 cron 挪到调度平台管理，服务本身不再依赖 `@Scheduled`。

### 7.4 其他注意事项

- 服务器时间要做 NTP 同步，否则各节点触发时刻会漂移。
- 滚动发布瞬间新旧副本同时在线，会短暂“多实例”，靠幂等/锁兜住。
- **广播类任务**（例如每个实例都要刷新本地缓存）不要加分布式锁，应该用广播路由或 MQ fanout。

---

## 八、结论

1. **写法**：`秒 分 时 日 月 周`；日和周只写一个，另一个用 `?`；时区显式指定 `Asia/Shanghai`。
2. **线程池**：要配。`spring.task.scheduling.pool.size` 默认 1，所有任务共用一个线程；本项目在 `config/common.yaml` 配 `size: 4` 即可。
3. **集群**：`@Scheduled` 在每个实例上都会执行。幂等是底线，分布式锁减少重复，生产环境推荐接调度中间件。
