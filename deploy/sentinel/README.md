# Sentinel 规则（Nacos 持久化）

## 1. 数据 ID 与分组

在 Nacos（`http://<your-vm-host-ip>:8848/nacos`）新建配置，**Group 全部用 `SENTINEL_GROUP`，格式 JSON**：

| Data ID | 规则类型 | 文件 |
|---|---|---|
| `gulimall-seckill-sentinel-flow.json` | 流控 | `gulimall-seckill-sentinel-flow.json` |
| `gulimall-seckill-sentinel-degrade.json` | 熔断 | `gulimall-seckill-sentinel-degrade.json` |
| `gulimall-seckill-sentinel-param-flow.json` | 热点 | `gulimall-seckill-sentinel-param-flow.json` |
| `gulimall-gateway-sentinel-gw-flow.json` | 网关流控（gw-flow） | `gulimall-gateway-sentinel-gw-flow.json` |

把对应文件内容整段粘贴进去即可。客户端配置已经在：

- `config/gulimall-seckill.yaml`
- `config/gulimall-gateway.yaml`

## 2. 当前规则含义

### seckill 流控
- `seckillOrderSubmit`：50 QPS，抢购入口 `@SentinelResource`
- `seckillSkuInfo`：200 QPS，商品详情秒杀信息
- `/seckill/sessions`、`/seckill/skus`：200 QPS，Web URL 资源

### seckill 熔断
- `seckillOrderSubmit`：慢调用比例模式，RT 超过 800ms 且慢调用比例 ≥ 50%，统计窗口 10 秒，熔断 10 秒；最小请求数 10。

### seckill 热点
- `seckillSkuInfo`：第 2 个参数（`skuId`，下标 1）每个 SKU 每秒限 20 次；超过返回 `14013`。

### gateway 网关流控
- 路由 `seckill-route`：100 QPS，超出由网关 `BlockRequestHandler` 返回统一 JSON。

> 规则数值是开发默认值，压测后按实际情况调整；`count` 不要照抄。

## 3. 是否永久保存

**默认不永久。** Sentinel Dashboard 默认把规则存在自己内存里：

- Dashboard 重启：Dashboard 内存里的规则丢失；
- 服务重启：客户端从 Dashboard 收到的规则丢失；
- 只有客户端从 Nacos 数据源加载的规则，会在每次重启时重新拉取。

本项目已经给 gateway、seckill 配了 Nacos 数据源，所以这份 README 里的 JSON 才是**规则的持久化来源**。

### 注意

- 在 Dashboard 里新建/修改的规则**不会自动写回 Nacos**。Dashboard 只适合观察和临时验证；正式修改要改 Nacos 配置。
- 如果希望“在 Dashboard 里改完就永久保存”，需要改造 Sentinel Dashboard（让它把规则写到 Nacos），当前使用的 `bladex/sentinel-dashboard:1.8.9` 没有这个能力。
- Gateway 的 `gw-flow` 规则在部分 Dashboard 版本没有管理页面，直接在 Nacos 里维护最稳。

## 4. 通过 Nacos API 发布（可选）

登录 Nacos 后也可以用接口发布：

```bash
curl -X POST 'http://<your-vm-host-ip>:8848/nacos/v1/cs/configs' \
  --data-urlencode 'dataId=gulimall-seckill-sentinel-flow.json' \
  --data-urlencode 'group=SENTINEL_GROUP' \
  --data-urlencode 'type=json' \
  --data-urlencode 'content@=gulimall-seckill-sentinel-flow.json'
```

Nacos 3.x 开了鉴权时，需要带 `accessToken`（先在 Nacos 控制台/登录接口获取）。

## 5. 验证

1. 启动 gateway、seckill，Nacos 里能看到 4 个 Data ID；
2. Sentinel Dashboard → 机器列表/簇点链路能看到 `gulimall-gateway`、`gulimall-seckill`；
3. 对 `seckillOrderSubmit` 连续发请求超过 50 QPS，返回统一 `14013`；
4. 重启 seckill 后规则仍在（从 Nacos 重新加载）。
