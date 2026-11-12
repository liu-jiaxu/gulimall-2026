# gulimall-seckill 模块说明

> 谷粒商城秒杀模块。独立微服务，端口 `14000`，负责秒杀场次/商品展示、抢购预扣、异步建单、取消/超时/支付回补、上架和对账。
>
> 技术栈：JDK 21 + Spring Boot 3.5.16 + Spring Cloud 2025.0.3 + Redis/Redisson + RabbitMQ + Sentinel + Micrometer Tracing/OTLP。

---

## 一、模块定位

`gulimall-seckill` 是秒杀链路的入口和协调者：

1. 从 `gulimall_sms` 读取场次、关联商品和秒杀单；
2. 把秒杀商品信息写到 Redis Hash，把库存写到 Redis String；
3. 抢购时用 Lua 原子完成库存、限购、防重和预扣标识；
4. 预扣成功后落 `sms_seckill_order`，通过 RabbitMQ 交给 `gulimall-order` 异步建单；
5. 调 `gulimall-ware` 完成活动库存到订单锁定的转换、释放和结算；
6. 提供取消、超时、支付、对账和后台管理所需的内部接口。

秒杀模块不直接维护 `oms_order`、`wms_ware_sku`、优惠券和后台菜单；这些数据分别由 order、ware、coupon 模块负责。

---

## 二、功能清单

### 2.1 用户端秒杀

- 秒杀页：展示可展示场次、场次商品、倒计时和登录状态。
- 商品详情秒杀模式：价格、剩余库存、每人限购、当前会员已购数量。
- 确认页：只使用默认收货地址；按限购和库存计算数量上限。
- 抢购提交：登录校验、Sentinel、参数校验、随机码校验、Lua 预扣、落预扣单、写可靠消息。
- 结果轮询：按 `orderSn` 或 `requestId` 查询自己的秒杀单状态。

### 2.2 异步建单与库存

- 建单消费者：读取 `seckill.order.create` 消息，调 order 内部接口建单。
- 建单成功：秒杀单 `0→1`，回填 `order_id` 和金额。
- 建单失败：秒杀单 `0→3`，回补 Redis 库存和限购计数。
- ware 锁库存：活动锁定的 `seckill_stock_locked` 转成订单锁定的 `stock_locked`。

### 2.3 取消、超时与支付

- 用户取消：order 关单后调 seckill 内部接口，场次进行中回秒杀池，否则回普通池。
- 订单超时：`OrderTimeoutTask` 关单后走同一释放链。
- 支付成功：order 支付回调后调 seckill 内部接口，秒杀单 `1→4`。
- 释放幂等：`seckill:release:{orderSn}` 保留 7 天，延迟消息重复回补不会多加库存。

### 2.4 后台管理

- 场次管理：新增、修改、下架、取消、删除；新增/修改会发开始/结束延时消息。
- 关联商品管理：新增、修改数量/商品、删除；调 ware 锁/放库存，变更后刷新商品 Hash。
- 秒杀活动管理：CRUD；有未结束场次时禁止修改/删除。
- 通知订阅：只有后台 CRUD，没有订阅、发送、短信/邮件逻辑。

### 2.5 上架、对账与兜底

- 每天 3 点或手动上架未来 3 天 + 进行中场次商品。
- 建单补偿：待建单超时重发消息，超过上限标记失败并回补。
- 已建单对账：订单已关闭补释放，已支付补 `PAID`。
- 限购对账：按秒杀单事实重建 Redis 每人已购 Hash。
- 结算兜底：停止中场次过了宽限期后由 coupon 的定时任务补结算。

### 2.6 稳定性

- Sentinel：Web URL 流控、抢购方法流控/熔断、商品信息热点参数、Feign fallback。
- 可靠消息：本地消息表 + publisher confirm/return + 定时补偿 + 消费端延迟重试 + 死信归档。
- 链路追踪：Micrometer Tracing + OTLP 导出到 Jaeger，Feign 传递 `traceparent`。
- 缓存降级：商品 Hash 缺失时回源数据库 + product；库存 key 缺失按 0 展示；product 不可用时保留旧 Hash。

---

## 三、全链路流程索引

| 编号 | 流程 | 摘要 | 文档 |
|---|---|---|---|
| 01 | 打开秒杀页 | 查可展示场次、从 Redis Hash 读商品，Hash 缺失时回源并回填 | [01-打开秒杀页](docs/秒杀业务流程说明/01-打开秒杀页.md) |
| 02 | 秒杀商品详情 | 商品详情页秒杀模式，返回价格、库存、限购和已购数量 | [02-秒杀商品详情](docs/秒杀业务流程说明/02-秒杀商品详情.md) |
| 03 | 秒杀确认页 | 校验登录、读取默认地址、按限购和库存计算数量上限 | [03-秒杀确认页](docs/秒杀业务流程说明/03-秒杀确认页.md) |
| 04 | 提交抢购订单 | 登录/Sentinel/参数/随机码校验后，Lua 原子预扣并写预扣单和可靠消息 | [04-提交抢购订单](docs/秒杀业务流程说明/04-提交抢购订单.md) |
| 05 | 抢购结果轮询 | 按订单号或请求 id 查询自己的秒杀单状态和跳转信息 | [05-抢购结果轮询](docs/秒杀业务流程说明/05-抢购结果轮询.md) |
| 06 | 异步建单 | 消费者调 order 建单，ware 把活动锁定转成订单锁定 | [06-异步建单](docs/秒杀业务流程说明/06-异步建单.md) |
| 07 | 用户取消订单回补 | order 关单后通知 seckill，按场次状态决定回秒杀池还是普通池 | [07-用户取消订单回补](docs/秒杀业务流程说明/07-用户取消订单回补.md) |
| 08 | 订单超时关闭回补 | `OrderTimeoutTask` 关闭超时订单，再走与取消相同的释放链 | [08-订单超时关闭回补](docs/秒杀业务流程说明/08-订单超时关闭回补.md) |
| 09 | 支付成功回填 | 支付宝 notify 推进 order 已支付，再回填秒杀单 `PAID` | [09-支付成功回填](docs/秒杀业务流程说明/09-支付成功回填.md) |
| 10 | 后台场次管理 | 场次新增/修改/下架/取消/删除，发送开始、结束、结算消息 | [10-后台场次管理](docs/秒杀业务流程说明/10-后台场次管理.md) |
| 11 | 后台关联商品管理 | 关联商品增删改，调 ware 锁/放库存并刷新 Redis Hash | [11-后台关联商品管理](docs/秒杀业务流程说明/11-后台关联商品管理.md) |
| 12 | 场次到点开始 | 开始延时消息把场次从“未开始”推进到“进行中” | [12-场次到点开始](docs/秒杀业务流程说明/12-场次到点开始.md) |
| 13 | 场次到点结束与结算 | 结束消息进入“停止中”，宽限期后释放剩余锁定并结算 | [13-场次到点结束与结算](docs/秒杀业务流程说明/13-场次到点结束与结算.md) |
| 14 | 秒杀商品上架 | 每天 3 点或手动把场次商品写入 Redis，库存 key 只补不覆盖 | [14-秒杀商品上架](docs/秒杀业务流程说明/14-秒杀商品上架.md) |
| 15 | 秒杀对账任务 | 建单补偿、已建单对账、限购重建、库存只读对账、结算兜底 | [15-秒杀对账任务](docs/秒杀业务流程说明/15-秒杀对账任务.md) |
| 16 | 秒杀活动管理 | 活动 CRUD；有未结束场次时禁止修改/删除 | [16-秒杀活动管理](docs/秒杀业务流程说明/16-秒杀活动管理.md) |
| 17 | Sentinel 限流熔断 | 网关/方法/热点参数规则、熔断、统一错误码和 Feign fallback | [17-Sentinel限流熔断](docs/秒杀业务流程说明/17-Sentinel限流熔断.md) |
| 18 | 监控与链路追踪 | 网关 → seckill → product 的 span、OTLP 导出和 Jaeger 查看 | [18-监控与链路追踪](docs/秒杀业务流程说明/18-监控与链路追踪.md) |
| 19 | MQ 重试死信归档重放 | 发送确认、定时补偿、消费延迟重试、死信归档和人工重放 | [19-MQ重试死信归档重放](docs/秒杀业务流程说明/19-MQ重试死信归档重放.md) |
| 20 | 缓存回源与降级 | 商品 Hash、库存 key、限购 key 的生命周期、回源和降级 | [20-缓存回源与降级](docs/秒杀业务流程说明/20-缓存回源与降级.md) |
| 21 | 秒杀商品通知订阅 | 后台 CRUD；当前没有订阅/发送/短信/邮件逻辑 | [21-秒杀商品通知订阅](docs/秒杀业务流程说明/21-秒杀商品通知订阅.md) |

---

## 四、接口清单

### 4.1 seckill 服务

| 方法 | 路径 | 说明 | 入口方法 |
|---|---|---|---|
| GET | `/`、`/seckill.html` | 秒杀页 | `SeckillPageController#seckillPage` |
| GET | `/confirm.html` | 秒杀确认页 | `SeckillPageController#confirmPage` |
| GET | `/seckill/sessions` | 可展示场次 | `SeckillController#sessions` |
| GET | `/seckill/skus?sessionId=` | 场次商品 | `SeckillController#skus` |
| GET | `/seckill/home` | 首页秒杀区 | `SeckillController#home` |
| GET | `/seckill/sku/info?sessionId=&skuId=` | 商品详情秒杀信息 | `SeckillController#skuInfo` |
| POST | `/seckill/upload` | 手动上架 | `SeckillController#upload` |
| POST | `/seckill/refresh?sessionId=&skuId=` | 刷新单个商品 Hash | `SeckillController#refresh` |
| POST | `/seckill/order/submit` | 提交抢购 | `SeckillOrderController#submit` |
| GET | `/seckill/order/result` | 抢购结果 | `SeckillOrderController#result` |
| POST | `/internal/seckill/order/release` | 取消/超时释放 | `SeckillOrderInternalController#release` |
| POST | `/internal/seckill/order/paid` | 支付成功回填 | `SeckillOrderInternalController#paid` |

### 4.2 coupon 服务

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/coupon/seckillsession/list`、`info/{id}`、`save`、`update`、`offline`、`cancel`、`delete` | 场次管理 |
| POST | `/coupon/seckillskurelation/list`、`session/{sessionId}`、`all`、`info/{id}`、`save`、`update`、`delete` | 关联商品管理 |
| POST | `/coupon/seckillpromotion/list`、`info/{id}`、`save`、`update`、`delete` | 秒杀活动管理 |
| POST | `/coupon/seckillskunotice/list`、`info/{id}`、`save`、`update`、`delete` | 通知订阅 CRUD |

### 4.3 order / ware / product 接口

| 模块 | 方法 | 路径 | 说明 |
|---|---|---|---|
| order | POST | `/internal/order/seckill/create` | seckill 调 order 建单 |
| order | GET | `/internal/order/seckill/status` | 秒杀单对账查询 |
| order | POST | `/internal/order/seckill/status/batch` | 批量查订单状态 |
| order | POST | `/pay/cancel` | 用户取消订单 |
| order | POST | `/pay/notify` | 支付宝异步通知 |
| order | GET | `/pay/return` | 支付宝同步返回 |
| ware | POST | `/ware/waresku/seckill/lock` | 关联商品锁活动库存 |
| ware | POST | `/ware/waresku/seckill/release` | 关联商品释放活动库存 |
| ware | POST | `/ware/waresku/seckill/refresh` | 刷新普通库存缓存 |
| ware | POST | `/ware/waresku/seckill/order/lock` | 活动锁定转订单锁定 |
| ware | POST | `/ware/waresku/seckill/order/release` | 取消/超时释放订单锁定 |
| ware | POST | `/ware/waresku/seckill/releaseRemaining` | 场次结算释放剩余活动锁定 |
| product | GET | `/product/skuinfo/info/{skuId}` | 查 SKU 名称、价格、图片 |

---

## 五、存储设计

### 5.1 MySQL

| 库 | 表 | 用途 |
|---|---|---|
| `gulimall_sms` | `sms_seckill_promotion` | 秒杀活动 |
| `gulimall_sms` | `sms_seckill_session` | 场次、状态机、停止/结算时间 |
| `gulimall_sms` | `sms_seckill_sku_relation` | 场次与 SKU 关联、`ware_id`、数量、限购、版本 |
| `gulimall_sms` | `sms_seckill_order` | 秒杀预扣单、建单/释放/支付状态、全链路幂等键 |
| `gulimall_sms` | `sms_seckill_sku_notice` | 通知订阅（只有 CRUD） |
| `gulimall_sms` | `mq_message` / `mq_dead_message` | seckill/coupon 本地消息与死信归档 |
| `gulimall_wms` | `wms_ware_sku` | `stock`、`stock_locked`、`seckill_stock_locked` |
| `gulimall_wms` | `wms_ware_order_task` / `_detail` | 库存工作单/明细，秒杀单 `biz_type=1` |
| `gulimall_wms` | `wms_seckill_stock_operation` | 秒杀锁/释放幂等记录 |
| `gulimall_wms` | `mq_message` / `mq_dead_message` | ware 侧本地消息与死信归档 |
| `gulimall_oms` | `oms_order` | 订单，秒杀单 `source_type=2` |
| `gulimall_oms` | `oms_order_item`、`oms_payment_info`、`oms_refund_info` | 订单明细、支付、退款 |

### 5.2 Redis

| key | 类型 | TTL | 用途 |
|---|---|---|---|
| `seckill:session:{sessionId}:skus` | Hash | 场次结束 + 1 天 | 场次商品信息，field=skuId，value=`SeckillSkuVo` JSON |
| `seckill:stock:{sessionId}:{skuId}` | String | 场次结束 + 1 天 | 秒杀剩余库存；只在关联锁库存/上架补 key 时创建 |
| `seckill:user:count:{sessionId}:{skuId}` | Hash | 与库存 key 一致 | 每人已购数量，field=memberId |
| `seckill:req:{sessionId}:{skuId}:{memberId}` | String | 10 秒 | 抢购防重，value=`orderSn` |
| `seckill:reserve:{orderSn}` | String | 30 分钟 | 预扣标识，value=`memberId:qty` |
| `seckill:release:{orderSn}` | String | 7 天 | 释放幂等标识 |
| `seckill:settle:lock:{sessionId}` | Redisson 锁 | 运行期 | 场次停止/结算/取消/关联修改共用 |

### 5.3 RabbitMQ

| 交换机 | 队列 | routingKey | 用途 |
|---|---|---|---|
| `gulimall-seckill-order-exchange` | `gulimall-seckill-order-create-queue` | `seckill.order.create` | 抢购预扣后异步建单 |
| `gulimall-seckill-start-exchange` | `gulimall-seckill-start-queue` | `seckill.start` | 场次到点开始 |
| `gulimall-seckill-end-exchange` | `gulimall-seckill-end-queue` | `seckill.end` | 场次到点结束/进入停止中 |
| `gulimall-seckill-exchange` | `gulimall-seckill-settle-queue` | `seckill.settle` | 宽限期后结算 |
| `gulimall-stock-exchange` | `gulimall-stock-release-queue` | `stock.release` | 普通订单库存解锁延时消息 |
| `gulimall-rabbitmq-retry-exchange` | 按原 routingKey 回原队列 | 原 routingKey | 消费失败 10s/30s/60s 重试 |
| `gulimall-rabbitmq-dead-exchange` | `gulimall-rabbitmq-dead-queue` | `gulimall.rabbitmq.dead` | 重试用尽/归档 |

### 5.4 定时任务

| 模块 | 任务 | cron | 作用 |
|---|---|---|---|
| seckill | `SeckillSkuScheduled#uploadSeckillSku` | `0 0 3 * * ?` | 每天 3 点上架未来 3 天 + 进行中场次商品 |
| seckill | `SeckillOrderReconcileTask#reconcile` | `15 * * * * ?` | 建单补偿、已建单对账 |
| seckill | `SeckillLimitReconcileTask#reconcile` | `30 * * * * ?` | 按订单重建限购 Hash |
| coupon | `SeckillSettleScheduled` | `0 0 3 * * ?` | 兜底结算已过宽限期的停止中场次 |
| coupon | `SeckillStockReconcileTask` | `0 0 3 * * ?` | 只读对账 DB/Redis 库存 |
| order | `OrderTimeoutTask#closeExpiredOrders` | `0 * * * * ?` | 关闭未支付超时订单 |
| order | `RefundRetryTask` | `0 * * * * ?` | 退款重试 |
| ware | `StockReleaseTask` | `0 * * * * ?` | 兜底释放普通订单库存工作单 |
| ware | `StockReconcileTask` | `0 * 2-4 * * ?` | 凌晨 2～4 点库存只读对账 |
| rabbitmq | `MqMessageCompensateTask` | `0 * * * * ?` | 补偿本地消息表 |

---

## 六、配置文件与资源

| 文件 | 作用 |
|---|---|
| `gulimall-seckill/pom.xml` | 依赖：web、thymeleaf、redis、openfeign、rabbitmq、session、redisson、sentinel、actuator、tracing |
| `src/main/resources/application.yaml` | 应用名、Nacos + 本地 `config/` 配置导入顺序 |
| `config/gulimall-seckill.yaml` | 端口 14000、数据源、Redis、Session、RabbitMQ 手动 ack、Sentinel 数据源、秒杀超时、Tracing |
| `config/common.yaml` | MySQL、Redis、RabbitMQ、Nacos、Sentinel、Jaeger、支付宝、Session、Feign 超时等公共配置 |
| `src/main/resources/templates/seckill.html` | 秒杀页 |
| `src/main/resources/templates/seckill-confirm.html` | 确认页 |
| `src/main/java/com/atguigu/gulimall/seckill/config/SeckillWebConfiguration.java` | 登录拦截器与放行路径 |
| `src/main/java/com/atguigu/gulimall/seckill/config/SessionConfig.java` | 跨子域共享 Session 的 Redis 序列化 |
| `src/test/resources/application-test.yaml` | 集成测试配置：不注册 Nacos、默认不启动 MQ 监听 |
| `src/test/java/com/atguigu/gulimall/seckill/support/SeckillIntegrationTestSupport.java` | 真实环境测试基类 |
| `src/test/README.md` | 集成测试运行前提、测试类与数据清理说明 |
| `docs/秒杀业务流程说明/` | 21 个业务流程文档 |

**配置要点**

- 秒杀场次、关联和秒杀单在 `gulimall_sms`，不是 `gulimall_oms`。
- `gulimall.seckill.order.pay-timeout-ms` 与订单支付超时保持一致，默认 30 分钟。
- `gulimall.ware.stock-release.delay-ms` 同时作为订单超时、秒杀结算宽限期。
- RabbitMQ 消费端手动 ack，失败交给 `MqRetryService`。
- Sentinel 规则从 Nacos 读取，Dashboard 只做展示，不写回。
- Session cookie 名 `GULIMALLSESSION`，domain `gulimall.vip`，与商城前台共享。

---

## 七、集成测试

测试目录按流程编号组织，一个流程一个测试类，使用真实 MySQL、Redis、RabbitMQ、Nacos 和其它微服务，不使用 Mock 替换业务 Bean。

运行前：

1. 启动 MySQL、Redis、RabbitMQ、Nacos、Sentinel、Jaeger；
2. 运行跨模块流程时启动 `gulimall-product`、`gulimall-member`、`gulimall-order`、`gulimall-ware`、`gulimall-coupon`；
3. 停止本机 `gulimall-seckill`，避免测试实例和正式实例抢 MQ 消息与定时任务。

运行全部流程测试：

```text
mvn -o -pl gulimall-seckill -am test -Dsurefire.excludedGroups= -Dtest="Flow*Test" -Dsurefire.failIfNoSpecifiedTests=false
```

只运行某个流程：

```text
mvn -o -pl gulimall-seckill -am test -Dsurefire.excludedGroups= -Dtest=Flow04SubmitSeckillOrderTest -Dsurefire.failIfNoSpecifiedTests=false
```

测试使用固定会员 `990001`、SKU `990001`、仓库 `990001`，结束后清理 `gulimall_sms`、`gulimall_oms`、`gulimall_wms`、`gulimall_ums`、`gulimall_pms` 中的测试数据和 Redis key。跨模块服务未启动时，测试通过 `Assumptions` 跳过，不伪造结果。

Flow19 会把 MQ 重试间隔从 10s/30s/60s 临时改成 1s/3s/6s，测试结束恢复。

---

## 八、已知限制

1. `requestId` 幂等只按 `request_id` 查询，没有按会员隔离；拿到别人的 `requestId` 会返回对方订单信息。
2. 抢购提交只校验 `addrId` 非空，没有校验地址属于当前会员。
3. `releaseRemainingSeckillStock` 接收 `sessionId`，但 SQL 没有按场次过滤；同一 SKU 在不重叠场次都锁库存时，结束一场可能释放另一场的锁定。
4. `failAndRelease` 先改 DB 状态再回补 Redis，两步不在同一事务；Redis 回补异常时可能漏回补。
5. ware 找不到秒杀工作单时 `releaseSeckillOrderStock` 直接返回成功，seckill 侧仍可能回补 Redis，造成 Redis 库存大于 `seckill_stock_locked`。
6. `NORMAL_POOL` 只回普通池，不回补 `seckill:user:count`，依赖场次结算删 key。
7. 秒杀内部接口 `/internal/**` 被登录拦截器放行，Nginx 订单站点 `location /` 会转发所有路径，存在外部直接调用风险。
8. `saveSession` 强制 `status=1`；`updateSession` 保留旧 `status`，前端“启用状态”对新增/修改不生效。
9. 活动 `promotionId` 只持久化，不校验活动是否存在/启用；活动 `status/start_time/end_time` 不参与场次逻辑。
10. `/seckill/upload` 放行登录且 Nginx 直连 14000，任何可访问者都能触发全量上架。
11. Sentinel 规则均为 `clusterMode=false`，单实例限流；`/seckill.html`、`/seckill/order/result`、`/seckill/upload` 等没有规则。
12. 正常订单超时关单与库存延时解锁是两条独立时间线，存在“已支付但库存已回可售池”的窗口。
13. `pay.gulimall.vip` 的 Nginx server 块不在仓库配置中，支付宝 notify 公网可达性需要部署时确认。
14. 秒杀商品通知订阅只有代码生成器 CRUD，没有订阅/发送/短信/邮件/定时任务。
15. 支付宝异步通知需要支付宝私钥签名，仓库只有应用私钥和支付宝公钥；集成测试覆盖 seckill 的 `PAID` 回调，不驱动完整沙箱 notify。
