# gulimall 部署说明

> **一句话**：把项目要用到的中间件（数据库、缓存、搜索、文件存储等）用 Docker 一次性拉起来，再用 Nginx 当统一大门。
>
> **技术栈**：JDK 21 + Spring Boot 3.5.16 + Spring Cloud 2025.0.3 + Spring Cloud Alibaba 2025.0.0.0
>
> ⚠️ **路径约定**：本文的 **`/mydata/docker/`** 就是仓库里的 **`deploy/` 目录** ——
> 部署时把 `deploy/` 整个传到服务器上改名即可。下文一律写服务器路径，照抄即可执行。

---

## 一、组件版本总览

| 组件 | 干什么用的 | 版本 | 镜像 | 端口 |
|---|---|---|---|---|
| **Nacos** | 让各个微服务互相"找到"对方；统一管配置 | 3.0.3 | `nacos/nacos-server:v3.0.3` | 8848 / 9848 |
| **Sentinel** | 限流、熔断（防止某一个接口拖垮整个系统） | 1.8.9 | `bladex/sentinel-dashboard:1.8.9` | 8858 |
| **Seata** | 分布式事务（跨服务的数据一致性） | 2.5.0 | `apache/seata-server:2.5.0` | 7091 / 8091 |
| **Jaeger** | 调用链追踪（OTLP，一个请求经过了哪些服务） | 2.6.0 | `jaegertracing/jaeger:2.6.0` | 16686 / 4317 / 4318 |
| **Elasticsearch** | 商品搜索（全文检索、分词） | 8.18.8 | `elasticsearch:8.18.8` | 9200 / 9300 |
| **Kibana** | ES 的网页控制台 | 8.18.8 | `kibana:8.18.8` | 5601 |
| **MySQL** | 主数据库 | 8.4.11 | `mysql:8.4.11` | 3306 |
| **Redis** | 缓存 | 7.2.12 | `redis:7.2.12` | 6379 |
| **RabbitMQ** | 消息队列（订单超时、库存解锁、秒杀削峰） | 3.13.7 | `rabbitmq:3.13.7-management` | 5672 / 15672 |
| **RocketMQ** | 消息队列（事务消息、延迟/顺序消息，交易链路） | 5.3.2 | `apache/rocketmq:5.3.2` | 9876 / 10909 / 10911 |
| **RocketMQ Dashboard** | RocketMQ 的网页控制台 | 2.1.0 | `apacherocketmq/rocketmq-dashboard:2.1.0` | 8180 |
| **MinIO** | 存图片和文件（对象存储） | latest | `minio/minio:latest` | 19000 / 19001 |
| **Milvus** | 向量数据库（AI 知识库检索） | 2.5.10 | `milvusdb/milvus:v2.5.10` | 19530 / 9091 |
| **Milvus etcd / MinIO** | Milvus 自己的元数据与索引存储 | etcd 3.5.18 / MinIO latest | `quay.io/coreos/etcd:v3.5.18`、`minio/minio:latest` | 内部使用 |
| **Nginx** | 统一入口（对外只开这一个门） | 1.28 | `nginx:1.28-alpine` | 80 |
| Gateway | API 网关（项目自己的 Java 程序，不是容器） | — | `spring-cloud-starter-gateway` 4.3.x | 88 |
| gulimall-agent | AI 助手（模型 / 工具 / RAG，项目自己的 Java 程序） | — | LangChain4J 1.0.1 | 15000 |

> 各中间件的**服务端版本**必须和项目里的**客户端依赖版本**配套，否则连不上。客户端版本见文末附录。

---

## 二、快速部署

### 1. 启动

```bash
cd /mydata/docker
docker compose up -d
```

> ⚠️ 上面能直接跑的前提是已经建了 `.env` 软链：
> `ln -sf /mydata/app/config/docker-compose.env /mydata/docker/.env`。
> 没有软链时，**每条 compose 命令都要带**
> `--env-file /mydata/app/config/docker-compose.env`；否则 RabbitMQ/Milvus 等变量为空，
> 会出现 401 或 `signature does not match`。

**已启用**：MySQL、Redis、RabbitMQ、Nacos、Seata、MinIO、Elasticsearch、Kibana、Nginx、Sentinel、Jaeger、Milvus（含独立 etcd 和 MinIO）
>
> **RocketMQ（含 Dashboard）已部署但默认停止**：业务模块没有使用它，compose 已设 `restart: "no"`，不会开机自启；需要测试时手动 `start`。

> - MySQL 建库：把 `*.sql` 放进 `/mydata/docker/mysql/` 的初始化目录，或手动导入。
> - Redis 首次启动前需要准备 `/mydata/docker/redis/conf/redis.conf`。
> - `gulimall-agent`（端口 15000）是宿主机上的 Java 进程，不在 compose 里；启动方式与其它微服务相同，启动前先执行 `sql/gulimall_ai.sql` 和 `sql/gulimall_ai_menu.sql`。

> **IP 统一配置**：`config/docker-compose.env` 里：
> - `VM_HOST_IP`：虚拟机/Docker 宿主机 IP，Compose 的 `SEATA_IP`、RocketMQ `brokerIP1` 都用它；
> - `WINDOWS_HOST_IP`：Windows 宿主机 IP，Nginx 反代宿主机 Java 进程用。
>
> 换 IP 只改这两行，再重建相关容器（`docker compose up -d ...`）。
>
> `.env` 软链（`/mydata/docker/.env -> /mydata/app/config/docker-compose.env`）只让 Compose 读到这些变量；**`broker.conf` 是容器里的普通配置文件，不读 `.env`**。它的 `brokerIP1` 由 compose 启动命令用 `VM_HOST_IP` 注入模板生成，所以**不需要为 IP 再软链 `broker.conf`**。如果想把 broker 配置文件也集中放在 `/mydata/app/config`，可以单独软链模板文件；这不是 IP 生效的必要条件。
>
> ⚠️ Java 应用读的是 `config/common.yaml` 的 `MYSQL_HOST` / `NACOS_ADDR` 等，**不读 Docker `.env`**；换 IP 时两边都要同步（或者在 `common.yaml` 里也加一个 `VM_HOST_IP` 让其他 host 引用它）。

### 2. 各服务访问地址

| 服务 | 地址 | 账号密码 |
|---|---|---|
| Nacos 控制台 | `http://<服务器IP>:8848/nacos` | `nacos` / `nacos` |
| Sentinel 控制台 | `http://<服务器IP>:8858` | `sentinel` / `sentinel` |
| Seata 控制台 | `http://<服务器IP>:7091` | 首次访问按引导设置 |
| Jaeger 控制台 | `http://<服务器IP>:16686` | 无 |
| Kibana 控制台 | `http://<服务器IP>:5601` | `elastic` / compose 里的 `ELASTIC_PASSWORD` |
| Elasticsearch API | `http://<服务器IP>:9200` | 同上（Basic Auth，账号 `elastic`） |
| MinIO 控制台 | `http://<服务器IP>:19001` | compose 里的 `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` |
| RabbitMQ 控制台 | `http://<服务器IP>:15672` | `gulimall` / compose 里的 `RABBITMQ_PASSWORD` |
| RocketMQ Dashboard | `http://<服务器IP>:8180` | ACL 账号：`gulimall` / compose 里的 `ROCKETMQ_SECRET_KEY` |
| **商城前台** | `http://gulimall.vip`（需配 hosts，见 §9） | 无 |
| **搜索页** | `http://search.gulimall.vip/list.html`（需配 hosts） | 无 |
| **管理端** | `http://admin.gulimall.vip`（需配 hosts） | renren-fast 的账号 |
| **图片直链** | `http://img.gulimall.vip`（需配 hosts） | 无 |
| **Milvus gRPC** | `<服务器IP>:19530`（内网；gulimall-agent 连接） | `root` / `MILVUS_ROOT_PASSWORD` |
| **Milvus 健康检查** | `http://<服务器IP>:9091/healthz` | 无 |

### 3. Nacos（服务注册 + 配置中心）

**干什么的**：微服务启动时来这里"报到"（注册），需要调别的服务时来这里"查地址"；配置也统一从这里读。

- ⚠️ **必须配认证变量**，否则 Nacos 2.2.1+ 启动直接报错。compose 里已给示例值，**生产环境务必换掉**：
  ```bash
  export NACOS_AUTH_TOKEN="$(openssl rand -base64 32)"
  ```
  另外两个变量 `NACOS_AUTH_IDENTITY_KEY` / `NACOS_AUTH_IDENTITY_VALUE` 同理。
- **数据目录**：`/mydata/docker/nacos/data`（挂了宿主目录，删容器不丢数据）。
- **防火墙**：要放行 **8848 和 9848** 两个端口（9848 是 gRPC 长连接用的，只放 8848 会连不上）。

### 4. MySQL / Redis / RabbitMQ / RocketMQ（数据库 + 缓存 + 消息队列）

**MySQL / Redis**

- **数据目录**：`/mydata/docker/mysql/data`、`/mydata/docker/redis/data`（都挂了宿主目录，删容器不丢）。
- **防火墙**：按需放行 3306、6379。

**RabbitMQ**

- **端口只开两个**：**5672**（AMQP 协议，Java 应用连它）、**15672**（Web 控制台）。参考文里其余端口本项目用不到所以没映射 —— 4369/25672 是组集群用的、5671 是 AMQP over TLS、61613/1883 是 STOMP/MQTT 协议。
- **账号不是 guest**：guest 默认只能从本机登录，宿主机上的 Java 进程连不上。compose 启动时用 `RABBITMQ_DEFAULT_USER/PASS` 创建 `gulimall` 用户（同时不再创建 guest）。
- ⚠️ **两处凭据必须一致**：`config/docker-compose.env` 的 `RABBITMQ_USERNAME/PASSWORD`（容器**建用户**用）和 `config/common.yaml` 的 `RABBITMQ_*`（Java **连接**用），不一致就是客户端 401。
- **首次启动前要授权**（容器内以 uid 999 运行，和 ES 那个 chown 1000 是同一类坑）：
  ```bash
  mkdir -p /mydata/docker/rabbitmq/data
  chown -R 999:999 /mydata/docker/rabbitmq/data
  ```
- **数据目录**：`/mydata/docker/rabbitmq/data`（队列、消息、用户都在里面，删容器不丢）。故意**没挂日志目录** —— 镜像日志走 stdout，`docker compose logs -f rabbitmq` 能看。
- **Java 侧不用配**：连接信息已统一放在 `config/common.yaml` 的 `spring.rabbitmq.*`（含发送确认 publisher-confirm/returns）；用到时只需给模块加 `spring-boot-starter-amqp` 依赖，`acknowledge-mode`/`prefetch` 这类跟业务相关的配置再写到模块自己的 yaml 里。
- **防火墙**：放行 5672、15672。
- ⚠️ **别用 `RABBITMQ_ENABLED_PLUGINS` 环境变量启用插件**：它要求 broker 进程（uid 999）去写 `/etc/rabbitmq/enabled_plugins`，写不进去就启动失败（`failed_to_update_enabled_plugins_file`）。compose 里改成挂一个**可写**的清单文件 `rabbitmq/conf/enabled_plugins`（内容 `[rabbitmq_management,rabbitmq_delayed_message_exchange].`）。
- ⚠️ **磁盘水位**：RabbitMQ 默认要求「剩余磁盘 ≥ 机器内存」（这台约 7.6G，而 `/` 只剩 4.3G）→ 会一直刷 `disk_almost_full` 告警，**告警期间生产者被阻塞、消息发不进去**。已用 `rabbitmq/conf/rabbitmq.conf` 里的 `disk_free_limit.absolute = 1GB` 覆盖。
- 💡 **延迟队列（订单超时自动取消要用）**：官方镜像**不带** `rabbitmq_delayed_message_exchange` 插件。compose 里已经挂好插件目录、也配好了启用它的环境变量（`RABBITMQ_ENABLED_PLUGINS`，注意里面**必须带上 `rabbitmq_management`**，否则控制台 15672 打不开），你只需要放文件 + 重建容器：
  1. 下载地址：<https://github.com/rabbitmq/rabbitmq-delayed-message-exchange/releases> → 选 **v3.13.0**，拿到的是 **`rabbitmq_delayed_message_exchange-3.13.0.ez`**；
  2. ⚠️ **别下错**：`rabbitmq-server-3.13.x.zip` 是 **RabbitMQ 服务端包**，不是插件 —— RabbitMQ 只认 `.ez`，`.zip` 会被当无效插件忽略；
  3. 把 `.ez` 放进 `/mydata/docker/rabbitmq/plugins/`（**文件名必须与 compose 里挂载的那行完全一致**），然后 `docker compose up -d rabbitmq` 重建；
  4. 验证：控制台 **Exchanges → Add a new exchange**，Type 下拉里能看到 **`x-delayed-message`** 就成了。

  ⚠️ **compose 里挂的是「单个 .ez 文件」，不是整个 `plugins` 目录** —— 镜像自带的插件（RabbitMQ 服务端自己的 `.beam` 模块）就住在容器的 `/plugins` 里，挂整个目录会把它们全盖掉，容器启动即崩、无限重启，日志是：
  `Error! Failed to load module 'rabbit' because it cannot be found`。换插件版本时，compose 那行的文件名要一起改。

  （这也是**选 3.13 而不是 4.x** 的原因：该插件 3.13 有现成版本。）

**RocketMQ**

> **当前业务模块没有使用 RocketMQ**（只部署、不参与业务）。compose 里三个 RocketMQ 容器已设置 `restart: "no"`，**不会随 Docker/开机自启**；需要测试时手动启动：
> ```bash
> cd /mydata/docker
> docker compose --env-file /mydata/app/config/docker-compose.env start rocketmq-namesrv rocketmq-broker rocketmq-dashboard
> ```
> 停止：`docker compose ... stop rocketmq-namesrv rocketmq-broker rocketmq-dashboard`

> **和 RabbitMQ 的分工**：两者都是消息队列，RocketMQ 更偏"交易链路"——订单、支付、库存这类对可靠性、顺序性要求高的场景，原生支持**事务消息、延迟消息、顺序消息**。项目里两个 MQ 并存，方便对比两套方案。术语人话版见 `gulimall-rabbitmq/RocketMQ与Kafka实战指南.md`。

- **三个容器**：
  - `rocketmq-namesrv` —— NameServer，相当于**电话簿**：只存"某个 Topic 的 Broker 在哪"，不存消息；
  - `rocketmq-broker` —— Broker，相当于**邮局**：真正存消息、转消息；
  - `rocketmq-dashboard` —— 网页控制台。
- **端口**：

  | 端口 | 用途 |
  |---|---|
  | `9876` | NameServer，客户端第一步连它拿 Broker 地址 |
  | `10911` | Broker 主端口，真正收发消息走这里 |
  | `10909` | Broker VIP 通道（老客户端会走），保留 |
  | `8180` | Dashboard |

- **数据目录**：`/mydata/docker/rocketmq/broker/store`（CommitLog、ConsumeQueue、索引都在里面，**删了消息全没**）。
- ⚠️ **磁盘**：消息默认保留 72 小时，CommitLog 按 1GB 一个文件滚动。这台机器 `/` 只剩几 G，测试时用 `du -sh /mydata/docker/rocketmq/broker/store` 盯一下；不够就把 `broker.conf` 的 `fileReservedTime` 调小（单位小时）。
- **日志目录**：`/mydata/docker/rocketmq/broker/logs/rocketmqlogs/broker.log`、`/mydata/docker/rocketmq/namesrv/logs/rocketmqlogs/namesrv.log`。镜像把日志写**文件**，`docker compose logs` 只能看到启动阶段，排查看文件。

⚠️ **首次启动前有两个必做项**：

**① 建目录并授权**（镜像内进程以 uid 3000 运行）：

```bash
mkdir -p /mydata/docker/rocketmq/namesrv/logs \
         /mydata/docker/rocketmq/broker/logs \
         /mydata/docker/rocketmq/broker/store
chown -R 3000:3000 /mydata/docker/rocketmq
```

不做的报错：容器退出码 **253**，或日志里 `Permission denied` 写 `store` / `logs`。

**② 两个配置文件必须先在宿主机存在**（compose 里已挂载）：

| 文件 | 作用 |
|---|---|
| `/mydata/docker/rocketmq/broker/conf/broker.conf` | 模板：集群名、`brokerIP1` 占位符、刷盘方式、ACL 开关 |
| `/mydata/docker/rocketmq/broker/conf/plain_acl.yml` | ACL 1.0 账号清单（accessKey/secretKey） |

> ⚠️ 文件不存在时 Docker 会把挂载点建成**同名目录**，Broker 读配置直接报错（和 ES/Kibana/nginx 同一个坑）。

⚠️ **`brokerIP1` 不能写死 IP**：模板里是 `__BROKER_IP__`，compose 启动时用 `VM_HOST_IP` 替换后生成 `/home/rocketmq/broker.conf`。
Broker 会把这个地址告诉客户端，客户端按它**直连**。写成 `127.0.0.1` / `localhost` / 容器名，表现是**Dashboard 一切正常，但 Java 端发消息一直超时**——这是 RocketMQ 最难查的坑。换机器、换 IP 时只改 `config/docker-compose.env` 的 `VM_HOST_IP`，然后重建 broker。

**ACL 认证**：

- `broker.conf` 里 `aclEnable = true`，账号在 `plain_acl.yml`：
  ```yaml
  accounts:
    - accessKey: gulimall
      secretKey: <与 docker-compose.env 一致>
      admin: true
  ```
- ⚠️ **三处凭据必须完全一致**，少一处就是认证失败：

  | 位置 | 谁用 |
  |---|---|
  | `deploy/rocketmq/broker/conf/plain_acl.yml` | **Broker** 校验 |
  | `config/docker-compose.env` 的 `ROCKETMQ_ACCESS_KEY/SECRET_KEY` | **Dashboard** 连接 |
  | `config/common.yaml` 的 `ROCKETMQ_ACCESS_KEY/SECRET_KEY` | **Java 客户端** 连接 |
- ⚠️ **仓库里的 `plain_acl.yml` 是占位符**（`CHANGE_ME_SAME_AS_DOCKER_COMPOSE_ENV`），部署时必须在服务器上把它改成 `config/docker-compose.env` 里的真实值：
  ```bash
  vi /mydata/docker/rocketmq/broker/conf/plain_acl.yml
  ```
  `plain_acl.yml` 支持**热加载**，改完直接生效，不用重启 broker；`broker.conf` 模板或 `VM_HOST_IP` 改完要 `docker compose up -d rocketmq-broker` 重建。
- ⚠️ **为什么固定 5.3.2，不升 5.3.4**：5.3.3 起官方**删除了 ACL 1.0**（`aclEnable` + `plain_acl.yml`），而 `rocketmq-spring-boot-starter` 2.3.x 用的是 **Remoting 协议 + ACL 1.0**。升到 5.3.3+ 要么改用 gRPC 客户端（`rocketmq-v5-client-spring-boot-starter`），要么关 ACL。5.3.2 是最后一个两者都支持的版本。
- 纯内网临时不想要账号密码：把 `broker.conf` 的 `aclEnable` 改成 `false`，同时删掉 Dashboard 的 `-Drocketmq.config.accessKey/secretKey`，再重建 broker + dashboard。
- 💡 在容器里用 `mqadmin` 时，ACL 凭据要放在 `/home/rocketmq/rocketmq-5.3.2/conf/tools.yml`（两行：`accessKey` / `secretKey`），否则同样会被 Broker 拒绝；日常建 Topic、查消息用 Dashboard 更省事。

**Java 侧**（按模块引入，**不要加进 `gulimall-common`**，否则所有模块都会被拉起一套生产/消费客户端）：

1. 要用 MQ 的模块 pom 里加：
   ```xml
   <dependency>
       <groupId>org.apache.rocketmq</groupId>
       <artifactId>rocketmq-spring-boot-starter</artifactId>
       <version>2.3.6</version>
   </dependency>
   ```
2. 模块自己的 `application.yaml` 里补**生产者组**（消费者组写在 `@RocketMQMessageListener` 上）：
   ```yaml
   rocketmq:
     producer:
       group: gulimall-order-producer
   ```
3. ⚠️ **配置前缀是 `rocketmq`，不是 `spring.rocketmq`**（RabbitMQ 才是 `spring.rabbitmq`）。连接地址和 ACL 凭据已统一放在 `config/common.yaml`，模块不用重复配。
4. 发消息注入 `RocketMQTemplate`；收消息用 `@RocketMQMessageListener(topic = "...", consumerGroup = "...")` 实现 `RocketMQListener`。

**Dashboard**：`http://<服务器IP>:8180`

- 镜像 2.1.0，容器内监听 **8082**（compose 用 `SERVER_PORT=8082` 固定），宿主映射 8180。
- 连接地址和 ACL 账号通过 `JAVA_OPTS` 传入；**改密码时这里会跟着 `docker-compose.env` 变，不用单独改 compose**。
- 集群页能看到 `DefaultCluster` / `broker-a`，说明 NameServer → Broker → 客户端这条链路通了。页面空白先看 broker 日志。

**防火墙**：放行 **9876、10909、10911、8180**。

**验证**：

```bash
cd /mydata/docker
docker compose ps

# Broker 是否启动成功（看到 "boot success" 即成功）
tail -f /mydata/docker/rocketmq/broker/logs/rocketmqlogs/broker.log

# 控制台
curl -I http://<your-vm-host-ip>:8180/
```

> 💡 第一次发消息前，Topic 会被自动创建（`autoCreateTopicEnable = true`，开发期方便）。生产环境建议在 `broker.conf` 里改成 `false`，改为在 Dashboard 或 `mqadmin` 里显式创建，避免拼错 Topic 名时悄悄造出一堆垃圾 Topic。

### 5. MinIO（对象存储）

**干什么的**：图片、文件都存这里。项目上传图片走它，页面展示图片也走它。

- **镜像 / 地址**：`minio/minio:latest`；应用连接用 `http://<服务器IP>:19000`（API 端口），网页控制台是 `19001`。
- **数据目录**：`/mydata/docker/minio/data`。
- **首次使用**：登录控制台建一个 bucket，名字用 `gulimall`（代码里就是这么配的）。
- ⚠️ **部署前必须改掉占位密码**：compose 里 `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` 只是占位（长度 ≥8），别用 `minioadmin` 这种弱口令。
- ⚠️ 密码**只在第一次启动、初始化数据目录时生效**；之后改环境变量没用，要清掉 `/mydata/docker/minio/data` 重建（会丢数据）。

> **以后扩容**：服务器 ≥4 台时可以组分布式 MinIO（数据冗余）。多节点命令已注释在 compose 的 minio 服务里，替换掉单机 `command` 即可。文档：<https://min.io/docs/minio/linux/operations/install-deploy-manage/deploy-minio-multi-node.html>

### 6. Elasticsearch（商品搜索）

**干什么的**：给商品做全文检索——用户搜"小米手机"，能快速找出相关商品。

⚠️ **首次启动前有三个必做项，少一个容器就起不来**：

**① 先建好配置文件** `/mydata/docker/es/config/elasticsearch.yml`：

```yaml
network.host: 0.0.0.0                    # 监听容器内所有网卡，宿主 9200 端口映射才通
discovery.type: single-node              # 单节点模式
xpack.security.enabled: true             # 开密码认证（账号 elastic）
xpack.security.http.ssl.enabled: false   # 关 HTTP 层 SSL，用 http + 账号密码即可连
```

> ⚠️ 文件不存在时，Docker 会把挂载点建成一个**同名目录**，ES 读配置直接报错。
> ⚠️ 挂载是**覆盖**关系：镜像自带的同名文件被整个替换，所以第一行 `network.host` **必须保留**——删了它 9200 会退回只监听回环地址，宿主的端口映射直接失效。
> ⚠️ 改完要 `docker compose restart elasticsearch` 才生效（ES 只在启动时读配置）。

**② 目录授权**（ES 容器内以 uid 1000 运行）：

```bash
mkdir -p /mydata/docker/es/config /mydata/docker/es/data /mydata/docker/es/plugins /mydata/docker/kibana/config
chown -R 1000:1000 /mydata/docker/es/config /mydata/docker/es/data /mydata/docker/es/plugins /mydata/docker/kibana/config
```
不做的报错：`AccessDeniedException: /usr/share/elasticsearch/data`。

**③ 宿主机内核参数**：

```bash
sudo sysctl -w vm.max_map_count=262144
# 永久生效：写进 /etc/sysctl.conf 后执行 sysctl -p
```
不做的报错：`max virtual memory areas vm.max_map_count [65530] is too low`。

**其他要点**：

- **内存必须限制**：`ES_JAVA_OPTS: -Xms512m -Xmx512m`（配在 compose 环境变量里，yml 里写无效）。不限制的话 ES 默认吃掉宿主机一半内存，会把 MySQL / Nacos 挤爆。两个值必须相等；服务器内存充足可调到 `1g`。
- **认证方式**：开了密码认证但关了 HTTP SSL，所以客户端用 **http 明文 + Basic Auth** 即可，不用配证书。纯内网想免认证，把 yml 里 `xpack.security.enabled` 改成 `false` 并删掉 compose 的 `ELASTIC_PASSWORD` 后重建。
- ⚠️ **改 `ELASTIC_PASSWORD` 要同步改两处**：ES 的 healthcheck 探测命令、各业务模块配置。漏改 healthcheck 会让 ES 永远 `unhealthy`，Kibana 永远起不来。
- ⚠️ 这个密码和 MinIO 一样，**只在首次启动时生效**，后续改环境变量无效（需清 `/mydata/docker/es/data` 重建，会丢数据）。

### 7. Kibana（ES 的网页控制台）

**干什么的**：用浏览器查看、搜索 ES 里的数据。

- ⚠️ **版本必须与 ES 完全一致**（都是 8.18.8），错开会导致 Kibana 连不上 ES 并反复重启。
- ⚠️ **两套账号别搞混**：

  | 用途 | 账号 | 说明 |
  |---|---|---|
  | Kibana 服务端连 ES | `kibana_system` | 内置用户，**默认没有密码**，首次使用前要先设一个 |
  | 浏览器登录 Kibana 界面 | `elastic` | `kibana_system` 权限不够，登不进 UI |

  Kibana 8.x 启动时会校验，用 `elastic` 超级用户直接 FATAL 退出（`value of "elastic" is forbidden`）。给 `kibana_system` 设密码：
  ```bash
  curl -u elastic:<ELASTIC_PASSWORD> -H 'Content-Type: application/json' \
    -X POST 'http://<服务器IP>:9200/_security/user/kibana_system/_password' \
    -d '{"password":"<给 kibana_system 设的密码>"}'
  # 返回 {} 即成功
  ```

- **配置文件** `/mydata/docker/kibana/config/kibana.yml`：ES 连接（hosts + 账号密码）、三个加密密钥、界面语言都在这里；compose 的 `environment` 只留 `TZ`。
  - ⚠️ 文件必须**先在宿主存在**再启动，否则 Docker 会把挂载点建成同名目录，Kibana 读配置报错。
  - ⚠️ **三个 `xpack.*.encryptionKey` 是生产必配项**。不配的话 Kibana 每次启动随机生成，后果是**重启后已登录会话全部失效**，且告警、动作等依赖加密存储的功能被禁用。重新生成：`docker compose exec kibana bin/kibana-encryption-keys generate`。
  - ⚠️ 这三个 key **泄露 = 加密数据可被解密，丢失 = 数据无法还原**，切勿提交到公开仓库；生产环境每个实例各自独立生成。
  - 改完要 `docker compose restart kibana`。
  - 生产环境应给 Kibana 和 ES 都配上 TLS，并把 `xpack.security.secureCookies` 设为 `true`——当前走 http 明文，该值只能为 `false`，启动日志会有一条 `Session cookies will be transmitted over insecure connections` 告警。

### 8. IK 中文分词器（商品搜索必备）

**为什么要装**：ES 自带的按空格切词，中文商品名"小米手机"会被切成一个个单字，搜不出东西。

**下载**：<https://github.com/infinilabs/analysis-ik/releases> → 选**与 ES 版本完全一致**的包（`elasticsearch-analysis-ik-8.18.8.zip`）。

⚠️ **三个必须遵守的点**——ES 会把 `plugins/` 下**每一个文件和子目录**都当成独立插件扫描，只要有一个不合格就 fatal 退出：

1. `plugins/` 根目录下**只能有插件子目录**，不能有散落文件（解压漏写 `-d` 会把 `config/` 摊在根目录 → `Plugin [config] is missing a descriptor properties file`）。
2. **zip 包本身绝不能留在 `plugins/` 下**（→ `Plugin [xxx.zip] is …`）。
3. **必须解压到独立子目录，且不能再多套一层** —— `plugin-descriptor.properties` 要直接位于子目录下。

```
✅ 正确                        ❌ 漏写 -d                  ❌ zip 没解压
plugins/                       plugins/                    plugins/
└── analysis-ik/               ├── config/                 └── xxx.zip
    ├── plugin-…properties     ├── xxx.jar
    ├── xxx.jar                └── plugin-…properties
    └── config/
```

**安装**：

```bash
cd /mydata/docker/es/plugins

# 解压到独立子目录（别在根目录直接解压）
mkdir -p analysis-ik
unzip -o elasticsearch-analysis-ik-8.18.8.zip -d analysis-ik

# 检查：应【直接】看到 plugin-descriptor.properties / *.jar / config
# 若多套了一层，把内容提上来：
#   mv analysis-ik/elasticsearch-analysis-ik-8.18.8/* analysis-ik/
#   rmdir analysis-ik/elasticsearch-analysis-ik-8.18.8
ls analysis-ik

# zip 移出 plugins 目录（关键）
mv elasticsearch-analysis-ik-8.18.8.zip ..

chown -R 1000:1000 .
docker compose restart elasticsearch
```

> 提示 `unzip: command not found` 时：`dnf install -y unzip`。

**验证**（返回的不是单字即成功）：

```bash
curl -u elastic:<ELASTIC_PASSWORD> -H 'Content-Type: application/json' \
  -X POST 'http://<服务器IP>:9200/_analyze' \
  -d '{"analyzer":"ik_max_word","text":"小米手机性价比很高"}'
```

> ⚠️ IK 版本与 ES 不一致会**直接导致 ES 启动失败**。装错版本先删掉 `/mydata/docker/es/plugins/analysis-ik` 再重装。

**远程词库**（`/mydata/docker/nginx/html/es/fenci.txt`）：一行一个词，**编码必须 UTF-8**（错了变乱码、分词静默失效）。
把它的 URL 填进 IK 的 `IKAnalyzer.cfg.xml`：

```xml
<entry key="remote_ext_dict">http://<your-vm-host-ip>/es/fenci.txt</entry>
```

> ⚠️ 这里必须写**服务器 IP**，不能写域名 —— 拉词库的是 ES 容器，它没配 hosts，解析不了域名。

### 9. Nginx（统一入口）

**干什么的**：对外只开 80 一个端口，按**访问的域名**把请求分发给不同的后端。浏览器必须用域名访问才能命中对应的站点。

**转发规则**：

| 域名 / 路径 | 转发到 | 用途 |
|---|---|---|
| `gulimall.vip/` | `host.docker.internal:10000` | 商城前台页面（`gulimall-product` 用 Thymeleaf 渲染，不是静态文件） |
| `gulimall.vip/gulimall/**` | 网关 `:88` | 所有微服务接口，路径原样透传 |
| `gulimall.vip/es/fenci.txt` | 本地静态文件 | IK 远程分词词库（IK 每分钟自动拉） |
| `*/oss/**`（gulimall / admin / search 三个站点） | MinIO `minio:9000` | **数据库里存的图片地址就是这种根相对路径** |
| `search.gulimall.vip/` | `host.docker.internal:12000` | 搜索页（`gulimall-search` 渲染） |
| `admin.gulimall.vip/` | 本地静态 `html/admin/` | 管理端页面（Vue 打包产物） |
| `admin.gulimall.vip/gulimall/**` | 网关 `:88` | 管理端所有接口 |
| `img.gulimall.vip/**` | MinIO `minio:9000` | 图片独立直链，与上面的 `/oss/**` 是同一个对象 |
| `*/gulimall/agent/**`（8 个站点） | 网关 `:88` | AI 聊天、SSE 流式、图片、历史、设置；由 `ai-widget.inc` 统一处理 |

> **AI 助手相关的三个必须点**：
> 1. `ai-widget.inc` 必须在 `gulimall.vip`、`admin.gulimall.vip`、`search`、`auth`、`cart`、`order`、`seckill`、`member` 八个 server 块里 `include`；
> 2. `location ^~ /gulimall/agent/` 的 `^~` 不能删，否则会被静态资源正则 `\.(js|css)$` 抢走，聊天窗 JS/CSS 变成 404；
> 3. SSE 必须 `proxy_buffering off; gzip off; proxy_read_timeout 300s;`，否则前端会等 AI 全部生成完才一次性收到。
>
> 网关侧 `application-gateway.yaml` 需要 `agent-route`（`Path=/gulimall/agent/**`、`StripPrefix=1`、`order: 7`），并且要小于 `admin_route` 的 99。网关实际优先读 Nacos 的 `application-gateway.yaml`，改完本地文件要同步发布到 Nacos。

**目录结构**：

```
/mydata/docker/
├── docker-compose.yaml
└── nginx/
    ├── conf/
    │   ├── nginx.conf              ← 主配置（worker/gzip/日志格式…）
    │   └── conf.d/
    │       ├── gulimall.conf       ← 站点配置（4 个域名站点）
    │       ├── ai-widget.inc       ← AI 聊天窗 / SSE 反向代理，被 8 个站点 include
    │       ├── location-proxy.inc  ← /location/ 逆地理代理
    │       └── img-proxy.inc       ← /oss/ 图片转发片段，被各站点 include
    ├── html/                       ← 静态资源根目录
    │   ├── index/                  ← ⚠️ 目录名是 index 而非 product，原因见下
    │   ├── item/                   ← 商品详情页 item.html 的静态资源
    │   ├── search/                 ← 搜索页的静态资源
    │   ├── admin/                  ← 管理端 dist（renren-fast-vue 构建产物）
    │   ├── coupon/ member/ order/ ware/   ← 预留
    │   └── es/fenci.txt            ← IK 词库（一行一个词，UTF-8）
    └── certs/                      ← HTTPS 证书（先预留，可空目录）
```

```bash
mkdir -p /mydata/docker/nginx/conf/conf.d /mydata/docker/nginx/certs
mkdir -p /mydata/docker/nginx/html/{es,admin,index,item,search,coupon,member,order,ware}
```

> ⚠️ `nginx.conf`、`gulimall.conf`、`img-proxy.inc` 三个文件**必须在启动前手工创建**——文件不存在时 Docker 会把挂载点建成同名**目录**，nginx 读配置直接报错（和 ES、Kibana 那个坑一样）。
> 💡 空目录没关系：静态资源在磁盘上找不到时，`try_files` 会自动回退给对应的 Java 服务，不会 404。

**主配置要点**（完整内容见 `/mydata/docker/nginx/conf/nginx.conf`）：

| 配置 | 值 | 作用 |
|---|---|---|
| `user` / `worker_processes` | `nginx` / `auto` | 运行用户；工作进程数 = CPU 核数 |
| `access_log` / `error_log` | `/var/log/nginx/*.log` | 镜像预置的软链接转到容器 stdout/stderr，`docker compose logs -f nginx` 能看到 |
| `sendfile` / `tcp_nopush` | `on` | 零拷贝 + 攒包发送，优化静态文件传输 |
| `client_max_body_size` | `20m` | 默认只有 1m，后台提交表单经网关会 **413** |
| `gzip` + `gzip_types` | 开，仅文本类 | 图片/视频本身已压缩，压了白费 CPU |
| `include /etc/nginx/conf.d/*.conf` | **放最后** | 引入站点配置；它引用的文件要用到上面定义的 `log_format` |

**站点配置结构**（完整内容见 `/mydata/docker/nginx/conf/conf.d/gulimall.conf`）：

```
upstream gulimall_gateway        ← 后端"服务池"（网关）

server  (gulimall.vip)           ← 站点一：主站
  ├── location /gulimall/        → 网关（原样透传）
  ├── location /es/              → 本地静态（IK 词库，禁缓存）
  ├── location ~* \.(js|css|…)   → 本地静态 html/ 下 + try_files 回退
  ├── location @backend          → host.docker.internal:10000（上一条找不到时的回退）
  └── location /                 → host.docker.internal:10000（product，Thymeleaf）

server  (admin.gulimall.vip)     ← 站点二：管理端
  ├── location /gulimall/        → 网关
  └── location /                 → 本地静态 html/admin/ + history 回退

server  (img.gulimall.vip)       ← 站点三：图片
  └── location /                 → minio:9000（只放行 GET/HEAD）

server  (search.gulimall.vip)    ← 站点四：搜索页
  ├── root                       → html/（★特例，见下）
  ├── location ~* \.(js|css|…)   → 本地静态 + try_files 回退
  └── location /                 → host.docker.internal:12000（search，Thymeleaf）
```

几个关键点：

- **`upstream` 里用 `host.docker.internal`** —— 网关跑在宿主机上（Java 进程，不在容器里），用 hosts 记录而不是域名，好处是**网关没启动时 nginx 也能正常启动**（upstream 里域名解析失败会导致 nginx 起不来）。
- **`/gulimall/` 不做 rewrite** —— 网关路由本来就是按 `/gulimall/xxx/**` 配的，路径完全对齐，少一层改写少一个出错点。
- **正则 location 必须停用** —— 正则（`~*`）在 nginx 里优先于前缀匹配，留着会把 `/static/`、`/assets/` 当本地文件找（404），而不转发给 product。

**★ 目录特例：product 和 search 的 `root` 为什么是 `html/`**

这两个模块的页面引用**自带模块名前缀**（`index/css/…`、`item/img/…`、`./search/css/…`）；
其他模块（coupon / member / order / ware）则**不带**前缀（`css/…`、`img/…`）。

**相对路径是相对「页面 URL」解析的**，不是相对文件在磁盘上的位置：

```
页面 URL     http://gulimall.com/          ← 页面在根路径下
HTML 引用    index/css/swiper-3.4.2.min.css
解析成       http://gulimall.com/index/css/swiper-3.4.2.min.css
              ↓  nginx root 指到 html/（不是 html/product）
磁盘         /usr/share/nginx/html/index/css/swiper-3.4.2.min.css   ✓ 命中
```

> ⚠️ **带前缀的前提：页面 URL 必须在根路径下**（如 `/item.html`）。一旦映射到子路径（如 `/product/item.html`），
> `item/img/x.jpg` 会被解析成 `/product/item/img/x.jpg` —— 图片全裂。所以 item.html 的 Controller
> 要用 `@GetMapping("/item.html")`，别写 `/product/item.html`。
>
> 📌 **两种约定不能混**，混了必然 404。

**配置 hosts**（⚠️ **访问端必做**）：

Nginx 靠 `server_name` 区分站点，所以**浏览器必须用域名访问**。在**访问端**的 hosts 文件里加四行：

```
<your-vm-host-ip>  gulimall.com
<your-vm-host-ip>  admin.gulimall.com
<your-vm-host-ip>  img.gulimall.com
<your-vm-host-ip>  search.gulimall.com
```

| 系统 | hosts 文件路径 |
|---|---|
| Windows | `C:\Windows\System32\drivers\etc\hosts`（需**管理员权限**才能保存） |
| Linux / macOS | `/etc/hosts`（需要 `sudo`） |

> 改完**立即生效**，不用重启浏览器（Windows 上若没生效，执行 `ipconfig /flushdns`）。
> 如果用 IP 访问，Host 头里是 IP，四个 `server_name` 都不匹配 —— 会落到第一个 server 块，管理端、图片站、搜索页就永远访问不到。
> **生产环境**不用改 hosts，配真实 DNS 解析即可。

> ⚠️ **`gulimall.com` 是真实注册的公网域名**，配 hosts 时有两个连带坑：
> 1. **本机代理工具（Clash / Mihomo 等）如果不读系统 hosts**，会把域名解析到公网那台同名服务器 → 关闭它的「DNS 覆写」，或加直连规则。
> 2. **浏览器可能缓存了该域名的 HSTS**（曾经访问过公网那台），导致 `http://` 被强制升成 `https://` → 到 `edge://net-internals/#hsts` 删除。
>
> 详细步骤见「三、常见问题」。

**验证**：

```bash
curl -I http://gulimall.com/                                    # ① 主站（502 = product 没起）
curl    http://gulimall.com/es/fenci.txt                        # ② IK 词库
curl -I http://gulimall.com/gulimall/product/brand/list         # ③ 接口反代（502 = 网关没起）
curl -I http://search.gulimall.com/list.html                    # ④ 搜索页
curl -I http://admin.gulimall.com/                              # ⑤ 管理端
curl -I http://gulimall.com/oss/gulimall/brand/.../xxx.png      # ⑥ 图片（库里存的就是这个路径）
```

**看日志**：`docker compose logs -f nginx`

**上 HTTPS 前必须知道的两件事**：

1. 证书只装在 Nginx 这一处，后端继续走内网 http —— 这正是统一入口的价值。
2. 上 HTTPS 后，各业务模块的 `spring.elasticsearch.uris`、MinIO 的 endpoint **不用改**（那是内部服务间调用，走内网地址）。只有**前端和浏览器**访问的地址（API 域名、图片域名）需要改成 `https://`。

### 10. Seata（分布式事务）

**干什么的**：保证“下单 + 锁库存”这类跨服务操作要么都成功、要么都回滚。只管数据库，不管 Redis / MQ。

**服务端**（compose 里已启用）：

- 镜像 `apache/seata-server:2.5.0`，事务服务端口 `8091`。
- ⚠️ 当前 JDK8 镜像不带 Servlet 容器；`seata-config/application.yml` 里已加 `spring.main.web-application-type: none`，只启动 TC（8091），**不启动 7091 控制台**。需要控制台就改用 `apache/seata-server:2.5.0.jdk17` / `2.5.0.jdk21` 镜像，或从镜像里拷官方默认 `application.yml`。
- ⚠️ `SEATA_IP` 必须是宿主机的**局域网 IP**（compose 用 `VM_HOST_IP`，来自 `config/docker-compose.env`），不能写 `0.0.0.0` 或容器名。Seata 会把这个地址注册到 Nacos，客户端按它直连；写错的表现是 Nacos 里能看到 `seata-server`，但下单时报连接超时。
- 服务端配置在 `deploy/seata-config/application.yml`，由 compose 挂载到容器：
  - `seata.registry` 指向 Nacos（容器内 `nacos:8848`）；
  - `config.type: file`：服务端配置直接读这个文件，不用在 Nacos 里再发一份；
  - `store.mode: file`：开发够用。生产改成 `db`，并在 `seata` 库执行 Seata 服务端脚本（`global_table` / `branch_table` / `lock_table` / `distributed_lock`）；
  - `service.vgroup-mapping.gulimall_tx_group: default`：客户端的 `tx-service-group` 必须和它一致。
- 启动/查看：
  ```bash
  cd /mydata/docker
  docker compose up -d seata-server
  docker compose logs -f seata-server
  ```
  Nacos 控制台 → 服务列表里应出现 `seata-server`；当前配置不启 7091 控制台。
- **防火墙**：放行 `8091`（启用控制台时再放行 `7091`）。

**客户端**（当前只有 order 和 ware 需要）：

- **order**：全局事务发起方（写订单/订单明细，然后 Feign 调 ware）；
- **ware**：写库存的分支参与者（`stock_locked`、工作单、`mq_message`）；
- **member / product / cart / coupon**：在提交订单链路里只读，不需要 Seata；以后如果它们也在同一个全局事务里写库，再把依赖和 `undo_log` 加上。

接入步骤：

1. 两个模块的 `pom.xml` 加（版本由根 pom 的 Spring Cloud Alibaba BOM 管理）：
   ```xml
   <dependency>
       <groupId>com.alibaba.cloud</groupId>
       <artifactId>spring-cloud-starter-alibaba-seata</artifactId>
   </dependency>
   ```
2. 客户端配置已统一放在 `config/common.yaml` 的 `seata.*`：
   - `tx-service-group: gulimall_tx_group`
   - `service.vgroup-mapping.gulimall_tx_group: default`
   - `registry` 指向 Nacos（`seata-server`）
   - `config.type: file`（客户端配置就在 yaml 里）
   - `enable-auto-data-source-proxy: true`（自动代理 DataSource，AT 模式必需）
3. 下单入口 `OrderServiceImpl.submitOrder` 加 `@GlobalTransactional`；ware 的 `lockStock` **只保留本地 `@Transactional`**，不要加全局注解。
4. order → ware 的 Feign 调用由 Seata starter 自动带 `TX_XID` 请求头；确认 ware 的 `undo_log` 存在，否则分支事务无法回滚。
5. 每个参与全局事务的库都要建 `undo_log`：
   ```sql
   -- 分别在 gulimall_oms、gulimall_wms 执行
   source sql/undo_log.sql;
   ```
   ⚠️ 只在一个库建是不够的：`undo_log` 是每个分支数据库各自用的。`gulimall_wms` 里已有旧版表可以继续用；`gulimall_oms` 必须补。

**验证**：

- 正常下一单：Nacos 有 `seata-server`，日志没有 `no available service`；提交后 `undo_log` 记录会被清理。
- 人为让 ware 锁库存失败（库存不足 / 指定仓库不存在）：order 的订单和订单明细、ware 的 `stock_locked` / 工作单 / `mq_message` 都应回滚；Redis 预扣由 order 的 catch 回补。
- ⚠️ **Redis、RabbitMQ、30 分钟库存解锁不归 Seata 管**：这些原有的补偿/对账/延时任务不要因为引入 Seata 就删掉。

### 11. Sentinel（限流 / 熔断）

**部署**：compose 已启用 `sentinel-dashboard`（`bladex/sentinel-dashboard:1.8.9`），控制台 `http://<服务器IP>:8858`，默认账号密码 `sentinel / sentinel`。

**客户端范围**：**gateway、seckill** 完整接入；**order** 只因为 `SeckillFeignClient` 的 Feign fallback 需要 Sentinel starter。不要加进 `gulimall-common`。

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
</dependency>
```

gateway 额外加：

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-alibaba-sentinel-gateway</artifactId>
</dependency>
```

gateway / seckill 还需要规则持久化：

```xml
<dependency>
    <groupId>com.alibaba.csp</groupId>
    <artifactId>sentinel-datasource-nacos</artifactId>
    <version>1.8.9</version>
</dependency>
```

**客户端端口必须唯一**：gateway `8719`、seckill `8720`、order `8721`；以后新增带 Sentinel 的模块也要分配不同端口。

### 11.1 流控 / 熔断 / 热点规则

规则 JSON 已经放在 `deploy/sentinel/`，**Nacos Group 用 `SENTINEL_GROUP`**：

| Data ID | 内容 |
|---|---|
| `gulimall-seckill-sentinel-flow.json` | `seckillOrderSubmit` 50 QPS、`seckillSkuInfo` 200 QPS、`/seckill/sessions`、`/seckill/skus` |
| `gulimall-seckill-sentinel-degrade.json` | `seckillOrderSubmit` 慢调用比例熔断：RT > 800ms、比例 ≥ 50%、熔断 10 秒 |
| `gulimall-seckill-sentinel-param-flow.json` | `seckillSkuInfo` 的 `skuId`（参数下标 1）每 SKU 每秒 20 次 |
| `gulimall-gateway-sentinel-gw-flow.json` | 路由 `seckill-route` 100 QPS |

详细说明和导入方式见 `deploy/sentinel/README.md`。客户端配置在 `config/gulimall-gateway.yaml` / `config/gulimall-seckill.yaml`，通过 Nacos 数据源加载。

### 11.2 异常处理

- **Web 层**：`SentinelWebBlockHandler` 实现 `BlockExceptionHandler`，Web URL 被限流时返回统一 `R` JSON（`14013`）。
- **资源层**：`SeckillOrderController.submit` 用 `@SentinelResource(blockHandler=...)`；`SeckillController.skuInfo` 同时配置 `blockHandler` 和 `fallback`。
- **Feign 层**：
  - order 的 `SeckillFeignClient` 加 `fallback = SeckillFeignClientFallback.class`，秒杀服务不可用时 release/paid 只记日志，不影响订单主流程；
  - seckill 的 `ProductFeignClient` 加 fallback，product 不可用时返回空 skuInfo，页面/上架任务降级；
  - 像 `OrderFeignClient` 的建单/状态查询没有静默 fallback，避免把“order 暂时不可用”误判成“订单不存在”而错误回补库存。

order / seckill 已经配置 `feign.sentinel.enabled: true`。

### 11.3 规则是否永久保存

**Dashboard 默认只存内存，不永久。**

- Dashboard 重启：规则丢失；
- 服务重启：客户端从 Dashboard 收到的规则丢失；
- 本项目已给 gateway/seckill 配 Nacos 数据源，规则从 Nacos 加载，**服务重启不会丢**；
- 在 Dashboard 手动改规则**不会写回 Nacos**，正式修改要改 Nacos 配置；Dashboard 只用于观察和临时验证。

**防火墙**：放行 `8858`；Dashboard 要访问客户端 8719/8720/8721，按需放行。

### 12. Jaeger（调用链，OTLP）

**部署**：compose 已启用 `jaegertracing/jaeger:2.6.0`：

| 端口 | 用途 |
|---|---|
| `16686` | Jaeger Web UI |
| `4318` | OTLP HTTP，Java 客户端用 `http://<服务器IP>:4318/v1/traces` |
| `4317` | OTLP gRPC，供其他客户端使用 |

- 开发环境默认内存存储，重启丢 trace；生产要接 Elasticsearch/Cassandra 或 OTel Collector + 持久化后端。
- **防火墙**：放行 `16686、4317、4318`。

**客户端范围**：gateway、auth-server、member、product、order、ware、coupon、seckill **分别**在各自 `pom.xml` 引入，**不要加进 `gulimall-common`**：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

有 Feign 调用的模块再加：

```xml
<dependency>
    <groupId>io.github.openfeign</groupId>
    <artifactId>feign-micrometer</artifactId>
</dependency>
```

追踪配置已经在各模块的 `config/gulimall-*.yaml`：

```yaml
management:
  tracing:
    sampling:
      probability: ${OTEL_TRACING_SAMPLING}   # 开发 1.0，生产按量调低
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT}
```

`OTEL_TRACING_SAMPLING` / `OTEL_EXPORTER_OTLP_ENDPOINT` 在 `config/common.yaml`，默认发到 Jaeger 的 4318。

**验证**：

1. `docker compose ps` 看到 `sentinel1.8.9`、`jaeger2.6.0` 为 Up；
2. 访问一次网关或秒杀接口，Sentinel 控制台出现对应服务；
3. Jaeger UI 选择 service（`spring.application.name`，如 `gulimall-order`），能看到一条 trace；跨 Feign 的服务在同一个 traceId 下；
4. 网关 WebFlux 的 trace 能串到下游服务。

### 13. Milvus / 向量库（AI 知识库）

**干什么的**：把项目的使用文档切成小段，转成向量存起来。用户问“怎么下单”“怎么发布商品”时，AI 先从 Milvus 里找出最相关的文档片段，再结合实时数据回答。

Milvus 由三个容器组成，缺一不可：

| 容器 | 作用 | 数据目录 |
|---|---|---|
| `milvus-etcd` | 存 Milvus 的元数据（集合、分区、索引定义） | `./milvus/etcd` |
| `milvus-minio` | 存向量索引文件（与业务 MinIO 隔离） | `./milvus/minio` |
| `milvus` | 向量检索引擎，端口 19530 | `./milvus/data` |

**首次启动前必做**：

```bash
mkdir -p /mydata/docker/milvus/etcd /mydata/docker/milvus/minio /mydata/docker/milvus/data
chown -R 1000:1000 /mydata/docker/milvus
```

> Milvus 容器内以非 root 用户运行，数据目录不可写会直接退出。不同镜像版本 uid 可能不同，报 `Permission denied` 时先看 `docker compose logs milvus` 里的实际 uid。

**资源要求**：

- 内存：Milvus standalone + etcd + MinIO 至少约 **2GB**，推荐 3GB 以上；
- 磁盘：文档向量数据不大，但 etcd 和 MinIO 会持续增长，先留 2GB 以上；
- 如果虚拟机内存紧张，把 `config/docker-compose.env` 的 `MILVUS_MEM_LIMIT` 调小并不能降低实际需求，建议先关掉不用的服务（如 Kibana）或扩容。

> 仓库里的 `deploy/milvus/{etcd,minio,data}/.gitkeep` 只是 **0 字节占位文件**：Git 不保存空目录，放一个占位文件保证克隆下来这三个目录存在，避免 Docker 用 root 自动创建导致权限问题。它们不是 Milvus 数据，服务器上目录建好后可以删。

**启动**：

```bash
cd /mydata/docker
docker compose --env-file /mydata/app/config/docker-compose.env up -d milvus-etcd milvus-minio milvus
```

> ⚠️ **必须带 `--env-file`**。Milvus 的 MinIO 账号、密码、root 密码都来自 `config/docker-compose.env`；不带时变量为空，Milvus 启动后会报 “signature does not match”。

**验证**：

```bash
docker compose ps                      # 三个容器都应是 Up
curl -fsS http://<服务器IP>:9091/healthz   # 返回 OK 即健康
docker compose logs -f milvus          # 看到 "Milvus Standalone" 启动成功
```

**gulimall-agent 连接配置**（在 `config/common.yaml`）：

| 键 | 默认值 | 说明 |
|---|---|---|
| `MILVUS_HOST` | `<服务器IP>` | Milvus 所在机器 |
| `MILVUS_PORT` | `19530` | gRPC 端口 |
| `MILVUS_DATABASE` | `gulimall_ai` | 首次启动时 agent 自动创建 |
| `MILVUS_COLLECTION` | `gulimall_knowledge_v1` | 向量集合名 |
| `MILVUS_USERNAME` / `MILVUS_PASSWORD` | `root` / `MILVUS_ROOT_PASSWORD` | 当前 compose 默认开鉴权，必须和 `config/docker-compose.env` 的 `MILVUS_ROOT_PASSWORD` 一致 |

**RAG 文档**：

- 运行时文档在 `gulimall-agent/src/main/resources/rag/*.md`；
- 启动时如果文档内容变化，会自动重新切分、向量化、入库；
- 管理员也可以在后台调用重建接口（需要管理端 token）：

```bash
curl -X POST http://admin.gulimall.vip/gulimall/agent/admin/rag/rebuild \
  -H "token: <管理员token>"
```

> ⚠️ RAG 使用的部署文档是**脱敏版**：密码、Token、内网 IP 已替换为 `******`，避免把凭据送进向量库。原文在 `document/Deploy/Deploy组件一览.md`，供人查看。

**鉴权默认开启（三处必须一致）**：

当前 `deploy/docker-compose.yaml` 已经开启了 Milvus 鉴权。它不是商城用户登录，用户聊天不需要输入密码；是 **gulimall-agent 连接 Milvus 的服务凭据**，agent 每次连接自动携带。

| 位置 | 键 | 作用 |
|---|---|---|
| `config/docker-compose.env` | `MILVUS_ROOT_PASSWORD` | 首次启动时设置 Milvus root 密码 |
| `config/common.yaml` | `MILVUS_USERNAME` / `MILVUS_PASSWORD` | agent 连接时使用 |
| `config/docker-compose.env` | `MILVUS_MINIO_ACCESS_KEY` / `MILVUS_MINIO_SECRET_KEY` | Milvus 自己那个 MinIO 的账号密码（Milvus 内部用） |
| `deploy/docker-compose.yaml` 的 `milvus` 服务 | `MINIO_SECRET_ACCESS_KEY` | ⚠️ Milvus 读的是这个变量名，**不是 `MINIO_SECRET_KEY`**；写错会拿空密码去连，报 signature does not match |

- `MILVUS_ROOT_PASSWORD` 必须等于 `config/common.yaml` 的 `MILVUS_PASSWORD`；
- `MILVUS_MINIO_ACCESS_KEY/SECRET_KEY` 会同时注入 `milvus-minio` 和 `milvus` 两个容器，二者必须由同一份 env 提供；
- ⚠️ Milvus root 密码只在**首次初始化数据目录**时生效；之后再改 `MILVUS_ROOT_PASSWORD` 不会自动改已有数据目录的密码，需要清空 `/mydata/docker/milvus/{data,minio,etcd}` 重新初始化（会丢向量数据，文档在代码仓库，重建即可）。

**备份 / 迁移**：

- 停 Milvus：`docker compose stop milvus milvus-etcd milvus-minio`
- 打包三个目录：`tar -czf milvus-backup.tar.gz /mydata/docker/milvus`
- 恢复时解压回原路径，再启动三个容器。
- 也可以在业务层不备份，迁移后让 agent 重新入库（文档在代码仓库里，重建很快）。

**Milvus 常见问题**：

- **`The request signature we calculated does not match` / `failed to check blob bucket exist [bucket=a-bucket]`**：Milvus 和它自己的 MinIO 账号密码不一致。最常见原因是 `docker compose up` 没带 `--env-file`，`MILVUS_MINIO_ACCESS_KEY/SECRET_KEY` 为空。修复：
  1. 必须用 `docker compose --env-file /mydata/app/config/docker-compose.env ...`；
  2. 先确认 Milvus 连的是**自己的** MinIO，不是业务 MinIO：
     ```bash
     docker compose --env-file /mydata/app/config/docker-compose.env exec milvus env | grep MINIO_ADDRESS
     # 必须是 milvus-minio:9000；如果是 minio:9000，说明连到业务 MinIO 了
     ```
  3. 检查两个容器实际拿到的值：
     ```bash
     docker exec milvus-minio env | grep MINIO_ROOT
     docker compose --env-file /mydata/app/config/docker-compose.env exec milvus env | grep -E 'MINIO_ACCESS_KEY_ID|MINIO_SECRET_ACCESS_KEY'
     ```
     两边必须完全一致；MinIO 镜像如果带 `MINIO_ROOT_USER_FILE`/`MINIO_ROOT_PASSWORD_FILE`，还要确认 `/run/secrets/access_key`、`secret_key` 文件内容与 env 一致（文件优先于 env）。
     ⚠️ Milvus 容器里必须出现 **`MINIO_SECRET_ACCESS_KEY`**；如果只有 `MINIO_SECRET_KEY`，说明 compose 写错了变量名，Milvus 实际用的是空密码，必然签名失败。
  4. 如果之前用错账号初始化过 MinIO，改环境变量不会自动改已有数据目录，需要停掉三个容器并清空 `deploy/milvus/{data,minio,etcd}` 后重新启动（当前刚安装、没有业务数据，清空是安全的）：
     ```bash
     cd /mydata/docker
     docker compose --env-file /mydata/app/config/docker-compose.env rm -sf milvus milvus-etcd milvus-minio
     rm -rf /mydata/docker/milvus/data /mydata/docker/milvus/minio /mydata/docker/milvus/etcd
     mkdir -p /mydata/docker/milvus/data /mydata/docker/milvus/minio /mydata/docker/milvus/etcd
     chown -R 1000:1000 /mydata/docker/milvus
     docker compose --env-file /mydata/app/config/docker-compose.env up -d milvus-etcd milvus-minio milvus
     ```
  5. 不想每次都写 `--env-file` 时，建软链：`ln -sf /mydata/app/config/docker-compose.env /mydata/docker/.env`。
- **刚启动时 `/healthz` 返回 500**：先看 `docker compose ps`，如果 Milvus 还是 `health: starting`，这是正常的，等 1～2 分钟变成 `healthy` 再测；Milvus 的 datacoord/querycoord 启动需要时间。
- **`CREATED` 已经几分钟，但 `STATUS` 只有 `Up 几秒`**：说明容器在**反复重启**，不是启动慢。检查：
  ```bash
  docker inspect -f 'Status={{.State.Status}} ExitCode={{.State.ExitCode}} OOMKilled={{.State.OOMKilled}} RestartCount={{.RestartCount}}' milvus2.5.10
  ```
  - `OOMKilled=true`：2g 的 `MILVUS_MEM_LIMIT` 不够，先停 RocketMQ/Kibana 等不用的容器，再把 `MILVUS_MEM_LIMIT` 调到 3g；
  - `ExitCode=1`：看 `docker logs --tail=300 milvus2.5.10`，多数还是 MinIO 账号/地址问题。
- **账号 env 看起来一致但日志仍报签名错误**：
  1. 确认 `MINIO_ADDRESS` 是 `milvus-minio:9000`，不是业务 `minio:9000`；
  2. 确认 Milvus 和 MinIO 容器时间没有明显偏差：分别执行 `date -u`；
  3. 确认 `milvus/minio` 数据目录不是旧密码初始化留下的（清空重建，见上一条）。
- **`find no available querycoord / datacoord`、`/healthz` 持续 500**：通常不是 Milvus 组件本身坏了，而是上面的 MinIO 鉴权失败导致 datacoord/querycoord 起不来；先修 MinIO 账号，再看日志。
- **容器起来就退出 / 日志 Permission denied**：数据目录没有授权，执行上面的 `chown -R 1000:1000 /mydata/docker/milvus`。
- **19530 连接被拒绝**：先看 `milvus` 是否健康；`milvus-etcd` / `milvus-minio` 没起来时 Milvus 会一直重启。
- **内存不足被 OOM**：`docker stats` 看内存；Milvus 至少需要 2GB，低配机器先停 Kibana 等非必要服务。
- **集合维度不匹配**：`text-embedding-3-small` 是 1536 维，换 embedding 模型必须同时改 collection 维度，最省事的做法是换一个新的 collection 名并重建。
- **RAG 回答不到文档**：确认文档已放进 `src/main/resources/rag/`，重启 agent 或调用重建接口；再检查 `gulimall:ai:rag:indexed` 标记是否已写入 Redis。
- **Milvus 正常但 agent 报“RAG 不可用”**：检查 `MILVUS_HOST` 是否是宿主机的局域网 IP（agent 跑在 Windows 上，不能用 `localhost` 指虚拟机），以及 19530 是否放行。

### 14. 停止 / 卸载

```bash
docker compose down        # 停止并移除容器（保留数据）
docker compose down -v     # 同时删除数据卷（慎用）
```

---

## 三、常见问题

### Nacos

- **启动报 Nacos 连接失败**：确认 `spring.cloud.nacos.server-addr` 指向可达地址；Nacos 3.x 需放行 8848 + 9848。
- **Nacos 容器启动失败**：检查是否配了 `NACOS_AUTH_TOKEN` 等认证变量（2.2.1+ 必须）。

### RabbitMQ

- **客户端 401 / ACCESS_REFUSED**：`config/docker-compose.env`（容器建用户）和 `config/common.yaml`（Java 连接）的账号密码不一致。
- **容器起来就退出**：数据目录没授权 → `chown -R 999:999 /mydata/docker/rabbitmq/data`。
- **控制台打不开**：确认已放行 15672；看日志 `docker compose logs -f rabbitmq`。
- **队列堆积怎么看**：`docker exec -it rabbitmq3.13.7 rabbitmqctl list_queues name messages`。

### RocketMQ

- **Java 客户端发消息一直超时 / 连接失败，但 Dashboard 一切正常**：`VM_HOST_IP` 没配置或配错（`brokerIP1` 会被注入错误地址）。Broker 把这个地址告诉客户端，客户端会照它直连；写 `127.0.0.1` / 容器名就会连错。改 `config/docker-compose.env` 的 `VM_HOST_IP` 后 `docker compose up -d rocketmq-broker`。
- **`AclException: No access key is configured` / 认证失败**：三处凭据不一致 —— `plain_acl.yml`（Broker）、`config/docker-compose.env`（Dashboard）、`config/common.yaml`（Java）。另外仓库里的 `plain_acl.yml` 是占位符，部署时忘了改也会这样。
- **容器起来就退出，退出码 253 / 日志 `Permission denied`**：`./rocketmq/namesrv/logs`、`./rocketmq/broker/logs`、`./rocketmq/broker/store` 没授权。执行 `chown -R 3000:3000 /mydata/docker/rocketmq`。
- **Docker 把 `broker.conf` 或 `plain_acl.yml` 建成了目录**：启动前文件不存在。删掉目录、建好文件再 `docker compose up -d`。
- **Dashboard 集群页空白 / 看不到 broker**：① `JAVA_OPTS` 里 NameServer 地址写错；② broker 没起来（看 `broker.log` 有没有 `boot success`）；③ ACL 账号密码与 `plain_acl.yml` 不一致，Dashboard 连 Broker 被拒。
- **发消息报 `No route info of this topic`**：`autoCreateTopicEnable = false` 时 Topic 必须先手工创建；当前开发配置是 `true`，如果改过就要在 Dashboard → Topic 里先建。
- **升级到 5.3.3+ 后 ACL 不生效**：5.3.3 起删除了 ACL 1.0，`aclEnable` + `plain_acl.yml` 不再支持；要么退回 5.3.2，要么改用 gRPC 客户端 + ACL 2.0。
- **看日志**：`docker compose logs -f rocketmq-broker` 只有启动阶段；完整日志在 `/mydata/docker/rocketmq/broker/logs/rocketmqlogs/broker.log`。

### Sentinel / Seata / Jaeger

- **Sentinel 控制台看不到服务**：先访问一次被限流的接口，控制台才显示该服务。
- **Seata 事务不生效**：检查客户端 `tx-service-group` 与服务端 `service.vgroup-mapping` 是否一致；确认 Seata 已注册到 Nacos。
- **`no available service 'default' found`**：客户端的事务分组在 Nacos 里找不到对应集群。检查 `seata.tx-service-group=gulimall_tx_group`、`vgroup-mapping.gulimall_tx_group=default`，以及服务端 `application.yml` 里的同名映射。
- **Nacos 有 `seata-server`，但客户端连不上 / 下单超时**：`SEATA_IP` 来自 `VM_HOST_IP`，检查 `config/docker-compose.env` 是否是宿主机局域网 IP；并确认 `8091` 已映射、防火墙放行。
- **报 `undo_log` 表不存在**：在对应业务库执行 `sql/undo_log.sql`（`gulimall_oms`、`gulimall_wms` 都要）。
- **ware 的库存没有跟着 order 回滚**：确认 order → ware 的 Feign 带了 `TX_XID` 头、ware 的 DataSource 已被 Seata 代理（`enable-auto-data-source-proxy: true`）、ware 库有 `undo_log`。缺一个都只会回滚 order 自己的库。
- **服务端启动报配置读取失败**：`seata-config/application.yml` 是 `config.type: file`；如果改成 `nacos`，必须先把 Seata 服务端配置发布到 Nacos，否则服务端读不到配置。
- **Redis / MQ 没回滚**：这是正常的，Seata 只管数据库；Redis 回补、库存解锁、MQ 补偿仍然由原有代码/定时任务处理。
- **Jaeger 收不到链路**：确认 `management.otlp.tracing.endpoint` 指向 `http://<服务器IP>:4318/v1/traces`，4318 已放行；生产采样率建议 < 1.0。
- **Sentinel 客户端端口冲突**：同一台机器上多个服务默认都抢 8719；本项目 gateway=8719、seckill=8720，新增带 Sentinel 的模块要各自分配端口。

### MinIO

- **控制台打不开**：确认放行 **19000 + 19001**（9000/9001 在宿主被占用，所以映射到 19000/19001）。
- **登录失败**：检查 `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` 是否改过。账号密码**首次启动时生效**，改密码要 `docker compose down` + 清掉 `/mydata/docker/minio/data` 再启（会丢数据）。
- **Java SDK 连不上**：应用连的是 **19000（API）**，不是 19001；endpoint 写 `http://<IP>:19000`，并确认 bucket 已建、accessKey/secretKey 与 MINIO_ROOT 一致。

### Milvus

- **容器起来就退出 / `Permission denied`**：`mkdir -p /mydata/docker/milvus/{etcd,minio,data}` 后 `chown -R 1000:1000 /mydata/docker/milvus`。
- **19530 连不上**：先 `curl http://<服务器IP>:9091/healthz`；不健康就看 `docker compose logs milvus`，多数是 etcd/minio 没起来或内存不足。
- **agent 报“RAG 不可用 / Milvus 不可用”**：检查 `config/common.yaml` 的 `MILVUS_HOST` 是否是虚拟机局域网 IP；Windows 上的 agent 不能用 `localhost` 指虚拟机。
- **回答总是命中不到文档**：确认 `gulimall-agent/src/main/resources/rag/` 下有 md 文件；重启 agent 或调用 `/gulimall/agent/admin/rag/rebuild`；再看 Redis 是否有 `gulimall:ai:rag:indexed`。
- **换了 embedding 模型后检索报维度错误**：`text-embedding-3-small` 是 1536 维；换模型要改 `MILVUS_COLLECTION` 为新名字并重建，不能直接复用旧集合。
- **Milvus 把内存吃满**：Milvus 至少需要约 2GB；低配虚拟机先停 Kibana 等非必要服务，或用 `docker stats` 确认是不是 Milvus 本身。
- **删了容器数据没了**：Milvus 的数据在 `/mydata/docker/milvus/{etcd,minio,data}`，删容器不会丢；执行 `docker compose down -v` 或手动删这三个目录才会丢。

### Elasticsearch

- **容器启动即退出**：看 `docker compose logs elasticsearch`，多数是这两个原因——① `/mydata/docker/es/data` 没 `chown 1000:1000`；② `vm.max_map_count` 太小。
- **报 `Plugin [xxx] is missing a descriptor properties file`**（`xxx` 可能是 `config`、某个 zip 或 jar 名）：IK 装错了，见 §8 的三个必须点。修复：`cd /mydata/docker/es/plugins && rm -rf ./*` 清空后重装。
- **报 `Plugin [analysis-ik] was built for Elasticsearch version X`**：IK 版本与 ES 不一致，删掉该目录重装对应版本。
- **报 `max virtual memory areas vm.max_map_count too low`**：`sudo sysctl -w vm.max_map_count=262144`（重启失效，需写 `/etc/sysctl.conf`）。
- **内存吃满 / 拖垮其他服务**：确认 compose 里 `ES_JAVA_OPTS` 生效（两值必须相等）。用 `docker stats elasticsearch8.18.8` 观察。
- **curl 访问返回 401**：开了密码认证，需带 `-u elastic:<密码>`；浏览器同理。
- **中文检索被切成单字**：没装 IK，见 §8。
- **Java 客户端报 SSL/handshake 错误**：客户端默认按 https 连。确认 `uris` 写的是 **http://**，或显式设 `spring.elasticsearch.ssl.enabled=false`。

### Kibana

- **起不来、日志报连不上 ES**：① 确认 ES 已 `healthy`（`docker compose ps`）；② `ELASTICSEARCH_HOSTS` 必须是 `http://elasticsearch:9200`（compose 服务名），写 `localhost` 连的是 Kibana 容器自己；③ 用户名必须是 `kibana_system`（不是 `elastic`）且密码正确；④ 版本必须与 ES 完全相同。
- **启动即 FATAL：`value of "elastic" is forbidden`**：Kibana 不允许用超级用户连接，改成 `kibana_system` 并先给该用户设好密码（见 §7），再 `docker compose up -d kibana`。

### Nginx

- **报 `bind() to 0.0.0.0:80 failed (98: Address already in use)`**：宿主 80 被占用。常见原因是之前按教程起过一个 nginx 容器还活着，`docker ps` 查出来后 `docker rm -f` 掉，或改用其他宿主端口。
- **报 `host not found in upstream "host.docker.internal"`**：`extra_hosts` 没生效（需要 Docker 20.10+）。确认 compose 里有 `extra_hosts: - "host.docker.internal:host-gateway"`；老版本 Docker 改用宿主 IP。
- **报 `open() "/etc/nginx/nginx.conf" failed (21: Is a directory)`**：宿主上该路径被 Docker 建成了目录（因为启动时文件不存在）。删掉目录、按 §9 建好文件再启动，`conf.d` 同理。
- **`/gulimall/**` 返回 502**：Nginx 转发成功但网关不可达。确认 `gulimall-gateway` 已在宿主机启动、监听 **88**。
- **`/gulimall/**` 返回 404 而不是 502**：请求没匹配到 `location /gulimall/`，落到 `location /` 当静态文件找了。检查 URL 有没有带 `/gulimall` 前缀——前端的 `baseURL` 也必须是 `/gulimall`。
- **前端页面刷新就 404**：SPA 用了 history 模式，需要 `try_files $uri $uri/ /index.html;` 回退（§9 已配）。
- **页面里的图片全裂（404）**：库 / ES 里存的是根相对路径 `/oss/...`，由 `img-proxy.inc` 转发给 MinIO——**必须经过 Nginx**。常见原因：① 直连后端端口（`localhost:10000`）访问页面，那个端口没有 `/oss/` 路由；② MinIO 没起；③ `/oss/` 被静态资源正则截走——`location ^~ /oss/` 的 `^~` 不能少。
- **`img.gulimall.com` 打不开**：① hosts 没加这一行；② MinIO 没起（502）；③ 图片本身 403 说明桶不是公共读。
- **IK 拉不到词库**：`curl http://gulimall.com/es/fenci.txt` 看能不能拿到文本。注意词库**必须 UTF-8**；`/es/` 已强制禁缓存，若仍是旧词库，检查是否有别的缓存层。

### 域名 / hosts 相关

- **域名打不开、但用 IP 能打开**：hosts 没配或没生效。注意**四个域名都要加**。
- **浏览器打不开 `gulimall.com`（502 / 转圈），但服务器上 `curl` 正常 → 本机代理工具拦了请求**：典型是开了 Clash / Mihomo。**判断方法**：F12 → Network 看那条请求的「远程地址」，若是 `127.0.0.1:7897` 这类本地端口，说明请求根本没出本机。
  - **修法 1（推荐）**：关闭 Clash 的「DNS 覆写」（开启时它用自己的 DNS，不读系统 hosts，会把域名解析到公网那台同名服务器）。
  - **修法 2**：加直连规则，**必须放在 `rules:` 最前面**：
    ```yaml
    rules:
      - DOMAIN-SUFFIX,gulimall.com,DIRECT
      - DOMAIN-SUFFIX,img.gulimall.com,DIRECT
    ```
    改完要**重载配置**（不是只保存）。
  - ⚠️ Clash 开 **TUN 模式**时，Windows 的「代理例外列表」不起作用，只能用上面两种方式。
- **浏览器强制把 `gulimall.com` 跳成 `https://` 然后失败 → HSTS 缓存**：`gulimall.com` 是真实注册的域名，**曾经访问过公网那台**的话，它返回的 `Strict-Transport-Security: max-age=31536000; includeSubDomains` 会被浏览器记住最长 1 年，此后所有 `*.gulimall.com` 都被强制升级为 https，而我们的 nginx 只监听 80 → 必然失败。
  - **判断**：地址栏输 `http://gulimall.com/`，若**自动变成 `https://`** 就是 HSTS。
  - **修复**：打开 `chrome://net-internals/#hsts`（Edge 用 `edge://net-internals/#hsts`）→ 在 **Delete domain security policies** 里删掉 `gulimall.com` 和 `img.gulimall.com` → **完全退出浏览器再重开**（只关标签页不够）。
  - ⚠️ 若用同页的 **Query HSTS/PKP domain** 查出来是 `static_sts_domain`（在浏览器预编译列表里），net-internals 删不掉，只能换域名。
  - **这条和上一条的共同根源**：`gulimall.com` 是真实域名，不配 hosts / 走代理时都会撞上公网那台。**遇到"配置对、curl 通、浏览器不通"，先查这两条。**

### 其他

- **compose 里的相对路径**：compose 文件里所有 `./` 挂载均相对 **compose 文件所在目录**（即 `/mydata/docker/`）。

---

## 四、附录：客户端依赖版本

大部分基础依赖已配入 `gulimall-common/pom.xml`；**Sentinel、调用链、Seata、RocketMQ** 都按模块单独引入，不要加进 common。版本由根 pom 的 Spring Boot / Spring Cloud / Spring Cloud Alibaba BOM 管理：

| 组件 | 依赖坐标 | 版本 |
|---|---|---|
| Nacos 注册 / 配置中心 | `spring-cloud-starter-alibaba-nacos-discovery` / `-config` | 2025.0.0.0（nacos-client **3.0.3**） |
| OpenFeign + 负载均衡 | `spring-cloud-starter-openfeign` | 4.3.3 |
| Sentinel | `spring-cloud-starter-alibaba-sentinel`；gateway 另加 `spring-cloud-alibaba-sentinel-gateway` | 2025.0.0.0（sentinel **1.8.9**）；gateway / seckill，order 仅为 Feign fallback |
| Gateway | `spring-cloud-starter-gateway` | 4.3.x |
| 调用链监控 | `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`（Feign 加 `feign-micrometer`） | Boot 3.5.16 管理 |
| Seata | `spring-cloud-starter-alibaba-seata` | 2025.0.0.0（seata **2.5.0**） |
| 商品全文检索 | `spring-boot-starter-data-elasticsearch` | 3.5.16 管理（elasticsearch-java **8.18.x**） |
| 消息队列（RabbitMQ） | `spring-boot-starter-amqp` | 3.5.16 管理（spring-amqp **3.2.x**） |
| 消息队列（RocketMQ） | `rocketmq-spring-boot-starter` | **2.3.6**（Remoting 客户端；ACL 1.0 要求服务端 ≤ **5.3.2**） |
| AI 对话 / 工具 / RAG | `dev.langchain4j:langchain4j`、`langchain4j-open-ai`、`langchain4j-reactor`、`langchain4j-milvus` | **1.0.1**（reactor/milvus 由 BOM 管理为 **1.0.1-beta6**） |
| Milvus 客户端 | `langchain4j-milvus` 传递的 `io.milvus:milvus-sdk-java` | **2.5.7**，与服务端 Milvus **2.5.x** 配套 |
| 图片对象存储 | `io.minio:minio` | **8.6.0** |

> 版本由 `spring-boot-dependencies 3.5.16` + `spring-cloud-dependencies 2025.0.3` + `spring-cloud-alibaba-dependencies 2025.0.0.0` 统一管理；根 pom 和 `gulimall-common` 都引入了这些 BOM，所以子模块引依赖时不用写版本号。
>
> ⚠️ `rocketmq-spring-boot-starter` **没有**放进 `gulimall-common`：要用 MQ 的模块单独引并写版本号，避免所有模块都被拉起一套生产者/消费者客户端。连接地址与 ACL 凭据已经在 `config/common.yaml` 统一配好。
>
> ⚠️ Seata 也**没有**放进 `gulimall-common`：只给 order / ware 加 `spring-cloud-starter-alibaba-seata`，避免所有模块都去连 Seata Server、都要求建 `undo_log`。
>
> ⚠️ 调用链**没有**放进 `gulimall-common`：只给 gateway、auth-server、member、product、order、ware、coupon、seckill 分别加 `spring-boot-starter-actuator` + `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`，Feign 模块再加 `feign-micrometer`。
>
> ⚠️ Sentinel **没有**放进 `gulimall-common`：gateway（含 `spring-cloud-alibaba-sentinel-gateway`）和 seckill 完整接入；order 只为了 `SeckillFeignClient` 的 Feign fallback 引入 starter。其他模块不要引入。
>
> ⚠️ AI 相关依赖（LangChain4J / Milvus / MinIO）**只放在 `gulimall-agent` 自己的 pom**，没有加进 `gulimall-common`：这些依赖体积大，且只有 AI 模块需要，不能拖累其他业务模块的启动和打包。
>
> ⚠️ `gulimall-agent` 的 AI Key 不写死在代码里：优先读环境变量 `LangChain4J_CloseAI`，其次读 `config/common.yaml` 的 `AI_CLOSEAI_API_KEY`。自定义模型 apiKey 用 `GULIMALL_AI_AES_KEY` 加密；业务模块内部接口用 `GULIMALL_INTERNAL_API_TOKEN` 鉴权。
