# gulimall-seckill 集成测试说明

测试目录按 README 第三节的流程编号组织，一个流程一个测试类。测试连接真实 MySQL、Redis、RabbitMQ、Nacos 和业务微服务，不使用 Mock 替换业务 Bean。

## 运行前提

1. 启动虚拟机上的 MySQL、Redis、RabbitMQ、Nacos、Sentinel、Jaeger。
2. 按流程启动对应业务服务：`gulimall-product`、`gulimall-member`、`gulimall-order`、`gulimall-ware`、`gulimall-coupon`。
3. 确认 `config/common.yaml` 和 `config/gulimall-seckill.yaml` 中的 MySQL、Redis、RabbitMQ、Nacos 地址可用。
4. 停止本机 IDEA 中的 `gulimall-seckill`；测试进程会自己启动一个完整实例，两个实例会抢建单消息和定时任务。
5. MQ 重试依赖 RabbitMQ 延时消息插件 `rabbitmq_delayed_message_exchange`。

## 测试配置

`src/test/resources/application-test.yaml`：

- 连接真实中间件。
- `spring.cloud.nacos.discovery.register-enabled=false`，测试实例不注册到 Nacos，避免污染正在运行的 seckill；其它服务仍可被发现。
- `spring.rabbitmq.listener.simple.auto-startup=false`，普通测试不启动监听容器。
- Flow06、Flow19 通过 `@SpringBootTest(properties="spring.rabbitmq.listener.simple.auto-startup=true")` 单独开启，并用 `@DirtiesContext` 在类结束后关闭上下文。

## 执行命令

默认 `mvn test` 不跑集成测试（Surefire 排除了 `integration` 标签）。

运行全部流程测试：

```text
mvn -o -pl gulimall-seckill -am test -Dsurefire.excludedGroups= -Dtest="Flow*Test" -Dsurefire.failIfNoSpecifiedTests=false
```

只运行某个流程：

```text
mvn -o -pl gulimall-seckill -am test -Dsurefire.excludedGroups= -Dtest=Flow04SubmitSeckillOrderTest -Dsurefire.failIfNoSpecifiedTests=false
```

只运行某个方法：

```text
mvn -o -pl gulimall-seckill -am test -Dsurefire.excludedGroups= -Dtest="Flow04SubmitSeckillOrderTest#sameRequestIdReturnsSameOrderWithoutSecondDeduction" -Dsurefire.failIfNoSpecifiedTests=false
```

## 测试类

| 测试类 | 对应流程 | 覆盖内容 | 额外依赖 |
|---|---|---|---|
| `Flow01OpenSeckillPageTest` | 01 打开秒杀页 | 页面、场次、商品、首页场次选择、过期场次过滤 | 无 |
| `Flow02SeckillItemDetailTest` | 02 秒杀商品详情 | 游客/登录信息、已购、库存缺失、Hash 回源 | product（回源方法） |
| `Flow03SeckillConfirmPageTest` | 03 秒杀确认页 | 未登录跳转、默认地址、数量上限、无默认地址跳转 | member |
| `Flow04SubmitSeckillOrderTest` | 04 提交抢购订单 | 预扣、限购、防重、幂等、时间窗口、MQ 消息 | 无 |
| `Flow05OrderResultPollTest` | 05 抢购结果轮询 | 按订单号/请求 id 查询、状态文案、越权查询 | 无 |
| `Flow06AsyncBuildOrderTest` | 06 异步建单 | 监听建单、ware 库存转换、失败回补 | order、ware、member |
| `Flow07UserCancelReleaseTest` | 07 用户取消回补 | 取消后回秒杀池/普通池 | order、ware、member |
| `Flow08OrderTimeoutReleaseTest` | 08 订单超时回补 | `OrderTimeoutTask` 关单后释放 | order、ware、member |
| `Flow09PaymentPaidCallbackTest` | 09 支付成功回填 | seckill `PAID` 回调、幂等、非已建单忽略 | 无 |
| `Flow10AdminSessionManageTest` | 10 后台场次管理 | 新增/修改/下架/取消/删除、延时消息 | coupon；取消需 ware |
| `Flow11AdminSkuRelationTest` | 11 后台关联商品 | 新增/修改/删除、ware 锁定、Hash 刷新、重叠校验 | coupon、ware、product |
| `Flow12SessionStartTest` | 12 场次到点开始 | 开始消息、旧消息忽略、提前消息重投 | coupon |
| `Flow13SessionEndSettleTest` | 13 场次结束与结算 | 停止中、删库存 key、结算释放剩余锁定 | coupon、ware |
| `Flow14SeckillSkuUploadTest` | 14 秒杀商品上架 | 上架、randomCode 沿用、库存 key 只补不覆盖 | product |
| `Flow15ReconcileTaskTest` | 15 对账任务 | 限购重建、建单重发、超限回补 | order（补偿方法） |
| `Flow16SeckillPromotionManageTest` | 16 秒杀活动管理 | 活动 CRUD、未结束场次保护 | coupon |
| `Flow17SentinelTest` | 17 Sentinel | 方法流控、提交流控、异常比例熔断、热点参数、fallback | 无 |
| `Flow18ObservabilityTest` | 18 监控与链路追踪 | Jaeger 服务可见、`traceparent` 传播 | Jaeger |
| `Flow19MqRetryDeadLetterTest` | 19 MQ 重试死信 | 坏消息重试 3 次、死信归档、队列释放 | 无 |
| `Flow20CacheFallbackTest` | 20 缓存回源与降级 | Hash 缺失/损坏回源、库存 key 缺失、防重 key TTL | product（回源方法） |
| `Flow21SkuNoticeCrudTest` | 21 通知订阅 | CRUD、保存不产生发送消息 | coupon |

## 测试数据

- 固定测试身份：`memberId=990001`、`skuId=990001`、`wareId=990001`。
- `SeckillIntegrationTestSupport#ensureBaseData` 创建 `ums_member`、`ums_member_receive_address`、`pms_sku_info`、`wms_ware_sku`。
- 每个测试创建独立场次和关联，场次名以 `IT-SECKILL-` 开头；清理时按前缀和跟踪的 id 删除。
- 清理范围：
  - MySQL：`sms_seckill_session`、`sms_seckill_sku_relation`、`sms_seckill_order`、`sms_seckill_promotion`、`sms_seckill_sku_notice`、`mq_message`、`mq_dead_message`、`oms_order`、`oms_order_item`、`oms_payment_info`、`oms_refund_info`、`wms_ware_order_task`、`wms_ware_order_task_detail`、`wms_seckill_stock_operation`、`wms_ware_sku`、`pms_sku_info`、`ums_member`、`ums_member_receive_address`。
  - Redis：测试场次的商品 Hash、库存、限购、防重、预扣、释放和场次锁 key。
  - Session：测试创建的 Spring Session。
- 工具只读流程（01、02、05、09、20 的部分方法）使用测试数据，不修改真实业务数据。

## 注意事项

- `Flow06`、`Flow07`、`Flow08` 会用真实 order/ware 服务创建订单和库存工作单；测试后按 `memberId` 和跟踪的订单号清理。
- `Flow08` 依赖 `OrderTimeoutTask` 每分钟整点执行，单次最长等待约 90 秒。
- `Flow19` 把 `MqConstants.RETRY_DELAY_MILLIS` 从 10s/30s/60s 临时改成 1s/3s/6s，结束后恢复；运行前必须停止正式 seckill，避免正式实例消费测试消息。
- `Flow03` 的登录态流程直接调用真实 `SeckillPageController`，重点验证默认地址、数量上限和模型数据，不依赖整页 Thymeleaf 渲染。
- `Flow17` 不 Mock 业务 Bean；熔断规则通过 Sentinel `SphU` 和真实异常比例规则验证，fallback 方法直接调用真实 Controller 方法。
- `Flow18` 默认查询 `http://<your-vm-host-ip>:16686`，可用 `-Djaeger.query.url=...` 覆盖；Jaeger 不可用或未收到测试实例 span 时跳过，`tracingBeansAreConfigured` 始终验证 Micrometer Tracer。
- `Flow09` 无法构造支付宝私钥签名，因此不驱动完整 `/pay/notify`；覆盖 seckill 收到 `PAID` 回调后的状态推进。
- 跨模块服务未启动时，测试通过 JUnit `Assumptions` 跳过，不伪造结果。
