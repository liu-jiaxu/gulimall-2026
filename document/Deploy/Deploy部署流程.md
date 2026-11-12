# gulimall 全量部署流程（Windows 开发机 → Linux 服务器）

> 目标机：`<your-vm-host-ip>`（下文用 `SERVER_IP` 代指，换机器时全局替换）。
> 本文只写 **Java 进程、管理端前端、配置、启动**。
> 中间件安装与排错见 [Deploy组件一览.md](./Deploy组件一览.md)；Cloudflare Tunnel 见 [Cloudflare-Tunnel-配置指南.md](../内网穿透/Cloudflare-Tunnel-配置指南.md)。

---

## 一、部署范围

### 1.1 进程清单（13 个）

| 服务 | 端口 | 产物 | 数据库 |
|---|---|---|---|
| gulimall-gateway | **88** | `gulimall-gateway-0.0.1-SNAPSHOT.jar` | — |
| gulimall-coupon | 7000 | `gulimall-coupon-0.0.1-SNAPSHOT.jar` | `gulimall_sms` |
| gulimall-member | 8000 | `gulimall-member-0.0.1-SNAPSHOT.jar` | `gulimall_ums` |
| gulimall-order | 9000 | `gulimall-order-0.0.1-SNAPSHOT.jar` | `gulimall_oms` |
| gulimall-product | 10000 | `gulimall-product-0.0.1-SNAPSHOT.jar` | `gulimall_pms` |
| gulimall-ware | 11000 | `gulimall-ware-0.0.1-SNAPSHOT.jar` | `gulimall_wms` |
| gulimall-search | 12000 | `gulimall-search-0.0.1-SNAPSHOT.jar` | —（ES） |
| gulimall-cart | 13000 | `gulimall-cart-0.0.1-SNAPSHOT.jar` | —（Redis） |
| gulimall-seckill | 14000 | `gulimall-seckill-0.0.1-SNAPSHOT.jar` | `gulimall_sms` |
| gulimall-agent | 15000 | `gulimall-agent-0.0.1-SNAPSHOT.jar` | `gulimall_ai` |
| gulimall-auth-server | 20000 | `gulimall-auth-server-0.0.1-SNAPSHOT.jar` | —（Redis Session） |
| gulimall-third-party | 30000 | `gulimall-third-party-0.0.1-SNAPSHOT.jar` | —（MinIO） |
| renren-fast | 8080 | `renren-fast.jar`（context-path `/renren-fast`） | `gulimall_admin` |

### 1.2 不部署的模块

| 模块 | 原因 |
|---|---|
| `gulimall-common` / `gulimall-api` | 公共依赖库，已打进各服务 jar |
| `gulimall-rabbitmq` / `gulimall-rocketmq` | 工具库，没有启动类 |
| `renren-generator` | 代码生成器，只在开发期用 |
| `jmeter/` | 压测脚本，可选上传 |

### 1.3 前端与模板

| 类型 | 位置 | 处理方式 |
|---|---|---|
| Thymeleaf 模板 | jar 内 `BOOT-INF/classes/templates/` | 由 Java 渲染，**不要**上传 Nginx |
| 页面静态资源 | 各模块 `src/main/resources/static/` | 可选上传 Nginx，见 §4.5 |
| 管理端 Vue | `renren-fast-vue/dist` | 必须上传 `html/admin/` |
| AI 聊天窗资源 | `gulimall-agent` jar 内 `static/agent/widget/` | agent 自己提供，不上传 Nginx |

---

## 二、部署前配置

### 2.1 修改 `config/docker-compose.env`

```bash
WINDOWS_HOST_IP=host-gateway     # Java 跑在 Linux 时用这个
VM_HOST_IP=<your-vm-host-ip>        # 本机局域网 IP
```

> **本地联调**（Java 还在 Windows）：`WINDOWS_HOST_IP` 要填 **`<your-windows-host-ip>`**，否则 Nginx 容器会去连 Linux 的 `172.17.0.1`，表现为 502。Java 全部迁到 Linux 后才改成 `host-gateway`。

Nginx 容器通过 `host.docker.internal` 回连 Java 进程；`host-gateway` 解析成 Linux 宿主机网桥，填 Windows IP 时解析成 Windows 开发机。

改完重建 Nginx：

```bash
cd /mydata/docker
docker compose --env-file /mydata/app/config/docker-compose.env up -d --force-recreate nginx
docker exec nginx getent hosts host.docker.internal
```

### 2.2 检查 IP

`config/common.yaml` 中的 `MYSQL_HOST` / `REDIS_HOST` / `NACOS_ADDR` / `ES_URIS` / `MINIO_ENDPOINT` / `RABBITMQ_HOST` 必须是目标机 IP。

```bash
grep -E 'MYSQL_HOST|REDIS_HOST|NACOS_ADDR|ES_URIS|MINIO_ENDPOINT|RABBITMQ_HOST' /mydata/app/config/common.yaml
```

### 2.3 停掉 Windows 上的服务

Windows 上正在跑的服务必须全部停掉，否则 Nacos 里会同时出现 `<your-windows-host-ip>` 和 `<your-vm-host-ip>` 两套实例，网关轮询会导致“一会儿正常一会儿 502”。

停完等 30 秒，在 Nacos 控制台确认没有 `<your-windows-host-ip>` 的实例。

### 2.4 发布网关路由到 Nacos

网关优先读 Nacos 的 `application-gateway.yaml`，本地文件只是兜底。在 Windows 侧执行（单行）：

```bash
curl -s -X POST "http://<your-vm-host-ip>:8848/nacos/v1/cs/configs" --data-urlencode "dataId=application-gateway.yaml" --data-urlencode "group=DEFAULT_GROUP" --data-urlencode "type=yaml" --data-urlencode "content@D:/SoftwareInstallation/gulimall/gulimall-gateway/src/main/resources/application-gateway.yaml"
```

验证包含 `agent-route`：

```bash
curl -s "http://<your-vm-host-ip>:8848/nacos/v1/cs/configs?dataId=application-gateway.yaml&group=DEFAULT_GROUP" | grep -n "agent-route"
```

---

## 三、构建（在 Windows 开发机执行）

### 3.1 停 IDEA 里运行的应用

否则 `mvn clean` 会报：

```text
failed with FileSystemException: ... 另一个程序正在使用此文件，进程无法访问
```

### 3.2 后端

```bash
cd D:\SoftwareInstallation\gulimall
mvn clean package -DskipTests
# 部分模块重新构建
mvn -f D:\SoftwareInstallation\gulimall\pom.xml -pl gulimall-seckill,gulimall-agent -am clean package -DskipTests
```

> 全量构建，不要用 `-pl` 只挑几个模块，避免漏掉 `gulimall-common` / `gulimall-api`。
> `gulimall-agent` 依赖 LangChain4J/Milvus，首次构建需要联网下载依赖。

### 3.3 renren-fast

```bash
cd D:\SoftwareInstallation\gulimall\renren-fast
mvn clean package -DskipTests
```

产物：`renren-fast/target/renren-fast.jar`。

### 3.4 管理端前端（必须 Node 16）

`node-sass 6.0.1` 只支持到 Node 16。

Git Bash：

```bash
export PATH="/d/SoftwareInstallation/nvm/nvm/v16.20.2:$PATH"
node -v
cd D:\SoftwareInstallation\gulimall\renren-fast-vue
npm run build
```

cmd：

```bat
set PATH=D:\SoftwareInstallation\nvm\nvm\v16.20.2;%PATH%
cd D:\SoftwareInstallation\gulimall\renren-fast-vue
npm run build
```

构建后验证：

```bash
grep baseUrl dist/config/index.js
# 应为：window.SITE_CONFIG['baseUrl'] = '/gulimall';
```

### 3.5 产物清单

| 产物 | 路径 |
|---|---|
| 12 个微服务 jar | `gulimall-*/target/gulimall-*-0.0.1-SNAPSHOT.jar` |
| renren-fast | `renren-fast/target/renren-fast.jar` |
| 管理端 dist | `renren-fast-vue/dist/` |
| 配置目录 | `config/`（含明文密码，不在 git） |
| Nginx/部署配置 | `deploy/`（服务器上对应 `/mydata/docker`） |

---

## 四、上传

### 4.1 服务器准备

首次部署安装 JDK 21：

```bash
dnf install -y java-21-openjdk
java -version
```

建目录：

```bash
mkdir -p /mydata/app /mydata/logs
```

如果 Nginx 访问 Java 端口返回 502，且日志是 `113 Host is unreachable`，处理防火墙：

```bash
# 测试机可临时关闭
systemctl stop firewalld
systemctl disable firewalld
systemctl is-active firewalld
systemctl is-active nftables
```

生产环境改为信任 docker 网桥：

```bash
ip -o link show | grep -o 'br-[0-9a-f]*'
firewall-cmd --permanent --zone=trusted --add-interface=docker0
firewall-cmd --permanent --zone=trusted --add-interface=br-<上面查到的名字>
firewall-cmd --reload
```

### 4.2 上传 jar

以下命令逐行执行：

```bash
scp D:\SoftwareInstallation\gulimall\gulimall-gateway\target\gulimall-gateway-0.0.1-SNAPSHOT.jar root@<your-vm-host-ip>:/mydata/app/
scp D:\SoftwareInstallation\gulimall\gulimall-coupon\target\gulimall-coupon-0.0.1-SNAPSHOT.jar root@<your-vm-host-ip>:/mydata/app/
scp D:\SoftwareInstallation\gulimall\gulimall-member\target\gulimall-member-0.0.1-SNAPSHOT.jar root@<your-vm-host-ip>:/mydata/app/
scp D:\SoftwareInstallation\gulimall\gulimall-order\target\gulimall-order-0.0.1-SNAPSHOT.jar root@<your-vm-host-ip>:/mydata/app/
scp D:\SoftwareInstallation\gulimall\gulimall-product\target\gulimall-product-0.0.1-SNAPSHOT.jar root@<your-vm-host-ip>:/mydata/app/
scp D:\SoftwareInstallation\gulimall\gulimall-ware\target\gulimall-ware-0.0.1-SNAPSHOT.jar root@<your-vm-host-ip>:/mydata/app/
scp D:\SoftwareInstallation\gulimall\gulimall-search\target\gulimall-search-0.0.1-SNAPSHOT.jar root@<your-vm-host-ip>:/mydata/app/
scp D:\SoftwareInstallation\gulimall\gulimall-cart\target\gulimall-cart-0.0.1-SNAPSHOT.jar root@<your-vm-host-ip>:/mydata/app/
scp D:\SoftwareInstallation\gulimall\gulimall-seckill\target\gulimall-seckill-0.0.1-SNAPSHOT.jar root@<your-vm-host-ip>:/mydata/app/
scp D:\SoftwareInstallation\gulimall\gulimall-agent\target\gulimall-agent-0.0.1-SNAPSHOT.jar root@<your-vm-host-ip>:/mydata/app/
scp D:\SoftwareInstallation\gulimall\gulimall-auth-server\target\gulimall-auth-server-0.0.1-SNAPSHOT.jar root@<your-vm-host-ip>:/mydata/app/
scp D:\SoftwareInstallation\gulimall\gulimall-third-party\target\gulimall-third-party-0.0.1-SNAPSHOT.jar root@<your-vm-host-ip>:/mydata/app/
scp D:\SoftwareInstallation\gulimall\renren-fast\target\renren-fast.jar root@<your-vm-host-ip>:/mydata/app/
```

### 4.3 上传 config

```bash
scp -r D:\SoftwareInstallation\gulimall\config root@<your-vm-host-ip>:/mydata/app/
```

确认：

```bash
ls /mydata/app/config
# common.yaml、gulimall-*.yaml、renren-fast.yaml、docker-compose.env
```

> `config/` 含明文密码，不在 git 里，换机器必须单独拷。
> 占位符没有默认值，config 不到位会直接启动失败 `Could not resolve placeholder 'MYSQL_HOST'`。

同时把 SQL 文件传上去（首次或换库时用）：

```bash
scp -r D:\SoftwareInstallation\gulimall\sql root@<your-vm-host-ip>:/mydata/app/
```

### 4.4 上传管理端前端

```bash
ssh root@<your-vm-host-ip> "mkdir -p /mydata/docker/nginx/html/admin"
scp -r D:\SoftwareInstallation\gulimall\renren-fast-vue\dist\* root@<your-vm-host-ip>:/mydata/docker/nginx/html/admin/
```

确认 `index.html` 直接在 `html/admin/` 下。

### 4.5 上传页面静态资源（可选）

Thymeleaf 模板本身不上传，这里只上传模板引用的 css/js/img：

```bash
ssh root@<your-vm-host-ip> "mkdir -p /mydata/docker/nginx/html"
scp -r D:\SoftwareInstallation\gulimall\gulimall-product\src\main\resources\static\index root@<your-vm-host-ip>:/mydata/docker/nginx/html/
scp -r D:\SoftwareInstallation\gulimall\gulimall-product\src\main\resources\static\item  root@<your-vm-host-ip>:/mydata/docker/nginx/html/
scp -r D:\SoftwareInstallation\gulimall\gulimall-search\src\main\resources\static\search     root@<your-vm-host-ip>:/mydata/docker/nginx/html/
scp -r D:\SoftwareInstallation\gulimall\gulimall-auth-server\src\main\resources\static\login root@<your-vm-host-ip>:/mydata/docker/nginx/html/
scp -r D:\SoftwareInstallation\gulimall\gulimall-auth-server\src\main\resources\static\reg   root@<your-vm-host-ip>:/mydata/docker/nginx/html/
scp -r D:\SoftwareInstallation\gulimall\gulimall-cart\src\main\resources\static\cart         root@<your-vm-host-ip>:/mydata/docker/nginx/html/
scp -r D:\SoftwareInstallation\gulimall\gulimall-order\src\main\resources\static\confirm     root@<your-vm-host-ip>:/mydata/docker/nginx/html/
scp -r D:\SoftwareInstallation\gulimall\gulimall-order\src\main\resources\static\pay         root@<your-vm-host-ip>:/mydata/docker/nginx/html/
scp -r D:\SoftwareInstallation\gulimall\gulimall-order\src\main\resources\static\detail      root@<your-vm-host-ip>:/mydata/docker/nginx/html/
scp -r D:\SoftwareInstallation\gulimall\gulimall-order\src\main\resources\static\list        root@<your-vm-host-ip>:/mydata/docker/nginx/html/
```

> 不上传也能用：Nginx 会回源到 Java；上传后由 Nginx 直出，减少 Java 静态资源压力。

### 4.6 同步 Nginx 配置

```bash
scp -r D:\SoftwareInstallation\gulimall\deploy\nginx\conf\* root@<your-vm-host-ip>:/mydata/docker/nginx/conf/
```

> 不要整体覆盖 `/mydata/docker`，避免误动 `mysql/data`、`es/data`、`milvus/*` 等数据目录。

---

## 五、数据库初始化

业务库已存在，不重复导入；只初始化 AI 库：

```bash
mysql -h127.0.0.1 -uroot -p < /mydata/app/sql/gulimall_ai.sql
mysql -h127.0.0.1 -uroot -p < /mydata/app/sql/gulimall_ai_menu.sql
mysql -h127.0.0.1 -uroot -p -e "show tables from gulimall_ai;"
# 应有：ai_user_setting / ai_custom_model
```

> 已有 `gulimall_pms/oms/ums/wms/sms/admin` 的建表脚本不要重复执行，否则会报 `Table already exists`。

---

## 六、启动

### 6.1 启动顺序

| 阶段 | 服务 | 说明 |
|---|---|---|
| ① 基础 | coupon、member、product、ware、third-party、search、cart、auth-server、seckill | 先注册到 Nacos |
| ② 交易 | order | 依赖 ware、coupon、member |
| ③ AI | agent | 依赖 Nacos/MySQL/Redis/MinIO；工具在请求时才调业务服务 |
| ④ 管理端 | renren-fast | 独立工程 |
| ⑤ 入口 | gateway | 最后启动 |

启动前确认中间件已就绪（见 `Deploy组件一览.md`）。

### 6.2 start-all.sh

在 Linux 创建 `/mydata/app/start-all.sh`：

```bash
#!/usr/bin/env bash
# gulimall 全量启动脚本（13 个 Java 进程）
set -u

APP_DIR=/mydata/app
LOG_DIR=/mydata/logs

# 公共 JVM 参数：压低元空间、JIT 代码缓存和线程栈
COMMON_OPTS="-XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=96m -Xss512k -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${LOG_DIR}"

mkdir -p "$LOG_DIR"
cd "$APP_DIR" || exit 1

# 按模块分配堆上限：堆上限合计约 2.9G
heap_for() {
  case "$1" in
    gulimall-product-*.jar|gulimall-order-*.jar)
      echo "-Xms128m -Xmx320m"
      ;;
    gulimall-gateway-*.jar)
      echo "-Xms128m -Xmx256m"
      ;;
    gulimall-agent-*.jar)
      echo "-Xms128m -Xmx384m -XX:MaxMetaspaceSize=256m"
      ;;
    gulimall-third-party-*.jar)
      echo "-Xms64m -Xmx128m"
      ;;
    *)
      # coupon/member/ware/search/cart/auth/seckill/renren-fast
      echo "-Xms64m -Xmx192m"
      ;;
  esac
}

start() {
  local jar="$1"
  if pgrep -f "$jar" >/dev/null 2>&1; then
    echo "[跳过] $jar 已在运行"
    return 0
  fi
  local heap
  heap="$(heap_for "$jar")"
  nohup java $COMMON_OPTS $heap -jar "$jar" > "$LOG_DIR/${jar%.jar}.log" 2>&1 &
  echo "[启动] $jar pid=$! heap=$heap"
}

# 第一批：基础业务服务
for jar in \
  gulimall-coupon-0.0.1-SNAPSHOT.jar \
  gulimall-member-0.0.1-SNAPSHOT.jar \
  gulimall-product-0.0.1-SNAPSHOT.jar \
  gulimall-ware-0.0.1-SNAPSHOT.jar \
  gulimall-third-party-0.0.1-SNAPSHOT.jar \
  gulimall-search-0.0.1-SNAPSHOT.jar \
  gulimall-cart-0.0.1-SNAPSHOT.jar \
  gulimall-auth-server-0.0.1-SNAPSHOT.jar \
  gulimall-seckill-0.0.1-SNAPSHOT.jar
do
  start "$jar"
  sleep 5
done

# 第二批：订单 + AI 助手
sleep 20
start gulimall-order-0.0.1-SNAPSHOT.jar
sleep 10
start gulimall-agent-0.0.1-SNAPSHOT.jar
sleep 10

# 第三批：管理端后端
start renren-fast.jar
sleep 10

# 第四批：网关
start gulimall-gateway-0.0.1-SNAPSHOT.jar

echo "[完成]"
echo "  ss -lntp | grep java"
echo "  tail -f $LOG_DIR/gulimall-agent-0.0.1-SNAPSHOT.log"
```

```bash
chmod +x /mydata/app/start-all.sh
/mydata/app/start-all.sh
```

### 6.3 stop-all.sh

```bash
#!/usr/bin/env bash
pkill -f 'gulimall-.*-0\.0\.1-SNAPSHOT\.jar'
pkill -f 'renren-fast\.jar'
sleep 3
pgrep -af 'gulimall-.*\.jar|renren-fast\.jar' || echo "[已全部停止]"
```

> 端口 88 是特权端口，必须用 root 启动。

### 6.4 确认启动成功

```bash
# 端口全部监听
for p in 88 7000 8000 9000 10000 11000 12000 13000 14000 15000 20000 30000 8080; do printf "%-6s " $p; ss -lntp | grep -q ":$p " && echo OK || echo DOWN; done

# 日志出现 Started
grep -l "Started Gulimall" /mydata/logs/*.log
grep -l "Started RenrenApplication" /mydata/logs/renren-fast.log
grep -l "Started GulimallAgentApplication" /mydata/logs/gulimall-agent-0.0.1-SNAPSHOT.log
```

Nacos 服务列表应有 12 个（renren-fast 不注册 Nacos；含 seata-server 则 13 个）。

---

## 七、Nginx

站点配置在 `deploy/nginx/conf/conf.d/gulimall.conf`，共 9 个 server 块：

| 域名 | 去向 |
|---|---|
| `gulimall.vip` | `/` → product:10000；`/gulimall/` → gateway:88；`/es/` → 本地词库 |
| `admin.gulimall.vip` | `/` → 静态 `html/admin/`；`/gulimall/` → gateway:88 |
| `img.gulimall.vip` | `/` → `minio:9000` |
| `search.gulimall.vip` | `/` → search:12000 |
| `auth.gulimall.vip` | `/` → auth-server:20000 |
| `cart.gulimall.vip` | `/` → cart:13000 |
| `order.gulimall.vip` | `/` → order:9000 |
| `seckill.gulimall.vip` | `/` → seckill:14000 |
| `member.gulimall.vip` | `/` → member:8000 |
| 以上 8 个站点 | `*/gulimall/agent/**` → gateway:88（由 `ai-widget.inc` 处理） |

`*/oss/**` 由各站点 include 的 `img-proxy.inc` 转发到 MinIO。

> `ai-widget.inc` 必须在 8 个 server 块里 `include`；
> `location ^~ /gulimall/agent/` 的 `^~` 不能删；
> SSE 必须 `proxy_buffering off; gzip off; proxy_read_timeout 300s;`。

生效：

```bash
cd /mydata/docker
ls nginx/conf/conf.d/ai-widget.inc
docker compose --env-file /mydata/app/config/docker-compose.env exec nginx nginx -t
docker compose --env-file /mydata/app/config/docker-compose.env restart nginx
```

---

## 八、验证

```bash
# 网关直连
curl -s 'http://127.0.0.1:88/gulimall/product/brand/list?page=1&limit=10'
curl -s 'http://127.0.0.1:88/gulimall/member/member/list?page=1&limit=10'

# 各服务直连
curl -I http://127.0.0.1:10000/
curl -I http://127.0.0.1:12000/list.html
curl -I http://127.0.0.1:20000/login.html
curl -I http://127.0.0.1:8080/renren-fast/

# 经 Nginx
curl -I -H 'Host: gulimall.vip'        http://127.0.0.1/
curl -I -H 'Host: search.gulimall.vip' http://127.0.0.1/list.html
curl -I -H 'Host: admin.gulimall.vip'  http://127.0.0.1/

# AI 聊天窗资源；未登录返回 15001
curl -I -H 'Host: gulimall.vip' http://127.0.0.1/gulimall/agent/widget/chat-widget.js
curl -s http://127.0.0.1:15000/agent/models
```

浏览器访问：

| 地址 | 内容 |
|---|---|
| `http://gulimall.vip/` | 商城前台 |
| `http://search.gulimall.vip/list.html` | 搜索页 |
| `http://auth.gulimall.vip/login.html` | 登录页 |
| `http://cart.gulimall.vip/cart.html` | 购物车 |
| `http://order.gulimall.vip/` | 订单页 |
| `http://seckill.gulimall.vip/` | 秒杀页 |
| `http://admin.gulimall.vip/` | 后台管理 |
| 任意前台页面右下角 | AI 购物助手（登录后） |
| `http://admin.gulimall.vip/#/ai/chat` | 管理端 AI 助手 |

域名解析（hosts/DNS）和 Cloudflare Tunnel 见 [Cloudflare-Tunnel-配置指南.md](../内网穿透/Cloudflare-Tunnel-配置指南.md)。

---

## 九、日常运维

```bash
# 看日志
tail -f /mydata/logs/gulimall-product-0.0.1-SNAPSHOT.log
tail -f /mydata/logs/gulimall-agent-0.0.1-SNAPSHOT.log

# 全部停止 / 启动
/mydata/app/stop-all.sh
/mydata/app/start-all.sh

# 更新单个服务
pkill -f 'gulimall-product-0\.0\.1-SNAPSHOT\.jar'
sleep 3
cd /mydata/app && nohup java -Xms128m -Xmx320m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=96m -Xss512k -jar gulimall-product-0.0.1-SNAPSHOT.jar > /mydata/logs/gulimall-product-0.0.1-SNAPSHOT.log 2>&1 &

# 端口占用
ss -lntp | grep -E ':(88|7000|8000|9000|10000|11000|12000|13000|14000|15000|20000|30000|8080) '
```

Java 堆上限：product/order 320m、gateway 256m、agent 384m、小模块 192m、third-party 128m，合计约 2.9G。内存紧张时先降未使用模块的 `-Xmx`，不要全局加。

> 不用的容器（如 RocketMQ、Kibana）可以按需停，具体见 `Deploy组件一览.md`。

---

## 十、首次部署检查清单

```text
□ 1. Windows 上 13 个服务全部停止，Nacos 里没有 <your-windows-host-ip> 实例
□ 2. config/docker-compose.env 的 WINDOWS_HOST_IP = host-gateway
□ 3. nginx 已 --force-recreate，getent hosts host.docker.internal 指向宿主机
□ 4. 中间件已就绪（MySQL/Redis/Nacos/MinIO/ES/Milvus 等）
□ 5. 业务库可访问；gulimall_ai 已执行 gulimall_ai.sql + gulimall_ai_menu.sql
□ 6. 13 个 jar + config + admin dist 已上传
□ 7. （可选）页面静态资源已上传
□ 8. start-all.sh 执行，13 个端口全部监听（含 15000）
□ 9. Nacos 服务列表 12 个实例，IP 均为 <your-vm-host-ip>
□ 10. nginx -t 通过并重启，ai-widget.inc 已存在且 8 个站点已 include
□ 11. 前台/搜索/管理端能打开，接口返回 JSON
□ 12. 登录后宠物/聊天/流式/历史/设置可用；管理端 #/ai/chat 可用
□ 13. Milvus /healthz 返回 OK；agent 日志出现 RAG 入库完成（首次）
□ 14. Cloudflare Route 按独立指南配置（pay 指向 order 或 Nginx）
```
