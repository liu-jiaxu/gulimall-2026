# gulimall-ware：RabbitMQ 库存解锁流程

这份文档只讲 ware 里和 RabbitMQ 相关的代码：每个类干什么、方法怎么执行、库存字段怎么变。

## 1. 先理解要解决的问题

下单时用户还没付款，不能直接把库存扣掉，否则用户不付钱库存就白少了。所以拆成两步：

- **锁库存**：`stock` 不动，`stock_locked` 加上去。可用库存 = `stock - stock_locked` 变小。
- **解锁或扣减**：
  - 30 分钟没支付 → 把 `stock_locked` 还回去（解锁）；
  - 支付成功 → `stock` 和 `stock_locked` 同时减（锁定转真实扣减）。

MQ 的作用就是**延迟 30 分钟通知 ware 来解锁**。因为 MQ 可能重复投递、也可能丢消息，所以又加了本地消息表、重试、死信和定时兜底。

## 2. 涉及的文件

| 文件 | 作用 |
|---|---|
| `pom.xml` | 引入 `gulimall-rabbitmq` 共通模块 |
| `config/common.yaml` / `config/gulimall-ware.yaml` | `delay-ms`（common）；`grace-ms`、`trusted-packages`、手动 ack、prefetch（ware） |
| `WareSkuController` | `/ware/waresku/lockStock`、`/ware/waresku/markPaid` |
| `WareSkuServiceImpl` | 核心业务：锁库存、解锁、支付扣减 |
| `WareInfoServiceImpl` / `WareInfoDao.xml` | 运费页选仓：查候选仓、调百度距离、选最优仓 |
| `StockReleaseExecutor` | 解锁前加库存读锁 |
| `StockReleaseListener` | 消费库存解锁延时消息 |
| `StockReleaseTask` | 定时兜底解锁 |
| `StockReleaseMessage` | 消息体，只有 `taskId` 和 `orderSn` |
| `gulimall-api` 的 `StockLockVo` | order 调 ware 锁库存的入参（含选中的仓库） |
| `gulimall-api` 的 `SkuShippingFromVo` | 候选仓：sku + 仓库 + 地址 + 可用库存 |
| `sql/migrate-gulimall-wms-rabbitmq-tables.sql` | `mq_message` / `mq_dead_message` 建表 |

## 3. 先看懂三组状态

### 3.1 `wms_ware_sku`

- `stock`：仓库里实际有多少件；
- `stock_locked`：已经被未支付订单锁住多少件；
- 可用库存 = `stock - stock_locked`，Redis 里缓存的就是这个值。

### 3.2 `wms_ware_order_task` / `wms_ware_order_task_detail`

一个订单对应一条工作单主记录，状态 `task_status`：

| 值 | 含义 |
|---|---|
| 1 | 已锁定 |
| 2 | 已解锁 |
| 3 | 已扣减（支付成功） |

明细表一行代表「某个仓库的某个 sku 锁了多少件」，`lock_status` 同样是 1/2/3。

为什么要明细表：一个 sku 可能在多个仓库都有库存，锁的时候可能从 A 仓锁 2 件、B 仓锁 1 件。解锁和扣减时必须知道**每个仓库各还多少**，不能只记一个总数。

### 3.3 `mq_message`

本地消息表，状态 `message_status`：

| 值 | 含义 |
|---|---|
| 0 | 新建，等 Broker 确认 |
| 1 | 已送信 |
| 2 | 送信失败，等补偿 |

`delay_millis` 保存这条消息的延时时间（库存解锁是 30 分钟），补偿重发时会保留它。

## 4. 主流程（按时间顺序）

1. order 调用 `POST /ware/waresku/lockStock`，传 `StockLockVo`：订单号 + 每个 sku 要锁几件 + 运费页选中的仓库。
2. `WareSkuServiceImpl.lockStock` 在一个本地事务里：
   - 写工作单主记录（`task_status=1`）；
   - 只锁运费页选中的仓；缺少选仓信息直接报错；`stock_locked + n`，同时写明细（`lock_status=1`）；
   - 调 `sendReleaseMessage`，把「30 分钟后解锁」这件事写进 `mq_message`。
3. 事务提交后，共通模块把消息投到 `gulimall-rabbitmq-retry-exchange`，带 `x-delay=30 分钟`。
4. 30 分钟后消息进入 `gulimall-stock-release-queue`。
5. `StockReleaseListener` 收到消息，调 `StockReleaseExecutor.release`，最终执行 `releaseStock`：
   - 工作单 `1 → 2`；
   - 每个明细的 `stock_locked - n`，明细 `1 → 2`；
   - 事务提交后 Redis `INCRBY n`。
6. 成功就 `basicAck`；失败交给共通模块重试 10s/30s/60s，3 次后进死信表。
7. `StockReleaseTask` 每分钟再扫一次「还是已锁定、且已经超过 30 分钟 + 5 分钟」的工作单兜底。
8. 支付成功时 order 调 `POST /ware/waresku/markPaid?orderSn=xxx`，执行 `deductLockedStock`：工作单 `1 → 3`，`stock` 和 `stock_locked` 同时减。

## 5. 重点：`WareSkuServiceImpl` 的方法逐个解释

### 5.1 `lockStock`：下单锁库存

```java
@Transactional(rollbackFor = Exception.class)
public Long lockStock(Long orderId, String orderSn,
                      Map<Long, Integer> skuQuantities,
                      Map<Long, Long> skuWareMap)
```

它做四件事：

1. **幂等检查**：先按 `order_sn` 查有没有工作单。
   - 有且状态是 1：说明这个订单已经锁过，直接返回原来的工作单 id；
   - 有但状态是 2/3：说明已经解锁或扣减，不能重复锁，抛异常。
   - 数据库上还有 `uk_order_sn` 唯一索引，防止并发重复请求锁两次（迁移脚本：`sql/migrate-ware-order-task-uk-order-sn.sql`）。
2. **写工作单主记录**：`wms_ware_order_task`，`task_status=1`，并拿到自增 id。
3. **逐个 sku 锁库存**：
   - 必须带 `skuWareMap`，只查 `(sku_id, ware_id)` 这一行，够就锁，不够直接失败，不再遍历其他仓；
   - 执行 `stock_locked = stock_locked + take`，WHERE 里再带一次 `stock - stock_locked >= take`；
   - 影响行数为 0 说明并发下库存已被别人抢走，抛异常，整个事务回滚；
   - 每锁一笔写一条明细：`task_id / sku_id / ware_id / sku_num / lock_status=1`。
4. **登记延时消息**：调 `sendReleaseMessage(taskId, orderSn)`。

因为方法带 `@Transactional`，第 2、3、4 步里的数据库写入要么一起成功，要么一起回滚。不会出现「库存锁了但没写工作单」或「工作单写了但库存没锁」。

### 5.2 `sendReleaseMessage`：登记「30 分钟后解锁」的消息

```java
mqMessageService.sendReliable(
        MqConstants.RETRY_EXCHANGE,
        MqConstants.STOCK_RELEASE_ROUTING_KEY,
        message,
        delayMillis);
```

注意：**这个方法不是立刻把消息发到 RabbitMQ**。

- `sendReliable` 会先把消息写进 `mq_message`（status=0，delay_millis=30 分钟）；
- 这条 `mq_message` 记录和 `lockStock` 的工作单在**同一个本地事务**里；
- 事务提交之后，共通模块才真正把消息投到延时交换机；
- 如果事务回滚，消息记录一起回滚，不会出现「订单没下成功却发了消息」；
- 如果消息投递失败，共通模块的补偿任务会重发；
- 如果补偿也失败，还有 `StockReleaseTask` 定时扫工作单兜底。

消息体只有 `taskId` 和 `orderSn`，不带商品明细，因为明细在 ware 自己的数据库里，消费时按 `taskId` 查即可。

### 5.3 `releaseStock`：真正把锁定的库存还回去

```java
@Transactional(rollbackFor = Exception.class)
public void releaseStock(Long taskId)
```

它做三件事：

1. **抢占工作单**：

```sql
UPDATE wms_ware_order_task
SET task_status = 2
WHERE id = ? AND task_status = 1
```

- 影响行数为 1：说明这次是我抢到的，继续解锁；
- 影响行数为 0：说明工作单已经是 2（已解锁）或 3（已扣减），别的线程/支付流程已经处理过，直接返回。

这一步是幂等的关键：MQ 重复投递、定时任务和 MQ 同时处理，最终只有一次会真正解锁。

2. **按明细解锁**：查 `lock_status=1` 的明细，逐条执行：

```sql
UPDATE wms_ware_sku
SET stock_locked = stock_locked - n
WHERE ware_id = ? AND sku_id = ? AND stock_locked >= n
```

- 只减 `stock_locked`，`stock` 不能动，因为东西还在仓库里；
- 明细更新成 `lock_status=2`；
- 如果 `stock_locked` 不够减，抛异常让事务回滚，交给 MQ 重试或定时任务再处理。

3. **事务提交后回补 Redis**：

```java
stringRedisTemplate.opsForValue()
        .increment(WareConstant.STOCK_CACHE_PREFIX + skuId, num);
```

- 必须等 DB 事务提交后再加 Redis，否则 DB 回滚了 Redis 却加回去；
- 用 `INCRBY` 而不是「查库后 SET」：并发下单时查库可能读到旧值，SET 会把别人刚预扣的数量覆盖掉，造成超卖。

### 5.4 `deductLockedStock`：支付成功，锁定转真实扣减

```java
@Transactional(rollbackFor = Exception.class)
public void deductLockedStock(String orderSn)
```

1. 按 `orderSn` 查工作单；
2. 如果已经是 3（已扣减），直接返回，支付回调重复执行也不会重复扣；
3. 抢占工作单：`1 → 3`；如果影响行数 0，说明已经变成 2（已解锁），抛异常，不能扣；
4. 按明细执行：

```sql
UPDATE wms_ware_sku
SET stock = stock - n,
    stock_locked = stock_locked - n
WHERE ware_id = ? AND sku_id = ? AND stock >= n AND stock_locked >= n
```

- `stock` 和 `stock_locked` 同时减，可用库存 `stock - stock_locked` 不变；
- 明细 `lock_status` 改成 3；
- Redis 不再动：order 下单时已经预扣过 Redis，这里再减就重复了。

### 5.5 `getFare`：运费页怎么选仓

1. `WareInfoDao.getShippingFromList` 查每个 sku 的所有候选仓，带 `wareId` 和可用库存；
2. 只保留可用库存 >= 该 sku 购买数量的仓；
3. 所有候选仓一起发给第三方距离接口，一个 sku 会拿到多条距离；
4. 按 sku 分组选一条：
   - 有耗时的优先比耗时，取最短；
   - 没有耗时按距离，取最短；
   - 再相同按地址、`wareId` 兜底，保证结果稳定。
5. `FareItem.wareId` 返回给页面；提交时放进 `skuWareMap`，order 带到 `lockStock`，ware 只锁这个仓。
6. order 提交前会调 `hasStockByWare` 按指定仓校验库存，仓库不够直接报错。

## 6. 消费端和兜底分别干什么

### `StockReleaseListener`

- `@RabbitListener(queues = gulimall-stock-release-queue)` 收消息；
- 把 JSON 解析成 `StockReleaseMessage`；
- 调 `StockReleaseExecutor.release(taskId)`；
- 成功 `channel.basicAck`；
- 失败调 `mqRetryService.handleFailure`，交给共通模块重试/死信。

### `StockReleaseExecutor`

- 解锁前先拿库存**读锁**（和下单同一把）；
- 再调 `releaseStock`；
- 为什么加锁：每日对账任务拿的是库存写锁，会拿 DB 可用库存覆盖 Redis。解锁如果不加锁，可能在对账读取 DB 之后、写 Redis 之前插进去 `INCRBY`，把 Redis 加错；
- 读锁之间可以并发，不影响正常下单。

### `StockReleaseTask`

- 每分钟跑一次；
- 找 `task_status=1` 且 `create_time <= now - 30 分钟 - 5 分钟` 的工作单；
- 调 `StockReleaseExecutor.release` 解锁；
- 多实例同时扫也没关系，`releaseStock` 的条件更新保证只解锁一次。

## 7. 库存数字变化示例

初始：`stock=100`，`stock_locked=0`，Redis 可用库存 `100`。

| 阶段 | stock | stock_locked | 可用库存 | Redis |
|---|---:|---:|---:|---:|
| 初始 | 100 | 0 | 100 | 100 |
| 下单锁 2 件 | 100 | 2 | 98 | 98（order 预扣） |
| 支付成功 | 98 | 0 | 98 | 98（不再动） |
| 或超时解锁 | 100 | 0 | 100 | 100（INCRBY 2） |

## 8. 测试时怎么调

1. 在 `gulimall_wms` 执行两个迁移脚本：`sql/migrate-ware-order-task-uk-order-sn.sql`、`sql/migrate-gulimall-wms-rabbitmq-tables.sql`；
2. 把 `config/common.yaml` 的 `delay-ms` 和 `config/gulimall-ware.yaml` 的 `grace-ms` 临时改成 `60000`；
3. 下一单不支付，检查：
   - 运费接口返回的 `fares[i].wareId` 有值，提交表单里的 `skuWareMap` 也有值
   - `wms_ware_order_task.task_status=1`
   - `wms_ware_sku.stock_locked` 增加
   - `wms_ware_order_task_detail.ware_id` 就是运费页选中的仓
   - `mq_message` 有一条 `delay_millis=60000` 的记录
4. 等 1 分钟，检查工作单变成 2、`stock_locked` 减回去、Redis 加回来；
5. 再下一单并调用 `/ware/waresku/markPaid?orderSn=xxx`，检查工作单变成 3、`stock` 和 `stock_locked` 同时减。
