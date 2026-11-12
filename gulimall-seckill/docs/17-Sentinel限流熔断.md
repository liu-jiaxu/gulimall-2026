# 业务流程 17：Sentinel 限流熔断

## 一、设计思路

- 三层拦截：网关按路由、Web 层按 URL、方法按 `@SentinelResource` 资源名，越靠近入口拒绝成本越低。
- 规则持久化在 Nacos，客户端启动时拉取并监听变更；Dashboard 只做展示与临时调整，不写回 Nacos。
- 资源点放在 Controller 最外层，`blockHandler` 在业务执行前返回，被拦请求不进入 Redis 预扣、不落单、不发 MQ。
- 限流与熔断统一返回错误码 `14013`；网关返回 HTTP 429，seckill Web 与方法资源返回 HTTP 200 加统一 `R` JSON，调用方按 body `code` 判断。
- 热点参数按 `skuId` 单独限流，避免单个热门 SKU 挤占整条链路的配额。
- 熔断采用慢调用比例：统计窗口内请求数达标且慢调用比例超阈值时打开，冷却期后半开探测。
- Feign 降级分两类：可容忍的下游失败返回空数据或错误码并等待对账；建单与状态查询不设 fallback，避免把“服务不可用”误判为“订单不存在”。
- 规则阈值为单实例语义，多实例部署时总配额按实例数放大。

## 二、主要功能描述

**触发与前置**

- 触发来源：经网关 `/gulimall/seckill/**` 的请求、seckill Web 层请求、带 `@SentinelResource` 的方法调用。
- 前置条件：Nacos 的 `SENTINEL_GROUP` 下已存在网关、流控、熔断、热点参数四份规则。
- 网关与 seckill 能连接 Nacos 与 Sentinel Dashboard，客户端端口互不冲突。
- 抢购接口还需要登录态，未登录在进入方法资源前由登录拦截器返回 `14001`。

**做什么**

- 网关对路由 `seckill-route` 限制 100 QPS，超限返回 HTTP 429 与 `14013`。
- Web 层对 `/seckill/sessions`、`/seckill/skus` 各限制 200 QPS，超限返回 HTTP 200 与 `14013`。
- `seckillSkuInfo` 先过热点参数，同一 `skuId` 每秒 20 次，再过 200 QPS 流控。
- `seckillOrderSubmit` 先过 50 QPS 流控，再过慢调用熔断：RT 超过 800ms 且慢调用比例达 50%，熔断 10 秒。
- `seckillSkuInfo` 的非限流异常走 `fallback`，返回 `14009`。
- product 调用失败时返回空 `skuInfo`，调用方跳过该 SKU；order 调 seckill 失败时记录日志并等待对账。

**不做什么**

- 不做登录鉴权、库存校验、库存扣减与建单。
- 被限流的请求不读不写任何秒杀 Redis key，也不写业务表。
- 不做集群限流，全部规则为单实例阈值。
- 不为 `OrderFeignClient` 配置 fallback，异常交由 MQ 重试与对账处理。

**结果**

- 未触发规则：正常执行，返回 `code=0` 或业务错误码。
- 被限流或熔断：返回 `14013`，业务方法完全不执行，库存不变。
- 下游降级：商品页退化为普通商品页，或订单主流程不回滚、等待对账补偿。

**数据变化**

- Nacos 与客户端内存：启动及配置变更时读取规则，客户端内存保存规则、统计与熔断状态。
- 被限流或熔断的请求不读不写 `seckill:stock:*`、`seckill:user:count:*`、`seckill:session:*:skus`。
- 正常放行的抢购才执行 Lua 预扣、写 `sms_seckill_order`、发送建单消息。
- Dashboard 只展示机器与指标，规则变更不持久化，客户端重启后以 Nacos 为准。

**异常与边界**

- 网关限流返回 HTTP 429，seckill 内部限流返回 HTTP 200，调用方只能按 `code` 统一判断。
- 熔断是方法级全局开关，打开后所有用户的抢购请求都被 `14013` 拒绝。
- 同一 SKU 的热点限流会同时影响正常用户，超过 20 次/秒即被拒绝。
- Nacos 不可用或规则 JSON 错误时对应规则不生效，流量不受控。
- 客户端端口冲突会导致注册或规则推送异常，gateway、seckill、order 必须使用不同端口。
- product 降级返回成功但无 `skuInfo`，调用方只判断 `code=0` 会把降级当成功。

## 三、执行顺序

1. 客户端启动：seckill 从 Nacos 拉取 `flow`、`degrade`、`param-flow` 规则，网关拉取 `gw-flow`，并注册到 Dashboard。
2. 请求进入网关，Spring Cloud Gateway 匹配路由 `seckill-route`。
3. `SentinelGatewayConfig#initBlockHandler`：注册网关限流回调，统一返回 `R` JSON。
4. 网关 `gw-flow`：按路由 ID 检查 100 QPS，超限返回 HTTP 429 与 `14013`。
5. 未超限时执行 `StripPrefix=1`，转发到 `lb://gulimall-seckill`。
6. `LoginUserInterceptor`：校验登录态，未登录直接返回 `14001`。
7. `SentinelWebBlockHandler#handle`：URL 资源超过 200 QPS 时写回 HTTP 200 与 `R` JSON。
8. `SeckillOrderController#submit`：进入资源 `seckillOrderSubmit`，先过 `FlowSlot` 的 50 QPS。
9. `SeckillOrderController#submit`：再过 `DegradeSlot`，熔断器打开时直接拦截。
10. `SeckillOrderController#submitBlocked`：命中流控或熔断时返回 `14013`，不执行后续业务。
11. `SeckillOrderServiceImpl#submit`：放行后才执行 Redis Lua 预扣、落预扣单、发建单消息。
12. `SeckillController#skuInfo`：进入资源 `seckillSkuInfo`，先过 `ParamFlowSlot` 按 `skuId` 限流。
13. `SeckillController#skuInfo`：再过 `FlowSlot` 的 200 QPS。
14. `SeckillController#skuInfoBlocked`：热点参数或流控命中时返回 `14013`。
15. `SeckillSkuServiceImpl#getSkuInfo`：放行后读场次商品 Hash、库存 String、限购 Hash，缺失时回源数据库。
16. `SeckillController#skuInfoFallback`：业务抛非 `BlockException` 异常时返回 `14009`。
17. `ProductFeignClientFallback#getSkuInfo`：product 超时或不可用时返回空 `R.ok()`，调用方按 null 跳过。
18. `SeckillFeignClientFallback#releaseSeckillOrder`：order 通知 seckill 释放失败时记录错误并返回 `R.error`，取消流程不回滚。
19. `SeckillFeignClientFallback#markSeckillOrderPaid`：支付回填失败同样只记录，等待对账任务补偿。
20. `OrderFeignClient#createSeckillOrder`：无 fallback，异常向上抛出并进入 MQ 重试或对账。
