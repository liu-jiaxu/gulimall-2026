# 一、Elasticsearch

## 1、Elasticsearch简介

全文搜索属于最常见的需求，开源的 Elasticsearch 是目前全文搜索引擎的首选。它可以快速地储存、搜索和分析海量数据。维基百科、Stack Overflow、Github 都采用它

![image-20211011110540663](ES/image-20211011110540663.png)

文档：

官方文档：https://www.elastic.co/guide/en/elasticsearch/reference/current/index.html

官方中文:https://www.elastic.co/guide/cn/elasticsearch/guide/current/foreword_id.html

社区中文：

https://es.xiaoleilu.com/index.html
http://doc.codingdict.com/elasticsearch/0/

（其中中文文档都是比较旧的版本）

## 2、基本概念

1、Index（索引)：

动词，相当于 MySQL 中的 insert；
名词，相当于 MySQL 中的 Table（注意：不是 Database，见下面第 3 点）

2、Document（文档）

保存在某个索引（Index）下的一个数据（Document），文档是 JSON 格式的，Document 就像是 MySQL 中某个 Table 里面的一行内容

3、Type(类型) —— **已在 7.0 废弃、8.0 彻底移除**

**一个索引就是一张表**，类比关系如下：

| 关系型数据库 | Elasticsearch |
|---|---|
| Database（库） | 用索引名前缀/别名模拟，如 `gulimall_*` |
| Table（表） | **Index（索引）** |
| Row（行） | Document（文档） |
| Column（列） | Field（字段） |

**为什么没有 type？**

ES 底层是 Lucene，而 **Lucene 里一个索引就是一堆文档平铺在一起，字段名是全局的**，没有"分表"这回事。
所以从 7.0 起废弃了 type，8.0 彻底移除 —— **一个索引就是一张表**，需要多张表就建多个索引。

**那 `_doc` 是什么？** 它**不是类型**，只是 URL 里的一个固定占位符，让路径维持
`<索引>/<端点>/<ID>` 三段式的形状。它不出现在 mapping 里，也没有任何字段定义。

![image-20211011110935605](ES/image-20211011110935605.png)

4、为什么ES搜索快？ 倒排索引

![image-20211011111013604](ES/image-20211011111013604.png)

对每条要存储的文档进行分词，然后对每个词和所在的记录进行存储。在搜索时，先搜索含有其关键字的记录，然后对所记录计算相关性得分，得出最终的搜索结果

## 3、Docker安装Elasticsearch

> 本项目统一用 **`/mydata/docker/docker-compose.yaml`** 编排，配置走 `elasticsearch.yml` 文件挂载。
> 完整部署步骤（含 IK 分词器、Kibana、故障排查）见 **`/mydata/docker/DEPLOY.md`**，这里只讲要点。

**1、版本选型（三者必须完全一致）**

| 组件 | 版本 | 理由 |
|---|---|---|
| Elasticsearch | **8.18.8** | 与 Spring Boot 3.5.16 管理的 elasticsearch-java **8.18.x** 客户端配套，两端大版本必须一致 |
| Kibana | **8.18.8** | 与 ES 版本错开会连不上 |
| IK 分词器 | **8.18.8** | 与 ES 版本错开会**导致 ES 启动失败** |

> 三个组件的版本必须**完全一致**。

**2、启动**

```bash
cd /mydata/docker
docker compose up -d elasticsearch   # 只起 ES
docker compose up -d                 # 全部中间件一起起
```

启动**前**必须做的两件事（否则容器起不来）：

```bash
# ① 宿主目录授权：ES 容器内以 uid 1000 运行
mkdir -p ./es/config ./es/data ./es/plugins ./kibana/config
chown -R 1000:1000 ./es/config ./es/data ./es/plugins ./kibana/config

# ② 内核参数：ES 的 mmap 要求，重启后会失效，需写进 /etc/sysctl.conf
sudo sysctl -w vm.max_map_count=262144
```

**3、配置文件（`/mydata/docker/es/config/elasticsearch.yml`）**

8.x 的配置统一写在这个文件里，通过挂载覆盖容器内的同名文件：

```yaml
network.host: 0.0.0.0                    # 监听所有网卡，宿主 9200 端口映射才通
discovery.type: single-node              # 单节点模式，单机部署必配
xpack.security.enabled: true             # 开启密码认证（8.x 默认就开着，生产必须保留）
xpack.security.http.ssl.enabled: false   # 关 HTTP 层 SSL，客户端 http + 账号密码即可连
```

> ⚠️ **挂载是「覆盖」关系**：镜像自带的同名文件会被整个替换。官方镜像原本预置了 `network.host`，
> 所以上面第一行**必须保留** —— 删掉它容器的 9200 会退回只监听回环地址，宿主端口映射直接失效。

`ES_JAVA_OPTS`（JVM 堆内存）和 `ELASTIC_PASSWORD`（初始密码）这两项**只能配在 compose 的环境变量里**，
写进 yml 无效。

**4、访问测试**

8.x 默认开启密码认证，访问必须带账号密码：

```bash
# 命令行
curl -u elastic:<your-password> 'http://<your-vm-host-ip>:9200/_cat/health?v'

# 浏览器：打开 http://<your-vm-host-ip>:9200 ，会弹 Basic Auth 登录框
# 账号 elastic，密码见 compose 的 ELASTIC_PASSWORD
```

**5、安装 Kibana**

Kibana 同样由 compose 编排，配置在 `/mydata/docker/kibana/config/kibana.yml`：

```yaml
server.host: "0.0.0.0"
elasticsearch.hosts: ["http://elasticsearch:9200"]   # 写服务名，不是 localhost
elasticsearch.username: "kibana_system"              # 必须是 kibana_system，不能用 elastic 超级用户
elasticsearch.password: "<your-password>"
xpack.security.encryptionKey: "..."                  # 三个加密密钥，生产必配
xpack.encryptedSavedObjects.encryptionKey: "..."
xpack.reporting.encryptionKey: "..."
i18n.locale: zh-CN
```

> ⚠️ **Kibana 连 ES 不能用 `elastic` 超级用户** —— Kibana 8.x 启动时做配置校验会直接 FATAL 退出：
> `value of "elastic" is forbidden. This is a superuser account that cannot write to system indices`。
> 必须用内置的 `kibana_system`，且首次使用前要先给它设密码：
> ```bash
> curl -u elastic:<your-password> -H 'Content-Type: application/json' \
>   -X POST 'http://<your-vm-host-ip>:9200/_security/user/kibana_system/_password' \
>   -d '{"password":"<your-password>"}'
> ```
> 注意区分两套账号：**Kibana 服务端连 ES** 用 `kibana_system`；
> 而你在**浏览器登录 Kibana 界面**用的是 ES 用户 `elastic`。

**6、访问 Kibana**

浏览器打开 `http://<your-vm-host-ip>:5601`，用 `elastic` / `<your-password>` 登录。

后面的检索练习都在 **Dev Tools** 里做：左上角菜单 → **Management → Dev Tools**。
它自带 ES 语法补全和格式化，且认证由 Kibana 自动带上，比 curl 和 Apifox 都方便。

> 配套的练习脚本见同目录的 **`ES-console.sh`**，整份复制粘贴到 Dev Tools 即可逐条执行。



## 4、初步检索

**1、_cat —— 查看集群基本信息**

`_cat` 系列默认返回**纯文本表格**，加 `?v` 显示表头（verbose）；不加 `?v` 只有数据行，很难看懂。

**加 `&format=json` 可以改成输出结构化 JSON**，看起来更清晰，本教程统一用这种写法：

```
GET /_cat/nodes?v&format=json                         查看所有节点
GET /_cat/health?v&format=json                        查看 es 健康状况
GET /_cat/master?v&format=json                        查看主节点
GET /_cat/indices?v&format=json                       查看所有索引（相当于 show databases）
GET /_cat/indices/bank?v&h=index,docs.count&format=json   只看指定索引，并指定返回哪几列
```

> - `format=json` 只对 `_cat` 系列有效。`_search`/`_mapping` 等接口返回的本来就是 JSON，不需要这个参数。
> - JSON 模式下 `?v`（表头）不起作用，留着无害；`h=` 仍可用于选择返回哪些字段。

> **`_cat/health` 的 status 含义**：
> **green** 一切正常 / **yellow** 副本未分配（单节点常见）/ **red** 有主分片丢失（数据不完整，要立即处理）
>
> 以 `.` 开头的**系统索引**（Kibana 自己建的 `.kibana_8.18.8_001`、`.internal.alerts-*` 等）默认**不显示**，
> 需要显式要求：`GET /_cat/indices?v&expand_wildcards=all`



**2、索引一个文档（保存）**

> URL 三段的含义是 `PUT /<索引名>/_doc/<文档ID>`，`_doc` 是固定的端点占位符。
> 索引不存在时会**自动创建**（默认 `action.auto_create_index: true`）。

```
# 在 customer 索引下保存 1 号文档
PUT customer/_doc/1
{
  "name": "John Doe"
}
```

在 Apifox / IDEA 的 `.http` 文件里等价写法：

```http
PUT http://<your-vm-host-ip>:9200/customer/_doc/1
Content-Type: application/json
Authorization: Basic ZWxhc3RpYzp6Z2gyOTYwNDI1

{
  "name": "John Doe"
}
```

> 那串 base64 是 `elastic:<your-password>` 的编码（`printf 'elastic:<your-password>' | base64` 的结果）。
> 密码一改就得重算，所以更推荐用 Apifox 的 **Auth → Basic Auth** 图形化配置。



返回数据（带下划线开头的都是**元数据**，反映当前文档的基本信息）：

```json
{
  "_index": "customer",    文档在哪个索引下
  "_id": "1",              文档 id
  "_version": 1,           版本号，每写一次 +1
  "result": "created",     本次是新建；再次 PUT 同一个 id 会变成 "updated"
  "_shards": {
    "total": 2,
    "successful": 1,
    "failed": 0
  },
  "_seq_no": 0,            并发控制字段，每次更新 +1，用来做乐观锁
  "_primary_term": 1       主分片重新分配（如重启）时会变化
}
```

> **PUT 和 POST 的区别：**
> - **POST**：不指定 id → 自动生成 id 新增；指定 id → 修改该文档并新增版本号
> - **PUT**：**必须指定 id**（PUT 的语义是"放到指定位置"，位置是必填的）。
>   可以新增也可以修改，实际中多用于修改操作；不指定 id 会直接报错

**⚠️ 重要概念：ES 是「近实时」（NRT），不是实时**

写完文档后立刻查 `docs.count` 或执行 `_search`，可能会看到**空结果** —— 这不是数据没写进去。写入流程是：

```
PUT 文档 → 内存缓冲区 + translog（已持久化，此时 _doc 按 id 能实时读到）
              ↓ 默认 refresh_interval = 1s
           生成新的 Lucene 段（此时才能被 _search / _cat 统计 看到）
```

所以刚写完那 1 秒内：

| 命令 | 能否查到 | 原因 |
|---|---|---|
| `GET 索引/_doc/1` | ✅ **能** | 按 id 取文档是**实时**的，直接读 translog |
| `GET 索引/_search` | ❌ 可能查不到 | 搜索只认**已 refresh** 的段 |
| `GET _cat/indices` 的 `docs.count` | ❌ 可能显示 0 | 统计取自 Lucene 段，不含未 refresh 的数据 |

**两个解法**（效果相同）：

```
POST bank/_refresh            # 手动强制刷新，立刻可见
```

或者**等 1 秒**再查 —— 默认 `refresh_interval` 就是 1 秒。

> 这也解释了为什么 ES 官方称自己为 **near real-time**：写入到可搜索之间有最长 1 秒的延迟。
> 生产环境可以通过调大 `refresh_interval`（如 `30s`）来提升写入吞吐，代价是搜索可见性延迟变长。



**3、查询文档**

```
GET customer/_doc/1
```

Apifox / IDEA HTTP 文件写法：

```http
GET http://<your-vm-host-ip>:9200/customer/_doc/1
Accept: application/json
Authorization: Basic ZWxhc3RpYzp6Z2gyOTYwNDI1
```

返回数据：

```json
{
  "_index": "customer",
  "_id": "1",
  "_version": 1,
  "_seq_no": 0,          // 并发控制字段，每次更新都会+1，用来做乐观锁
  "_primary_term": 1,    // 同上，主分片重新分配（如重启）时会变化
  "found": true,
  "_source": {           // 你存进去的原始内容
    "name": "John Doe"
  }
}
```

> `found: false` 表示文档不存在 —— 这**不算错误**，所以不会返回 404 状态码，接口调用方要自己判断这个字段。

> **乐观锁用法**：URL 上带 `if_seq_no=x&if_primary_term=y`，
> 只有当文档当前的 seq_no 和 primary_term 都与传入值一致时才允许修改，否则返回 409 版本冲突。
> 之所以叫"乐观"，是因为它**不提前加锁**，而是在写入的那一刻才校验 —— 适合冲突不频繁的场景。

示例：当 seq_no=0、primary_term=1 时才修改

```
PUT customer/_doc/1?if_seq_no=0&if_primary_term=1
{
  "name": "John Doe"
}
```

修改成功后 `_seq_no` 变成 1。

把上面这条**原样再发一次**就会失败 —— seq_no 已经变了，乐观锁生效：

```json
{
  "error": {
    "type": "version_conflict_engine_exception",
    "reason": "[1]: version conflict, required seqNo [0], primary term [1]. current document has seqNo [1] and primary term [1]"
  },
  "status": 409
}
```

![image-20211011134234874](ES/image-20211011134234874.png)

![image-20211011134451525](ES/image-20211011134451525.png)



**4、更新文档**

> 注意 `_update` 在 id 的**前面**：`POST /<索引名>/_update/<文档ID>`

```
# 方式一：带 _update，会先跟原数据对比
POST customer/_update/1
{
   "doc":{
       "name": "John Doew"
   }
}

# 方式二：POST 不带 _update，不检查原数据，直接整体覆盖
POST customer/_doc/1
{
   "name": "John Doe2"
}

# 方式三：PUT，同样是整体覆盖
PUT customer/_doc/1
{
   "name": "John Doe"
}
```

> **带不带 `_update` 的区别（重要）：**
>
> | 写法 | 是否对比原数据 | 内容相同时 | version |
> |---|---|---|---|
> | `POST .../_update/1` | **会**对比 | 什么都不做 | **不增加** |
> | `POST` / `PUT` 不带 `_update` | 不对比 | 照样覆盖写入 | 每次都增加 |
>
> - **带 `_update`**：适合"读多写少、偶尔更新"的场景 —— 省掉无意义的写入，也避免版本号无谓膨胀
> - **不带 `_update`**：适合"写多读少"的场景 —— 少一次对比，写入更快
>
> 另外 `_update` 还支持脚本更新（`"script"`）和 `upsert`（不存在就插入），比整体覆盖灵活得多。

示例：内容与原数据相同，ES 直接跳过，version 不变

```
POST customer/_update/1
{
  "doc":{
    "name": "John"
  }
}
```



**5、删除文档&索引**

```
DELETE customer/_doc/1     删除 id=1 的文档
DELETE customer            删除整个索引（相当于 DROP TABLE）
```

> 注：ES **没有"删除类型"的操作**（类型本身已经废弃了），只提供了删除**文档**和删除**索引**两种粒度。

删除 id=1 的数据：

```
DELETE customer/_doc/1
```

![image-20211011140906175](ES/image-20211011140906175.png)

删除 customer 索引：

```
DELETE customer
```

响应：

```json
{
    "acknowledged": true
}
```



**6、ES的批量操作——bulk**

> ⚠️ **本机实测：该 Kibana(8.18.8) 的 Dev Tools Console 解析不了 bulk(NDJSON) 请求体。**
> `POST xxx/_bulk` 会报下面两个错，且数据行被当成"HTTP 方法"去解析：
>
> ```
> Validation Failed: 1: no requests added
> Expected one of GET/POST/PUT/DELETE/HEAD/PATCH
> ```
>
> 这两个错**不是语法错误** —— 同样的内容用 curl 发送完全正常，说明是 Console 没把数据行
> 识别成请求体。已知两种情形：
>
> 1. **页面状态错乱** —— 从 Dev Tools 切到别的页面（如 Search Profiler）再切回来会触发，
>    **强制刷新页面（Ctrl+Shift+R）即可恢复**
> 2. **版本缺陷** —— 若刷新后仍失败，说明该版本对 NDJSON 支持有问题，**改用 curl**：
>
> ```bash
> # 请求体存成文件（纯 NDJSON，一行一条）
> cat > /tmp/bulk.ndjson <<'EOF'
> {"index":{"_id":"1"}}
> {"name": "John Doe"}
> {"index":{"_id":"2"}}
> {"name": "Jane Doe"}
> EOF
>
> curl -u elastic:<密码> \
>   -H "Content-Type: application/x-ndjson" \
>   -X POST "http://<your-vm-host-ip>:9200/customer/_bulk" \
>   --data-binary @/tmp/bulk.ndjson
> ```
>
> 注意 `Content-Type` 必须是 **`application/x-ndjson`**，不是 `application/json`。

**等价写法（控制台里可靠，推荐日常练习用）**

bulk 的四种 action 都能用普通命令代替 —— 数据量不大时（十几条）完全够用：

| bulk 写法 | 等价命令 |
|---|---|
| `{"index":{"_id":"1"}}` + 文档 | `PUT 索引/_doc/1` + 文档 |
| `{"create":{"_id":"1"}}` + 文档 | `PUT 索引/_create/1` + 文档（只新建，id 已存在则报错） |
| `{"update":{"_id":"1"}}` + `{"doc":{...}}` | `POST 索引/_update/1` + `{"doc":{...}}` |
| `{"delete":{"_id":"1"}}` | `DELETE 索引/_doc/1` |

```
PUT customer/_doc/1
{"name": "John Doe"}
PUT customer/_doc/2
{"name": "Jane Doe"}
```

**bulk 原始写法（供学习语法，控制台跑不通就用 curl）**

示例1：在 customer 索引下批量操作

```
POST customer/_bulk
{"index":{"_id":"1"}}
{"name": "John Doe" }
{"index":{"_id":"2"}}
{"name": "Jane Doe" }
```

> 路径是 `POST /<索引名>/_bulk`，注意没有 type 那一段。

**语法格式：两行一组**，第一行为操作和元数据，第二行为文档内容

```
{ action: { metadata }}\n
{ request body }\n

{ action: { metadata }}\n
{ request body }\n
```

四种 action：

| action | 含义 |
|---|---|
| `index` | 存在就覆盖，不存在就新建（最常用） |
| `create` | 只新建，id 已存在则报错 |
| `update` | 局部更新，请求体要写成 `{"doc":{...}}` |
| `delete` | 删除，**只要一行**（没有文档内容行） |

**关键特性：动作之间互相独立** —— 某一条执行失败时，其余数据仍会继续执行。

> bulk API 按顺序执行所有 action。如果单个动作因任何原因失败，它将继续处理后面剩余的动作。
> 当 bulk API 返回时，会提供每个动作的状态（与发送顺序相同），所以你可以检查某个指定动作是否失败。

返回数据

```json
{
  "took" : 318,            花费了多少 ms
  "errors" : false,        有没有发生错误；true 表示有动作失败
  "items" : [              每个数据的结果，顺序与请求一致
    {
      "index" : {          动作类型
        "_index" : "customer",   索引
        "_id" : "1",             文档
        "_version" : 1,          版本
        "result" : "created",    创建
        "_shards" : {
          "total" : 2,
          "successful" : 1,
          "failed" : 0
        },
        "_seq_no" : 0,
        "_primary_term" : 1,
        "status" : 201           201=新建完成，200=更新成功，404=文档不存在
      }
    },
    {
      "index" : {
        "_index" : "customer",
        "_id" : "2",
        "_version" : 1,
        "result" : "created",
        "_shards" : {
          "total" : 2,
          "successful" : 1,
          "failed" : 0
        },
        "_seq_no" : 1,
        "_primary_term" : 1,
        "status" : 201
      }
    }
  ]
}
```

示例2：全局 bulk —— 动作里用 `_index` 指定目标索引，可以跨索引操作

```
POST _bulk
{"delete":{"_index":"website","_id":"123"}}
{"create":{"_index":"website","_id":"123"}}
{"title":"my first blog post"}
{"index":{"_index":"website"}}
{"title":"my second blog post"}
{"update":{"_index":"website","_id":"123"}}
{"doc":{"title":"my updated blog post"}}
```

> 注意 `delete` 动作**只有一行** —— 删除不需要文档内容。



**7、样本测试数据**

一份顾客银行账户信息的虚构 JSON 文档样本，每个文档的结构如下：

```json
{
"account_number": 0,
"balance": 16623,
"firstname": "Bradshaw",
"lastname": "Mckenzie",
"age": 29,
"gender": "F",
"address": "244 Columbus Place",
"employer": "Euron",
"email": "bradshawmckenzie@euron.com",
"city": "Hobucken",
"state": "CO"
}
```

测试数据地址（可直接下载）：https://gitee.com/xlh_blog/common_content/raw/master/es测试数据.json

导入方式（**8.x 去掉了 type**，路径里不再写 `account`）：

```bash
curl -u elastic:<your-password> -H "Content-Type: application/json" \
  -X POST "http://<your-vm-host-ip>:9200/bank/_bulk" \
  --data-binary @es测试数据.json
```

> 1000 条数据用 Dev Tools 粘贴会很卡，建议用上面的 curl 导入。

导入后查看索引：

```
GET _cat/indices/bank?v&h=health,status,index,pri,rep,docs.count,store.size
```

输出示例（单节点 + 副本 0，所以是 **green**）：

```
health status index pri rep docs.count store.size
green  open   bank    1   0       1000    414.2kb
```

> 单节点环境下**必须把 `number_of_replicas` 设为 0**，否则副本分片无处分配，集群会变成 `yellow`。
> 见 `ES-console.sh` 第 0 步的建索引写法。

> **不想导 1000 条也能练**：`ES-console.sh` 里内置了 13 条精选样本，
> 覆盖了后面所有查询示例所需的字段特征（mill/wallace/kings、各年龄段、各余额区间），
> 整个脚本自包含，粘贴即可跑通全部示例。



## 5、进阶检索

> 本章所有示例都能在 **Kibana Dev Tools** 里直接执行：
> 浏览器打开 `http://<your-vm-host-ip>:5601` → 左上角菜单 → **Management → Dev Tools**。
> 配套的完整可执行脚本见同目录的 **`ES-console.sh`**，整份粘贴即可逐条跑。
>
> ⚠️ **写 JSON 时特别注意**：Dev Tools 的**请求体内部只能用 `//` 注释**，
> 用 `#` 会导致 JSON 解析失败（`#` 只能用在请求外面做行注释/分隔符）。

### **1、SearchAPI**

ES 支持两种基本方式检索：

* 一个是通过使用 REST request URI 发送搜索参数（uri+检索参数）
* 另一个是通过使用 REST request body 来发送它们（uri+请求体）

1）、信息检索

```
GET bank/_search     													检索 bank 下所有信息（包括 docs）

GET bank/_search?q=*&sort=account_number:asc							请求参数方式检索
说明：
q=*     查询所有
sort    排序字段
asc     升序
```

返回内容：

![image-20211011143910397](ES/image-20211011143910397.png)

`took` – 花费多少ms搜索
`timed_out` – 是否超时
`_shards `– 多少分片被搜索了，以及多少成功/失败的搜索分片
`max_score` –文档相关性最高得分
`hits.total.value` - 多少匹配文档被找到
`hits.sort` - 结果的排序key（列），没有的话按照score排序
`hits._score` - 相关得分 (not applicable when using match_all)



uri + 请求体 检索

```
GET /bank/_search
{
  "query": { "match_all": {} },
  "sort": [
    { "account_number": "asc" },
    { "balance": "desc" }
  ]
}
```

> HTTP 客户端工具（POSTMAN / Apifox）里 GET 请求常常不能携带请求体，改成 POST 效果完全一样 ——
> 我们只是把 JSON 风格的查询请求体 POST 到 `_search` API 而已。

需要了解：**一旦搜索结果被返回，Elasticsearch 就完成了这次请求**，
不会在服务端维护任何资源或结果游标（cursor）。所以翻页要靠 `from` + `size`，深度翻页要靠 `search_after`。

### 2、Query DSL

Elasticsearch提供了一个可以执行查询的Json风格的DSL(domain-specific language领域特定语言)。这个被称为Query DSL，该查询语言非常全面。



1、基本语法格式

```
如果针对于某个字段，那么它的结构如下：
{
  QUERY_NAME:{        // 使用的功能
     FIELD_NAME:{     // 功能参数
       ARGUMENT:VALUE,
       ARGUMENT:VALUE,...
      }
   }
}
```

完整示例（分页 + 只取部分字段 + 排序）：

```
GET bank/_search
{
  "query": {                                // 查询的字段
    "match_all": {}
  },
  "from": 0,                                // 从第几条文档开始查
  "size": 5,                                // 查几条文档
  "_source": ["balance"],                   // 返回哪些字段，这里只查 balance
  "sort": [
    {
      "account_number": {                   // 按哪个字段排序
        "order": "desc"                     // 降序
      }
    }
  ]
}
```

> ⚠️ **请求体内部只能写 `//` 注释**（`#` 只在请求外面做行注释和分隔用），
> 写成 `#` 会导致 JSON 解析失败。

**query 定义如何查询：**

* `match_all` 查询类型【代表查询所有的索引】，es 中可以在 query 中组合非常多的查询类型完成复杂查询；
* 除了 query 参数之外，我们也可以传递其他的参数以改变查询结果，如 sort，size；
* `from` + `size` 限定，完成分页功能；
* `sort` 排序，多字段排序，会在前序字段相等时对后续字段内部排序，否则以前序为准；



2、`query/match`匹配查询

> 如果是非字符串，会进行精确匹配。如果是字符串，会进行全文检索

- 基本类型（非字符串），精确控制: 查询account_number == 20的文档

```http
GET bank/_search
{
  "query": {
    "match": {
      "account_number": "20"
    }
  }
}
```

- 字符串，全文检索: 查询address中含有kings的文档

```http
GET bank/_search
{
  "query": {
    "match": {
      "address": "kings"
    }
  }
}
```

* 字符串，全文检索：最终查询出 address 中包含 mill 或者 road 或者 mill road 的所有记录，并给出相关性得分

```http
GET bank/_search
{
   "query": {
      "match": {
         "address": "mill road"
      }
   }
}
```

全文检索，最终会按照评分进行排序，会对检索条件进行分词匹配。



3、`query/match_phrase`【短语匹配】

将需要匹配的值当成一整个单词（不分词）进行检索

- `match`：拆分字符串进行检索。 包含mill 或 road 或 mill road
- `match_phrase`：不拆分字符串进行检索。 包含mill road
- `字段.keyword`：必须全匹配上才检索成功。 

```http
GET bank/_search
{
  "query": {
    "match_phrase": {
      "address": "mill road"   // 不匹配只有 mill 或只有 road 的，要匹配 "mill road" 一整个子串
      
      // "address.keyword": "990 Mill Road"   // 字段加上 .keyword 后缀，则必须完整匹配整个值
      
    }
  }
}
```



4、`query/multi_math`【多字段匹配】

`state或者address中包含mill`，并且在查询过程中，会对于查询条件进行分词。

```http
GET bank/_search
{
  "query": {
    "multi_match": {          // 上面的 match 只能指定一个字段，这个是多个
      "query": "mill",
      "fields": [             // state 和 address 有 mill 子串即可，不要求都有
        "state",
        "address"
      ]
    }
  }
}
```



5、`query/bool/must`复合查询

复合语句可以合并，任何其他查询语句，包括符合语句。这也就意味着，复合语句之间可以互相嵌套，可以表达非常复杂的逻辑。

* must：必须达到must所列举的所有条件
* must_not：必须不匹配must_not所列举的所有条件。
* should：应该满足should所列举的条件。满足条件最好，不满足也可以，满足得分更高

```http
GET bank/_search
{
  "query": {
    "bool": {
      "must": [   // gender 必须是 M，address 必须包含 mill
        {
          "match": {
            "gender": "M"
          }
        },
        {
          "match": {
            "address": "mill"
          }
        }
      ],
      "must_not": [   // age 必须不等于 18
        {
          "match": {
            "age": "18"
          }
        }
      ],
      "should": [     // lastname 最好包含 wallace
        {
          "match": {
            "lastname": "Wallace"
          }
        }
      ]
    }
  }
}
```



6、`query/filter`【结果过滤】

* 上面的must和should影响相关性得分，而must_not仅仅是一个filter ，不贡献得分

* must改为filter就使must不贡献得分

* 如果只有filter条件的话，我们会发现得分都是0

并不是所有的查询都需要产生分数，特别是哪些仅用于filtering过滤的文档。不参与评分更快, 为了不计算分数，elasticsearch会自动检查场景并且优化查询的执行。

```http
GET bank/_search
{
  "query": {
    "bool": {
      "must": [
        { "match": {"address": "mill" } }
      ],
      "filter": {     // query.bool.filter
        "range": {
          "balance": {  // 哪个字段
            "gte": "10000",
            "lte": "20000"
          }
        }
      }
    }
  }
}
```

> 这条查询的含义：**先**全文检索出 address 包含 mill 的文档，**再**用 `10000 <= balance <= 20000` 过滤结果。
> 注意 `must` 里的条件会参与打分（`_score`），而 `filter` 里的条件不会 —— 所以只有 filter 时得分都是 0。



7、`query/term`

和 match 一样。匹配某个属性的值。全文检索字段用 match，其他非 text 字段匹配用 term。

比如：年龄为23岁，用term， address为mill road就用match

```http
GET bank/_search
{
   "query": {
      "bool": {
         "must": [
            {"term": {
               "age": {
                  "value": "28"
               }
            }},
            {"match": {
               "address": "990 Mill Road"
            }}
         ]
      }
   }
}
```



8、`query/nested`【嵌入式查询】

**数组里的对象必须用 `nested` 查** —— 普通查询查的是扁平化之后的字段，会把不同对象的值张冠李戴地匹配上
（原因见 `ES整合SpringBoot.md` 的「为什么 attrs 必须用 nested」）。

本项目的商品索引名是 **`product`**，它的 `attrs` 就是 `nested` 类型
（映射与建索引语句见 **`ES整合SpringBoot.md`**），查出规格属性中「颜色 = 青金」的商品：

```http
POST /product/_search
{
  "query": {
    "nested": {
      "path": "attrs",              // 声明要钻进哪个 nested 字段
      "query": {                    // 里面写正常 DSL，但字段名要带 attrs. 前缀
        "bool": {
          "must": [
            {
              "match": {
                "attrs.attrValue": "青金"
              }
            }
          ]
        }
      }
    }
  }
}
```

几个必须记住的点：

| 要点 | 说明 |
|---|---|
| `path` | **必填**，指明钻进哪个 nested 字段 |
| 字段名 | 内部字段必须写**全路径** `attrs.attrValue`；只写 `attrValue` 查不到 |
| 为什么必须 nested | nested 里的每个对象在底层是**独立的隐藏文档**，普通 `match` 搜不到它们 —— 拿 `attrs.attrValue` 直接查是**一条都命中不了**（不是"结果不准"，而是"查不到"） |
| 多条件语义 | 要求「**同一个** attrs 对象同时满足多个条件」时，必须写进**同一次** `nested`。拆成两个 `nested` 会变成「不同对象各满足一个」，语义完全不同 |

> **`attrValue` 在映射里是 `keyword`（不分词）**，所以这里的 `match` 等价于 `term` 精确匹配 ——
> 必须是完整值「青金」，写「青」查不到（原理见上面第 7 点）。

> **想看是哪个嵌套对象命中的**：在 `nested` 里加 `"inner_hits": {}`，
> 响应会多出 `hits.hits[].inner_hits.attrs.hits`，列出命中的嵌套文档。

> **打分**：`nested` 默认用 `score_mode: avg` 汇总内部得分，可改成 `sum` / `min` / `max` / `none`。



9、`aggregations`（执行聚合）

​	聚合提供了从数据中分组和提取数据的能力。最简单的聚合方法大致等于 SQL GROUP BY 和 SQL 聚合函数。在 Elasticsearch 中，`有执行搜索返回 hits（命中结果），并且同时返回聚合结果`，把一个响应中的所有 hits（命中结果）分隔开的能力。这是非常强大且有效的，您可以执行查询和多个聚合，并且在一次使用中得到各自的（任何一个的）返回结果，使用一次简洁和简化的 API 来避免网络往返。



例：搜索 address 中包含 mill 的所有人的年龄分布以及平均年龄，但不显示这些人的详情。

```http
GET bank/_search
{
  "query": {              // 查询出包含 mill 的
    "match": {
      "address": "Mill"
    }
  },
  "aggs": {               // 基于查询聚合
    "ageAgg": {           // 聚合的名字，随便起
      "terms": {          // 看值的可能性分布
        "field": "age",
        "size": 10
      }
    },
    "ageAvg": {
      "avg": {            // 看 age 值的平均
        "field": "age"
      }
    },
    "balanceAvg": {
      "avg": {            // 看 balance 的平均
        "field": "balance"
      }
    }
  },
  "size": 0               // 不看文档详情，只要聚合结果
}
```

查询结果：

```json
{
  "took" : 2,
  "timed_out" : false,
  "_shards" : {
    "total" : 1,
    "successful" : 1,
    "skipped" : 0,
    "failed" : 0
  },
  "hits" : {
    "total" : {
      "value" : 4, // 命中4条
      "relation" : "eq"
    },
    "max_score" : null,
    "hits" : [ ]
  },
  "aggregations" : {
    "ageAgg" : { // 第一个聚合的结果
      "doc_count_error_upper_bound" : 0,
      "sum_other_doc_count" : 0,
      "buckets" : [
        {
          "key" : 38,  // age 为 38 的有 2 条
          "doc_count" : 2
        },
        {
          "key" : 28,
          "doc_count" : 1
        },
        {
          "key" : 32,
          "doc_count" : 1
        }
      ]
    },
    "ageAvg" : { // 第二个聚合的结果
      "value" : 34.0  // 平均年龄是 34.0
    },
    "balanceAvg" : {
      "value" : 25208.0
    }
  }
}
```



例：按照年龄聚合，并且求这些年龄段的这些人的平均薪资

`aggs/aggName/aggs/aggName`子聚合

> 写到一个聚合里是基于上个聚合进行子聚合。
>
> 下面求每个age分布的平均balance

```http
GET bank/_search
{
  "query": {
    "match_all": {}
  },
  "aggs": {
    "ageAgg": {
      "terms": {          // 看分布
        "field": "age",
        "size": 100
      },
      "aggs": {           // 与 terms 并列，表示在这个分组内部再算
        "ageAvg": {       // 平均
          "avg": {
            "field": "balance"
          }
        }
      }
    }
  },
  "size": 0
}
```



例：复杂子聚合：查出所有年龄分布，并且这些**年龄段**中M的平均薪资和F的平均薪资以及这个年龄段的总体平均薪资

```http
GET bank/_search
{
  "query": {
    "match_all": {}
  },
  "aggs": {
    "ageAgg": {
      "terms": {          // 看 age 分布
        "field": "age",
        "size": 100
      },
      "aggs": {           // 子聚合
        "genderAgg": {
          "terms": {      // 看 gender 分布
            "field": "gender.keyword"   // 注意这里：文本字段聚合必须用 .keyword 子字段
          },
          "aggs": {     // 子聚合
            "balanceAvg": {
              "avg": {  // 该性别的平均余额
                "field": "balance"
              }
            }
          }
        },
        "ageBalanceAvg": {
          "avg": {  // 该年龄段不分性别的平均余额
            "field": "balance"
          }
        }
      }
    }
  },
  "size": 0
}
```

### 3、Mapping字段映射

1、字段类型

![image-20211012145448490](ES/image-20211012145448490.png)

> - `text` ⽤于全⽂索引，搜索时会自动使用分词器进⾏分词再匹配
> - `keyword` 不分词，搜索时需要匹配完整的值

![image-20211012145516034](ES/image-20211012145516034.png)

![image-20211012145531890](ES/image-20211012145531890.png)



2、Mapping（映射）
Mapping 是用来定义一个文档（document），以及它所包含的属性（field）是如何存储和索引的。比如，使用 mapping 来定义：

- 哪些字符串属性应该被看做全文本属性（full text fields）；
- 哪些属性包含数字，日期或地理位置；
- 文档中的所有属性是否都能被索引（all 配置）；
- 日期的格式；
- 自定义映射规则来执行动态添加属性；
- 查看mapping信息：`GET bank/_mapping`



3、一个索引一张表

ES 里没有 MySQL 那种"库 → 表"的两层结构，**一个索引就对应一张表**，需要多张表就建多个索引（原理见第 2 章）。

所以索引命名要有规划，建议统一加业务前缀：

| 业务 | 建议索引名 |
|---|---|
| 商品 | `gulimall_product` |
| 订单 | `gulimall_order` |
| 会员 | `gulimall_member` |

好处：便于用 `gulimall_*` 批量操作和清理，也避免与 Kibana 自己的系统索引混淆。

> ⚠️ **本项目目前用的是不带前缀的 `product`**（见 `EsConstant.PRODUCT_INDEX`）。
> 本文后面出现的 `gulimall_product` 都是**命名规范演示**，直接照抄会报
> `index_not_found_exception` —— 执行前把名字换成自己实际建的索引名。



4、对映射的操作

1）创建索引并指定映射

```json
PUT /my_index
{
  "mappings": {
    "properties": {
      "age": {
        "type": "integer"
      },
      "email": {
        "type": "keyword"   // 不分词，用于精确匹配、聚合、排序
      },
      "name": {
        "type": "text"      // 全文检索：保存时分词，检索时也分词匹配
      }
    }
  }
}
```



2）添加新的字段映射

```json
PUT /my_index/_mapping
{
  "properties": {
    "employee-id": {
      "type": "keyword",
      "index": false        // 不可被检索（不建索引），节省空间
    }
  }
}
```



3)更新映射

对于已经存在的映射字段，我们不能更新。更新必须创建新的索引进行数据迁移



4）数据迁移

先创建new_twitter的正确映射，然后使用如下方式进行数据迁移。

```
POST _reindex
{
  "source": {
    "index": "twitter"
  },
  "dest": {
    "index": "new_twitters"
  }
}
```

> 注意 API 路径是 **`POST _reindex`**（带下划线）。



示例：把 bank 索引下的文档迁移到 newbank 下

* 创建newbank索引

  ```json
  PUT /newbank
  {
    "mappings": {
      "properties": {
        "account_number": {
          "type": "long"
        },
        "address": {
          "type": "text"
        },
        "age": {
          "type": "integer"
        },
        "balance": {
          "type": "long"
        },
        "city": {
          "type": "keyword"
        },
        "email": {
          "type": "keyword"
        },
        "employer": {
          "type": "keyword"
        },
        "firstname": {
          "type": "text"
        },
        "gender": {
          "type": "keyword"
        },
        "lastname": {
          "type": "text",
          "fields": {
            "keyword": {
              "type": "keyword",
              "ignore_above": 256
            }
          }
        },
        "state": {
          "type": "keyword"
        }
      }
    }
  }
  ```

* 数据迁移

  ```json
  POST _reindex
  {
    "source": {
      "index": "bank"
    },
    "dest": {
      "index": "newbank"
    }
  }
  ```

  > 注意 `source` 里只需要写 `index`，没有 type 这一层。

* 查看newbank中的数据

  ```
  GET /newbank/_search
  ```

  响应：

  ```json
  {
    "hits" : {
      "total" : {
        "value" : 1000,
        "relation" : "eq"
      },
      "max_score" : 1.0,
      "hits" : [
        {
          "_index" : "newbank",
          "_id" : "1",
          "_score" : 1.0,
          "_source" : { "account_number": 1, "balance": 39225, "...": "..." }
        }
      ]
    }
  }
  ```



5）迁移收尾：删掉旧索引，把新索引「改名」回旧名

`_reindex` 只负责**搬数据**。搬完之后旧索引还在、旧名字还在被业务代码用 —— 只做到这一步等于没迁。
生产上换 mapping 的完整流程是**四步**：

> **① 建新索引 → ② `_reindex` 迁数据 → ③ 校验 → ④ 删旧索引 + 换名**

**⚠️ 前提：ES 没有「重命名索引」的 API**

索引名是索引的**物理标识**，创建之后**改不了**。所谓「改名」，生产上是靠**别名（alias）**实现的 ——
删掉旧索引，再建一个**与旧索引同名**的别名指向新索引：对业务代码和 Kibana 而言名字完全没变，
只是别名背后的物理索引从 `_v1` 换成了 `_v2`。

下面把上例的 `bank → newbank` 换成真实场景 `gulimall_product` 走一遍完整流程。

**① 建新索引**（用修正后的 mapping，名字带版本号）

```json
PUT /gulimall_product_v2
{
  "mappings": {
    "properties": {
      "skuId": { "type": "long" }
      // ... 其余字段照搬，只改需要修的那几个
    }
  }
}
```

**② 迁数据**

```json
POST _reindex
{
  "source": { "index": "gulimall_product" },
  "dest":   { "index": "gulimall_product_v2" }
}
```

**③ 校验**（迁完必须对数 —— 旧索引一删就没有退路了）

```
GET _cat/indices/gulimall_product*?v&h=index,docs.count&format=json
```

两边 `docs.count` 一致再往下走。响应里的 `created` / `updated` / `version_conflicts` 也一并看一眼，
别只看到 `"failures": []` 就以为成了。

**④ 删旧索引 + 建同名别名（=「改名」）**

```json
DELETE /gulimall_product

POST /_aliases
{
  "actions": [
    { "add": { "index": "gulimall_product_v2", "alias": "gulimall_product" } }
  ]
}
```

完事之后：业务代码写 `gulimall_product`、Kibana 里查 `gulimall_product`，走的都是新索引 —— **代码一行都不用改**。

> **别名默认只用于读。** 如果业务代码是**往别名写**（`_bulk` / `index`），
> 必须显式声明哪个物理索引接收写入，否则报错：

```json
POST /_aliases
{
  "actions": [
    {
      "add": {
        "index": "gulimall_product_v2",
        "alias": "gulimall_product",
        "is_write_index": true        // 不写的话，往别名写数据会直接失败
      }
    }
  ]
}
```

> ⚠️ 本项目 `EsConstant.PRODUCT_INDEX` 配的就是商品索引名（见 `EsConstant`），商品上架正是**往它写**的
> —— 所以 `is_write_index` 这一步**不能漏**，漏了表现为上架接口报错。

> ⚠️ **但第 ④ 步整体不是原子的** —— `DELETE` 和 `POST /_aliases` 是两个请求，
> 中间有一个极短的空窗期：此刻索引不存在，任何写请求都会失败。
>
> 想彻底消除空窗，正确做法是**从一开始就让业务用别名**：首次建索引时就建好
> `gulimall_product` 别名指向 `gulimall_product_v1`，之后每次换 mapping 只需在**一个**
> `_aliases` 请求里同时 `remove` 旧 + `add` 新 —— **这一步才是真正原子的**，
> 物理索引名随便换，业务全程无感。代价是首次要改一次代码里的索引名，属于一次性投入。

**迁移期间的增量数据怎么办**

`_reindex` 是**一次性快照式**搬运，迁移过程中新增/修改的文档不会自动同步。三种应对：

| 方式 | 做法 | 适用场景 |
|---|---|---|
| **停写窗口** | 迁移前停掉写入，迁完校验通过再放开 | 能停机、数据量小（最简单，推荐） |
| **双写** | 迁移期间业务代码同时写新旧两个索引，迁完切读、再停写旧 | 要求不停机 |
| **补增量** | 迁完再跑一次只迁「某时间点之后变更」的数据 | 数据带更新时间字段时 |

补增量的写法（靠 `source.query` 只取增量的那部分）：

```json
POST _reindex
{
  "source": {
    "index": "gulimall_product",
    "query": {
      "range": { "updateTime": { "gte": "2026-09-24T00:00:00" } }
    }
  },
  "dest": { "index": "gulimall_product_v2" }
}
```

**两个常用参数**

| 参数 | 写在哪 | 作用 |
|---|---|---|
| `"conflicts": "proceed"` | 顶层 | 遇到版本冲突**继续执行**，不中断（默认一遇冲突就报错并停止） |
| `"op_type": "create"` | `dest` 里 | 只在目标不存在该 id 时才写入，**天然幂等** —— 补增量、重跑都不会产生重复版本 |

```json
POST _reindex
{
  "conflicts": "proceed",
  "source": { "index": "gulimall_product" },
  "dest":   { "index": "gulimall_product_v2", "op_type": "create" }
}
```

> 数据量大、不想让请求挂着等：加 `?wait_for_completion=false`，`_reindex` 会立刻返回一个
> `task` id，再用 `GET _tasks/<task_id>` 轮询进度。千万级文档建议这么干，否则连接容易超时。

**顺带一提：为什么不用「迁回原索引名」替代别名？**

理论上可以 —— 删掉 `gulimall_product`，用新 mapping 建回同名索引，再把 `_v2` 的数据 `_reindex` 回去。
但那样**数据要搬两遍**，索引名也没了「可回滚」的余地（旧索引已删，出问题只能重建）。
别名方案只搬一遍，且切换是**原子的**（`_aliases` 一次请求内完成），出问题改回别名即可回滚。**生产一律推荐别名。**



### 4、分词

* 一个tokenizer（分词器）接收一个字符流，将之分割为独立的tokens（词元，通常是独立的单词），然后输出tokens流。

* 例如：whitespace tokenizer遇到空白字符时分割文本。它会将文本"Quick brown fox!"分割为[Quick,brown,fox!]

* 该tokenizer（分词器）还负责记录各个terms(词条)的顺序或position位置（用于phrase短语和word proximity词近邻查询），以及term（词条）所代表的原始word（单词）的start（起始）和end（结束）的character offsets（字符串偏移量）（用于高亮显示搜索的内容）。

* elasticsearch提供了很多内置的分词器（标准分词器），可以用来构建custom analyzers（自定义分词器）

示例：

```json
POST _analyze
{
  "analyzer": "standard",
  "text": "Quick brown fox!"
}
```

分词结果：

![image-20211012154808341](ES/image-20211012154808341.png)



**1、安装 ik 分词器**

所有语言默认使用的都是 "Standard Analyzer"，但它**对中文非常不友好** —— 会把中文按**单字**切分，
"小米手机" 会变成 小 / 米 / 手 / 机，检索效果极差。所以中文场景**必须装 IK**。

下载地址：https://github.com/infinilabs/analysis-ik/releases
（原 `medcl/elasticsearch-analysis-ik` 仓库已归档，迁移到了 `infinilabs/analysis-ik`）

**版本必须与 ES 完全一致** —— 本项目是 **8.18.8**，所以下载 `elasticsearch-analysis-ik-8.18.8.zip`。
版本不一致会**直接导致 ES 启动失败**（报 `Plugin [analysis-ik] was built for Elasticsearch version X`）。

安装方式（本项目已把 `plugins` 目录挂载到宿主机 `/mydata/docker/es/plugins`）：

```bash
cd /mydata/docker/es/plugins

# 解压到独立子目录 —— 注意 -d 参数，别在根目录直接解压
mkdir -p analysis-ik
unzip -o elasticsearch-analysis-ik-8.18.8.zip -d analysis-ik

# zip 包必须移出 plugins 目录，否则 ES 会把它当插件扫描并启动失败
mv elasticsearch-analysis-ik-8.18.8.zip ..

chown -R 1000:1000 .
docker compose restart elasticsearch
```

> ⚠️ **两个高频踩坑点**（原因：ES 会把 `plugins/` 下**每一个文件和子目录**都当成独立插件扫描）：
>
> | 报错 | 原因 |
> |---|---|
> | `Plugin [xxx.zip] is missing a descriptor properties file` | zip **没解压**就直接放进去了 |
> | `Plugin [config] is missing a descriptor properties file` | 解压**漏写 `-d`**，包里的 `config/` 摊在了根目录 |
>
> 正确的目录结构必须是：
>
> ```
> /mydata/docker/es/plugins/
> └── analysis-ik/
>     ├── plugin-descriptor.properties      ← 必须在这一层，不能再深
>     ├── elasticsearch-analysis-ik-8.18.8.jar
>     └── config/
> ```


**2、测试分词器**

1、ik_smart

```json
POST _analyze
{
  "analyzer": "ik_smart",
  "text": "我是中国人"
}
```

返回结果

```json
{
  "tokens" : [
    {
      "token" : "我",
      "start_offset" : 0,
      "end_offset" : 1,
      "type" : "CN_CHAR",
      "position" : 0
    },
    {
      "token" : "是",
      "start_offset" : 1,
      "end_offset" : 2,
      "type" : "CN_CHAR",
      "position" : 1
    },
    {
      "token" : "中国人",
      "start_offset" : 2,
      "end_offset" : 5,
      "type" : "CN_WORD",
      "position" : 2
    }
  ]
}
```

2、ik_max_word

```json
POST _analyze
{
  "analyzer": "ik_max_word",
  "text": "我是中国人"
}
```

返回结果

```json
{
  "tokens" : [
    {
      "token" : "我",
      "start_offset" : 0,
      "end_offset" : 1,
      "type" : "CN_CHAR",
      "position" : 0
    },
    {
      "token" : "是",
      "start_offset" : 1,
      "end_offset" : 2,
      "type" : "CN_CHAR",
      "position" : 1
    },
    {
      "token" : "中国人",
      "start_offset" : 2,
      "end_offset" : 5,
      "type" : "CN_WORD",
      "position" : 2
    },
    {
      "token" : "中国",
      "start_offset" : 2,
      "end_offset" : 4,
      "type" : "CN_WORD",
      "position" : 3
    },
    {
      "token" : "国人",
      "start_offset" : 3,
      "end_offset" : 5,
      "type" : "CN_WORD",
      "position" : 4
    }
  ]
}
```



**3、自定义词库**

IK 自带的词库不可能覆盖所有专有名词（品牌词、型号词、行业黑话），实际业务里需要往词库里加词。
有两种方式：

| 方式 | 配置项 | 特点 |
|---|---|---|
| **本地词库** | `ext_dict` | 词库文件放在 IK 的 config 目录下，**改完必须重启 ES** |
| **远程词库** | `remote_ext_dict` | 词库放在 HTTP 服务器上，IK **每分钟自动检查更新**，不用重启 |

> **生产环境推荐远程词库** —— 加词不用重启 ES，业务无感知。

![image-20211012192408901](ES/image-20211012192408901.png)

![image-20211012192537189](ES/image-20211012192537189.png)

**修改 `IKAnalyzer.cfg.xml`**（本项目位置：`/mydata/docker/es/plugins/analysis-ik/config/IKAnalyzer.cfg.xml`）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">
<properties>
	<comment>IK Analyzer 扩展配置</comment>
	<!--用户可以在这里配置自己的扩展字典 -->
	<entry key="ext_dict">custom/mydict.dic</entry>
	 <!--用户可以在这里配置自己的扩展停止词字典-->
	<entry key="ext_stopwords">custom/stopword.dic</entry>
	<!--用户可以在这里配置远程扩展字典 -->
	<entry key="remote_ext_dict">http://<your-vm-host-ip>/es/fenci.txt</entry>
	<!--用户可以在这里配置远程扩展停止词字典-->
	<!-- <entry key="remote_ext_stopwords">words_location</entry> -->
</properties>
```

**远程词库文件长什么样？**

一行一个词，**编码必须是 UTF-8**（编码错了会变乱码，分词自然失效）：

```
小米手机
华为Mate
尚硅谷
```

**怎么托管？** 任何静态文件服务器都行，nginx 是最常见的：

```bash
docker run -d --name nginx-es-dict -p 80:80 \
  -v /path/to/dict:/usr/share/nginx/html \
  nginx:stable
```

把 `fenci.txt` 放到 `/path/to/dict/es/` 下，通过 `http://<IP>/es/fenci.txt` 即可访问。

**重启 ES 并验证**

远程词库改完**不用重启**（IK 每分钟自动拉取）；本地词库改完要重启：

```bash
docker compose restart elasticsearch
```

```json
POST _analyze
{
  "analyzer": "ik_max_word",
  "text": "尚硅谷不错"
}
```

分词效果

```json
{
  "tokens" : [
    {
      "token" : "尚硅谷",
      "start_offset" : 0,
      "end_offset" : 3,
      "type" : "CN_WORD",
      "position" : 0
    },
    {
      "token" : "硅谷",
      "start_offset" : 1,
      "end_offset" : 3,
      "type" : "CN_WORD",
      "position" : 1
    },
    {
      "token" : "不错",
      "start_offset" : 3,
      "end_offset" : 5,
      "type" : "CN_WORD",
      "position" : 2
    }
  ]
}
```




---

> 📌 **ES 与 Spring Boot 的集成**（客户端配置、商品上架入库、商品检索查询）已拆分为独立文档：
> 见同目录的 **`ES整合SpringBoot.md`**。
