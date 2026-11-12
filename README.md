# 谷粒商城（Gulimall）

## 一、项目概述

谷粒商城是一个基于 Spring Cloud Alibaba 的 B2C 电商微服务系统，覆盖商品、搜索、购物车、订单、库存、支付、秒杀、会员、认证、AI 助手、可观测性与部署全链路。

系统分为两端：

| 使用端 | 地址 | 面向人群 |
|---|---|---|
| 商城前台 | `http://gulimall.vip` | 购物顾客 |
| 管理后台 | `http://admin.gulimall.vip` | 运营、商品、订单、库存管理员 |

前台提供注册登录、分类浏览、商品搜索、商品详情、购物车、下单支付、订单查询、秒杀、个人中心、AI 购物助手。

后台提供分类、品牌、属性、商品、库存、采购、优惠券、秒杀场次、订单、退款、会员、内容管理和 AI 运营助手。

整体架构：

```text
浏览器 / 管理端 Vue
        │
        ▼
Nginx :80  多子域统一入口
        │
        ├── gulimall.vip      → Gateway :88 → product / coupon / member / order / ware / agent
        ├── admin.gulimall.vip→ 静态页 + Gateway → renren-fast
        ├── search.gulimall.vip  → search :12000
        ├── auth.gulimall.vip    → auth-server :20000
        ├── cart.gulimall.vip    → cart :13000
        ├── order.gulimall.vip   → order :9000
        ├── seckill.gulimall.vip → seckill :14000
        ├── member.gulimall.vip  → member :8000
        ├── img.gulimall.vip     → MinIO
        └── pay.gulimall.vip     → Cloudflare Tunnel → order :9000

公共基础设施：Nacos、MySQL、Redis、Elasticsearch、RabbitMQ、RocketMQ、
Seata、Sentinel、MinIO、Milvus、Jaeger，由 deploy/docker-compose.yaml 编排。
```

## 二、技术栈

| 层次 | 技术 | 版本 / 说明 |
|---|---|---|
| 语言与运行时 | JDK | 21 |
| 基础框架 | Spring Boot | 3.5.16 |
| 微服务 | Spring Cloud / Spring Cloud Alibaba | 2025.0.3 / 2025.0.0.0 |
| 注册与配置 | Nacos | 3.0.3 |
| 网关 | Spring Cloud Gateway | WebFlux，端口 88 |
| 远程调用 | OpenFeign + LoadBalancer | 连接 2s、读取 5s |
| 持久层 | MyBatis-Plus | 3.5.16 |
| 数据库 | MySQL | 8.4.11，按业务域分库 |
| 缓存 | Redis 7.2.12 + Caffeine + Redisson 3.52.0 | 本地缓存 + 分布式缓存 |
| 搜索 | Elasticsearch / Kibana | 8.18.8 |
| 消息 | RabbitMQ / RocketMQ | 3.13.7 实际使用 / 5.3.2 预留 |
| 分布式事务 | Seata | 2.5.0.jdk21，AT 模式 |
| 限流熔断 | Sentinel | 1.8.9，规则持久化 Nacos |
| 对象存储 | MinIO | 前端预签名直传 |
| AI | LangChain4J + Milvus | 1.0.1 / 2.5.10 |
| 链路追踪 | Micrometer + OpenTelemetry + Jaeger | OTLP 4318 |
| 前端 | Thymeleaf + Vue2 + Element UI | 前台服务端渲染，后台 SPA |
| 部署 | Docker Compose + Nginx + K8s + Jenkins | 单机与集群两套 |

## 三、模块与端口

| 模块 | 端口 | 职责 | 数据库 |
|---|---:|---|---|
| gulimall-gateway | 88 | 统一路由、跨域、网关限流、请求日志 | 无 |
| gulimall-product | 10000 | 分类、品牌、属性、SPU/SKU、商品详情 | gulimall_pms |
| gulimall-coupon | 7000 | 优惠券、秒杀场次与关联 | gulimall_sms |
| gulimall-member | 8000 | 会员、收货地址、社交登录落库 | gulimall_ums |
| gulimall-order | 9000 | 订单、支付、退款、售后 | gulimall_oms |
| gulimall-ware | 11000 | 仓库、库存、采购、运费 | gulimall_wms |
| gulimall-search | 12000 | Elasticsearch 检索与商品上架写入 | 无 MySQL |
| gulimall-cart | 13000 | 游客车、登录车、合并、结算取数 | 纯 Redis |
| gulimall-seckill | 14000 | 秒杀抢购、预扣、异步建单、回补 | Redis + gulimall_sms |
| gulimall-agent | 15000 | AI 对话、工具调用、RAG、图片 | gulimall_ai |
| gulimall-auth-server | 20000 | 登录、注册、短信、OAuth2、签发 JWT | Redis Session |
| gulimall-third-party | 30000 | 短信、MinIO、百度地图、OAuth 换 token | 无 MySQL |
| gulimall-common | — | 公共返回、异常、常量、AOP、工具 | 公共库 |
| gulimall-api | — | 跨服务 DTO/VO | 公共库 |
| gulimall-rabbitmq | — | 可靠消息、延时、重试、死信 | 公共库 |
| gulimall-rocketmq | — | RocketMQ 模板骨架 | 公共库 |
| renren-fast | 8080 | 后台管理端 | gulimall_admin |
| renren-fast-vue | 8001 | 管理端前端 | — |
| renren-generator | — | 代码生成器 | 开发期工具 |
| gulimall-k8s | — | 独立 AI 应用 + K8s/Jenkins 练习 | 独立工程 |

## 四、数据库

按业务域拆成 6 个业务库和 1 个管理库，服务只访问自己的库，跨库数据通过 Feign 或消息获取。

| 数据库 | 主要表 |
|---|---|
| gulimall_pms | pms_category、pms_brand、pms_attr、pms_attr_group、pms_attr_attrgroup_relation、pms_category_brand_relation、pms_spu_info、pms_spu_info_desc、pms_spu_images、pms_sku_info、pms_sku_images、pms_product_attr_value、pms_sku_sale_attr_value |
| gulimall_sms | sms_coupon、sms_spu_bounds、sms_sku_ladder、sms_sku_full_reduction、sms_member_price、sms_seckill_session、sms_seckill_sku_relation、sms_seckill_order |
| gulimall_ums | ums_member、ums_member_level、ums_member_receive_address |
| gulimall_oms | oms_order、oms_order_item、oms_payment_info、oms_refund_info、oms_order_return_apply |
| gulimall_wms | wms_ware_info、wms_ware_sku、wms_purchase、wms_purchase_detail、wms_ware_order_task、wms_ware_order_task_detail、wms_seckill_stock_operation |
| gulimall_ai | AI 会话、会话消息、设置、自定义模型、图片任务等 |
| gulimall_admin | sys_menu、sys_user、sys_role 等后台管理表 |

跨库一致性由 Seata AT、可靠消息、本地消息表和定时对账共同保证。

## 五、项目开发履历与功能模块

项目按 git 提交顺序分为以下阶段，每个阶段对应一份详细文档。

| 阶段 | 主要内容 | 对应文档 |
|---|---|---|
| 1. 工程骨架 | 环境、Docker、Maven 多模块、renren-fast/generator、Nacos、Gateway | [1. Linux 创建与环境准备](document/项目总览/项目总览-01-Linux创建与环境准备-介绍.md) |
| 6. 商品属性 | 三级分类、品牌、MinIO、属性分组、分类品牌关联、规格参数、平台属性 | [6.1 三级分类](document/项目总览/项目总览-02-三级分类-介绍.md)、[6.2 品牌管理](document/项目总览/项目总览-03-品牌管理-介绍.md)、[6.3 MinIO 文件上传](document/项目总览/项目总览-04-MinIO文件上传-介绍.md)、[6.4 属性分组](document/项目总览/项目总览-05-属性分组-介绍.md)、[6.5 分类品牌关联](document/项目总览/项目总览-06-分类品牌关联-介绍.md)、[6.6 规格参数](document/项目总览/项目总览-07-规格参数-介绍.md)、[6.7 平台属性与分组关联](document/项目总览/项目总览-08-平台属性与分组关联-介绍.md) |
| 7. 商品与库存 | 新增商品、商品管理、仓库库存、采购 | [7.1 新增商品](document/项目总览/项目总览-09-新增商品-介绍.md)、[7.2 商品管理](document/项目总览/项目总览-10-商品管理-介绍.md)、[7.3 仓库与库存管理](document/项目总览/项目总览-11-仓库与库存管理-介绍.md)、[7.4 采购管理](document/项目总览/项目总览-12-采购管理-介绍.md) |
| 8. 搜索与详情 | 商品上架写 ES、搜索接口、聚合高亮、商品详情异步编排 | [8.1 商品上架与 ES 索引](document/项目总览/项目总览-13-商品上架与ES索引-介绍.md)、[8.2 搜索接口与搜索页](document/项目总览/项目总览-14-搜索接口与搜索页-介绍.md)、[8.3 商品详情页与异步编排](document/项目总览/项目总览-15-商品详情页与异步编排-介绍.md) |
| 9. 登录与个人中心 | 账号密码登录、短信注册、GitHub/Gitee/微信、Spring Session、个人中心 | [9.1 登录与短信注册](document/项目总览/项目总览-16-登录与短信注册-介绍.md)、[9.2 OAuth2 社交登录](document/项目总览/项目总览-17-OAuth2社交登录-介绍.md)、[9.3 Spring Session 与个人中心](document/项目总览/项目总览-18-SpringSession与个人中心-介绍.md) |
| 10. 购物车 | 游客车、登录车、Redis Hash、登录合并、数量勾选 | [10.1 购物车](document/项目总览/项目总览-19-购物车-介绍.md) |
| 11. 订单 | 确认页、防重令牌、验价、选仓运费、订单落库、超时关单 | [11.1 订单确认](document/项目总览/项目总览-20-订单确认-介绍.md)、[11.2 订单提交与状态](document/项目总览/项目总览-21-订单提交与状态-介绍.md) |
| 12. 库存与事务 | 库存工作单、Seata AT、RabbitMQ 可靠消息 | [12.1 库存工作单与锁库存](document/项目总览/项目总览-22-库存工作单与锁库存-介绍.md)、[12.2 Seata 分布式事务](document/项目总览/项目总览-23-Seata分布式事务-介绍.md)、[12.3 RabbitMQ 可靠消息与延时队列](document/项目总览/项目总览-24-RabbitMQ可靠消息与延时队列-介绍.md) |
| 13. 支付与售后 | 支付宝支付、回调、退款、退货、交易统计 | [13.1 支付宝支付](document/项目总览/项目总览-25-支付宝支付-介绍.md)、[13.2 退款与售后](document/项目总览/项目总览-26-退款与售后-介绍.md) |
| 14. 秒杀准备 | 场次状态机、商品上架、Redis 预热、后台管理、前台页面 | [14.1 秒杀场次与商品上架](document/项目总览/项目总览-27-秒杀场次与商品上架-介绍.md)、[14.2 秒杀缓存与后台管理](document/项目总览/项目总览-28-秒杀缓存与后台管理-介绍.md) |
| 15. 秒杀执行 | Lua 预扣、异步建单、回补、对账、Sentinel、压测 | [15.1 秒杀抢购与异步建单](document/项目总览/项目总览-29-秒杀抢购与异步建单-介绍.md)、[15.2 秒杀回补、对账与限流](document/项目总览/项目总览-30-秒杀回补对账与限流-介绍.md) |
| 16. 可观测性与压测 | Jaeger、日志、Sentinel、JMeter、SQL/JVM 调优 | [16.1 可观测性与日志](document/项目总览/项目总览-31-可观测性与日志-介绍.md)、[16.2 Sentinel 限流熔断](document/项目总览/项目总览-32-Sentinel限流熔断-介绍.md)、[16.3 JMeter 压测与性能优化](document/项目总览/项目总览-33-JMeter压测与性能优化-介绍.md) |
| 17. 入口与部署 | Nginx 多子域、Cloudflare Tunnel、Linux 部署 | [17.1 Nginx 多子域](document/项目总览/项目总览-34-Nginx多子域-介绍.md)、[17.2 Cloudflare Tunnel 与支付回调](document/项目总览/项目总览-35-CloudflareTunnel与支付回调-介绍.md)、[17.3 Linux 部署与启动脚本](document/项目总览/项目总览-36-Linux部署与启动脚本-介绍.md) |
| 18. K8s 与 Jenkins | StatefulSet、Deployment、Secret、探针、Jenkins 流水线 | [18.1 K8s 部署](document/项目总览/项目总览-37-K8s部署-介绍.md)、[18.2 Jenkins 流水线](document/项目总览/项目总览-38-Jenkins流水线-介绍.md) |
| 19. AI 助手 | 多模型、流式对话、工具调用、图片、RAG | [19.1 AI 多模型与对话](document/项目总览/项目总览-39-AI多模型与对话-介绍.md)、[19.2 AI 工具与天气查询](document/项目总览/项目总览-40-AI工具与天气查询-介绍.md)、[19.3 AI 图片识别与生成](document/项目总览/项目总览-41-AI图片识别与生成-介绍.md)、[19.4 AI RAG 与 Milvus](document/项目总览/项目总览-42-AI-RAG与Milvus-介绍.md) |
| 20. AI 安全 | RS256 JWT、双端鉴权、内部接口、密钥保护 | [20.1 AI JWT 与内部接口安全](document/项目总览/项目总览-43-AI-JWT与内部接口安全-介绍.md) |

## 六、使用指南

### 6.1 商城前台

**注册与登录**

1. 打开商城前台，点击「注册」，填写用户名、手机号、密码。
2. 点击「获取验证码」，填写短信验证码，勾选用户协议后提交。
3. 注册成功后回到登录页，使用账号密码登录。
4. 也支持 GitHub、Gitee、微信网页授权登录；首次登录会自动创建商城账号。
5. 登录状态在商品、购物车、订单、个人中心、秒杀等页面共享。

**浏览与搜索**

1. 首页展示分类导航、轮播广告、推荐商品和秒杀入口。
2. 点击一级分类逐级进入二级、三级分类。
3. 顶部搜索框输入关键词，进入搜索结果页。
4. 可按品牌、价格区间、有货状态、规格属性筛选，按价格、销量、热度排序。

**商品详情**

1. 详情页展示商品图片、名称、卖点、价格、库存、规格参数和商品描述。
2. 选择颜色、内存等销售属性后，价格和库存随 SKU 变化。
3. 可加入购物车，也可在秒杀商品上进入秒杀入口。

**购物车**

1. 未登录也可以加购，系统用 `user-key` 保存游客车。
2. 登录后游客车会自动合并到账号购物车。
3. 购物车支持修改数量、勾选、删除、清理下架或无货商品、查看合计金额。
4. 勾选商品后点击「去结算」。

**下单与支付**

1. 在订单确认页选择收货地址；没有地址先新增。
2. 确认商品清单、数量、价格、优惠券、积分和运费。
3. 点击「提交订单」，进入支付页。
4. 选择支付宝沙箱完成付款，支付成功后订单变为「待发货」。
5. 订单提交后 30 分钟内未支付会自动取消并释放库存。

**我的订单**

1. 按待付款、待发货、待收货、已完成、已关闭、待退货、已退货筛选。
2. 可查看详情、取消未支付订单、继续支付、查看物流、确认收货、申请退款或退货。

**秒杀**

1. 从首页秒杀入口进入，选择场次。
2. 场次开始前查看商品和秒杀价，开始后点击「立即抢购」。
3. 抢购成功后进入确认页提交订单。
4. 秒杀商品数量有限，同一场次同一商品通常有每人限购。

**个人中心**

1. 修改昵称、头像、性别、生日等资料。
2. 修改密码。
3. 新增、修改、删除收货地址并设置默认地址。
4. 查看积分、成长值、优惠券和订单。

**AI 购物助手**

1. 登录后点击页面右下角的动漫宠物打开聊天窗。
2. 可以询问商品推荐、分类、品牌、价格、库存、购物车、自己的订单、优惠券、积分、秒杀活动、天气和商城使用流程。
3. 支持切换模型、流式输出、新建对话、查看最近 3 天历史、搜索对话、上传图片识别、生成图片、自定义提示词和自定义模型。
4. 模型标注「不支持查库」时只能通用对话或看图；查实时数据选择支持工具调用的模型，默认 DeepSeek 可直接使用。

### 6.2 管理后台

**登录**

1. 打开 `http://admin.gulimall.vip`。
2. 输入管理员账号、密码、验证码后进入后台。

**系统管理**

- 管理员列表、角色管理、菜单管理、定时任务、SQL 监控。

**商品管理**

- 分类维护：新增、修改、删除三级分类。
- 品牌管理：新增品牌、上传 logo、修改、删除。
- 平台属性：属性分组、规格参数、销售属性。
- 商品维护：SPU 管理、发布商品、商品管理、上下架、修改规格。

**库存系统**

- 仓库维护、商品库存、采购单、采购需求合并、领取采购、完成采购、库存工作单。

**优惠营销**

- 优惠券管理、发放记录、专题活动、秒杀场次、每日秒杀、积分维护、满减折扣、会员价格。

**订单系统**

- 订单查询、订单详情、发货、退货单处理、支付流水、退款流水、等级规则。

**用户系统**

- 会员列表、会员等级、积分变化、统计信息。

**内容管理**

- 首页推荐、分类热门、评论管理。

**AI 运营助手**

1. 进入「AI 助手 → AI 聊天」。
2. 可查询全部订单、交易额、订单数、商品销量、每日趋势、商品、分类、品牌、SPU、SKU、仓库库存、会员概要、优惠券和秒杀场次。
3. 运营助手只做查询，不修改商品、订单、库存，也不执行发货或退款。

### 6.3 关键流程

**管理员上架一件商品**

1. 商品系统 → 分类维护：建立一级、二级、三级分类。
2. 品牌管理：新增品牌并上传 logo。
3. 平台属性：建立属性分组、规格参数和销售属性。
4. 商品维护 → 发布商品：选择三级分类和品牌，填写 SPU 信息、图片、规格参数，添加销售属性生成 SKU，填写价格、重量、默认图和库存。
5. 保存或上架。
6. 到商品管理确认上架，到库存系统确认可用库存，再到前台用分类或搜索验证。

**用户购买一件商品**

1. 登录商城，通过分类或搜索找到商品。
2. 选择规格后加入购物车。
3. 购物车确认数量和勾选，点击去结算。
4. 选择收货地址，确认优惠、积分、运费和应付金额。
5. 提交订单并完成支付宝付款。
6. 支付成功后订单变为待发货；发货后确认收货，订单完成。

**秒杀一件商品**

1. 管理员在每日秒杀中创建场次，在秒杀活动中关联 SKU，设置秒杀价、限购和秒杀库存。
2. 用户在秒杀页选择场次，开始后点击立即抢购。
3. 抢购成功后进入确认页提交订单。
4. 未支付订单超时后取消并释放秒杀库存。

**退款或退货**

1. 用户在已支付订单中点击「申请退款/退货」，填写原因。
2. 管理员在退货单处理中审核。
3. 审核通过后按流程退款，用户在订单中查看状态。

**使用 AI 查数据**

- 用户端：登录后点击宠物，询问商品、库存、自己的订单、优惠券、秒杀等。
- 管理端：进入 AI 聊天，询问交易额、订单状态、库存、销量、会员概要等。

## 七、文档导航

| 文档 | 内容 |
|---|---|
| [1. Linux 创建与环境准备](document/项目总览/项目总览-01-Linux创建与环境准备-介绍.md) | Linux、Docker、项目骨架、Nacos、Gateway |
| [6.1 三级分类](document/项目总览/项目总览-02-三级分类-介绍.md) | 分类表、树形查询、增删改、拖拽、缓存 |
| [6.2 品牌管理](document/项目总览/项目总览-03-品牌管理-介绍.md) | 品牌 CRUD、JSR303 分组校验、改名级联 |
| [6.3 MinIO 文件上传](document/项目总览/项目总览-04-MinIO文件上传-介绍.md) | 预签名直传、桶公共读、根相对路径 |
| [6.4 属性分组](document/项目总览/项目总览-05-属性分组-介绍.md) | 分组分页、分类路径、分组属性关联 |
| [6.5 分类品牌关联](document/项目总览/项目总览-06-分类品牌关联-介绍.md) | 多对多关联、去重、双向级联 |
| [6.6 规格参数](document/项目总览/项目总览-07-规格参数-介绍.md) | attr_type、基本属性、SPU 规格读写 |
| [6.7 平台属性与分组关联](document/项目总览/项目总览-08-平台属性与分组关联-介绍.md) | 已关联/未关联、批量增删关联 |
| [7.1 新增商品](document/项目总览/项目总览-09-新增商品-介绍.md) | SPU/SKU 五步向导、积分满减 Feign |
| [7.2 商品管理](document/项目总览/项目总览-10-商品管理-介绍.md) | SPU/SKU 查询、上架写 ES、规格修改 |
| [7.3 仓库与库存管理](document/项目总览/项目总览-11-仓库与库存管理-介绍.md) | 仓库、库存、可用库存、Redis 回写 |
| [7.4 采购管理](document/项目总览/项目总览-12-采购管理-介绍.md) | 采购单、需求合并、领取、完成入库 |
| [8.1 商品上架与 ES 索引](document/项目总览/项目总览-13-商品上架与ES索引-介绍.md) | SkuEsModel、Bulk 写入、mapping |
| [8.2 搜索接口与搜索页](document/项目总览/项目总览-14-搜索接口与搜索页-介绍.md) | bool 查询、nested、聚合、高亮 |
| [8.3 商品详情页与异步编排](document/项目总览/项目总览-15-商品详情页与异步编排-介绍.md) | CompletableFuture、线程池、详情聚合 |
| [9.1 登录与短信注册](document/项目总览/项目总览-16-登录与短信注册-介绍.md) | 账号密码、短信验证码、频控 |
| [9.2 OAuth2 社交登录](document/项目总览/项目总览-17-OAuth2社交登录-介绍.md) | GitHub、Gitee、微信、state |
| [9.3 Spring Session 与个人中心](document/项目总览/项目总览-18-SpringSession与个人中心-介绍.md) | Session+Redis、跨子域、地址管理 |
| [10.1 购物车](document/项目总览/项目总览-19-购物车-介绍.md) | 游客车、登录车、合并、Redis Hash |
| [11.1 订单确认](document/项目总览/项目总览-20-订单确认-介绍.md) | 确认页、防重令牌、运费、地址 |
| [11.2 订单提交与状态](document/项目总览/项目总览-21-订单提交与状态-介绍.md) | 验价、预扣、锁库存、状态机、超时关单 |
| [12.1 库存工作单与锁库存](document/项目总览/项目总览-22-库存工作单与锁库存-介绍.md) | 工作单、锁定/解锁/扣减/退货 |
| [12.2 Seata 分布式事务](document/项目总览/项目总览-23-Seata分布式事务-介绍.md) | AT、undo_log、全局事务边界 |
| [12.3 RabbitMQ 可靠消息与延时队列](document/项目总览/项目总览-24-RabbitMQ可靠消息与延时队列-介绍.md) | 本地消息表、confirm、重试、死信、延时 |
| [13.1 支付宝支付](document/项目总览/项目总览-25-支付宝支付-介绍.md) | 沙箱支付、回调、验签、幂等 |
| [13.2 退款与售后](document/项目总览/项目总览-26-退款与售后-介绍.md) | 退款、退货、审核、重试 |
| [14.1 秒杀场次与商品上架](document/项目总览/项目总览-27-秒杀场次与商品上架-介绍.md) | 场次状态机、商品上架、库存锁定 |
| [14.2 秒杀缓存与后台管理](document/项目总览/项目总览-28-秒杀缓存与后台管理-介绍.md) | Redis 商品/库存、随机码、回源 |
| [15.1 秒杀抢购与异步建单](document/项目总览/项目总览-29-秒杀抢购与异步建单-介绍.md) | Lua 预扣、限购、MQ 异步建单 |
| [15.2 秒杀回补、对账与限流](document/项目总览/项目总览-30-秒杀回补对账与限流-介绍.md) | 回补、结算、对账、Sentinel、压测 |
| [16.1 可观测性与日志](document/项目总览/项目总览-31-可观测性与日志-介绍.md) | 日志 AOP、traceId、Jaeger |
| [16.2 Sentinel 限流熔断](document/项目总览/项目总览-32-Sentinel限流熔断-介绍.md) | 网关/抢购/热点规则、降级 |
| [16.3 JMeter 压测与性能优化](document/项目总览/项目总览-33-JMeter压测与性能优化-介绍.md) | 压测场景、SQL/JVM/连接池调优 |
| [17.1 Nginx 多子域](document/项目总览/项目总览-34-Nginx多子域-介绍.md) | 9 个 server、静态资源、/oss |
| [17.2 Cloudflare Tunnel 与支付回调](document/项目总览/项目总览-35-CloudflareTunnel与支付回调-介绍.md) | pay 子域、Tunnel、缓存规则 |
| [17.3 Linux 部署与启动脚本](document/项目总览/项目总览-36-Linux部署与启动脚本-介绍.md) | start-all、JVM、日志、端口 |
| [18.1 K8s 部署](document/项目总览/项目总览-37-K8s部署-介绍.md) | StatefulSet、Deployment、探针、Secret |
| [18.2 Jenkins 流水线](document/项目总览/项目总览-38-Jenkins流水线-介绍.md) | 构建、镜像、推送、rollout、RBAC |
| [19.1 AI 多模型与对话](document/项目总览/项目总览-39-AI多模型与对话-介绍.md) | 模型策略、流式、停止、Redis 记忆 |
| [19.2 AI 工具与天气查询](document/项目总览/项目总览-40-AI工具与天气查询-介绍.md) | Function Calling、WeatherTools、降级 |
| [19.3 AI 图片识别与生成](document/项目总览/项目总览-41-AI图片识别与生成-介绍.md) | 上传校验、视觉识别、异步生成 |
| [19.4 AI RAG 与 Milvus](document/项目总览/项目总览-42-AI-RAG与Milvus-介绍.md) | 知识库、embedding、audience 过滤 |
| [20.1 AI JWT 与内部接口安全](document/项目总览/项目总览-43-AI-JWT与内部接口安全-介绍.md) | RS256、双端鉴权、内部 token、AES-GCM |
| [ES 文档](document/ES/ES.md) | Elasticsearch 安装、运维与索引 |
| [ES 整合](document/ES/ES整合SpringBoot.md) | Java 客户端与 DSL |
| [JWT 文档](document/JWT/JWT原理与项目实践.md) | JWT 原理与实现 |
| [Nginx 文档](document/Nginx/Nginx.md) | Nginx 子域与静态资源 |
| [Sentinel 文档](document/Sentinel/Sentinel.md) | 限流、熔断、热点参数 |
| [Spring Session](document/SpringSession/Spring%20Session%20%2B%20Redis%20接入.md) | Session + Redis 接入 |
| [内网穿透](document/内网穿透/Cloudflare-Tunnel-配置指南.md) | Cloudflare Tunnel |
| [K8s 与 DevOps](document/k8s/K8S部署%26DevOps.md) | K8s 部署与 Jenkins |
| [部署流程](document/Deploy/Deploy部署流程.md) | 服务器部署 |
| [秒杀设计](gulimall-seckill/docs/秒杀全流程设计思路.md) | 秒杀全流程 |
| [秒杀流程文档](gulimall-seckill/docs/) | 22 篇逐环节说明 |
| [AI 助手说明](gulimall-agent/README.md) | AI 模块接口与流程 |
| [JMeter 说明](jmeter/秒杀测试说明.md) | 压测场景与脚本 |
