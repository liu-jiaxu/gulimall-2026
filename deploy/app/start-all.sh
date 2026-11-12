#!/usr/bin/env bash
# gulimall 全量启动脚本（13 个 Java 进程）
set -u

# 1) 固定目录：cwd 必须是 /mydata/app，否则 ./config/*.yaml 读不到
APP_DIR=/mydata/app
LOG_DIR=/mydata/logs

# JMX：测试/压测默认开启，端口 = HTTP 端口 + 1；生产环境用 ENABLE_JMX=0 关闭
ENABLE_JMX="${ENABLE_JMX:-1}"
JMX_HOST="${JMX_HOST:-<your-vm-host-ip>}"

# 2) 公共 JVM 参数：压低元空间、JIT 代码缓存和线程栈，减少每个进程的 native 内存
COMMON_OPTS="-XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=96m -Xss512k -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${LOG_DIR}"

mkdir -p "$LOG_DIR"
cd "$APP_DIR" || exit 1

# 3) 按模块分配堆上限：
#    15G 虚拟机还要跑 MySQL/ES/Kibana/Milvus 等容器，13 个 Java 进程不能都吃 512m。
#    product/order 448m、gateway 320m、AI agent 512m、其余模块 256m；
#    所有 Java 进程都在 256～512m 区间，堆上限合计约 4.0G，实际占用通常更低。
heap_for() {
  case "$1" in
    gulimall-product-*.jar|gulimall-order-*.jar)
      echo "-Xms128m -Xmx448m"
      ;;
    gulimall-gateway-*.jar)
      echo "-Xms128m -Xmx320m"
      ;;
    gulimall-agent-*.jar)
      # LangChain4j / Milvus / MinIO 客户端类多，元空间单独放宽；
      # 如果 RAG 大量入库时仍 OOM，再继续往上加
      echo "-Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m"
      ;;
    *)
      # coupon/member/ware/search/cart/auth/seckill/renren-fast/third-party
      echo "-Xms64m -Xmx256m"
      ;;
  esac
}

# JMX 端口映射：统一用 HTTP 端口 + 1，当前测试机这些端口都空闲
jmx_port_for() {
  case "$1" in
    gulimall-gateway-*.jar) echo 89 ;;
    renren-fast.jar) echo 8081 ;;
    gulimall-coupon-*.jar) echo 7001 ;;
    gulimall-member-*.jar) echo 8001 ;;
    gulimall-order-*.jar) echo 9001 ;;
    gulimall-product-*.jar) echo 10001 ;;
    gulimall-ware-*.jar) echo 11001 ;;
    gulimall-search-*.jar) echo 12001 ;;
    gulimall-cart-*.jar) echo 13001 ;;
    gulimall-seckill-*.jar) echo 14001 ;;
    gulimall-agent-*.jar) echo 15001 ;;
    gulimall-auth-server-*.jar) echo 20001 ;;
    gulimall-third-party-*.jar) echo 30001 ;;
    *) echo "" ;;
  esac
}

jmx_for() {
  # 1. 不需要 VisualVM 时用 ENABLE_JMX=0 关闭
  [ "$ENABLE_JMX" = "1" ] || return 0
  # 2. 查端口，没有映射就不加 JMX
  local port
  port="$(jmx_port_for "$1")"
  [ -n "$port" ] || return 0
  # 3. JMX 端口和 RMI 端口保持一致；hostname 必须是 VisualVM 能访问的地址
  echo "-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=${port} -Dcom.sun.management.jmxremote.rmi.port=${port} -Djava.rmi.server.hostname=${JMX_HOST} -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false"
}

# 4) 启动单个 jar：已在运行则跳过；日志按 jar 名区分
start() {
  local jar="$1"
  if pgrep -f "$jar" >/dev/null 2>&1; then
    echo "[跳过] $jar 已在运行"
    return 0
  fi
  local heap jmx_opts jmx_port
  heap="$(heap_for "$jar")"
  # 1. 查这个 jar 的 JMX 端口
  jmx_port="$(jmx_port_for "$jar")"
  # 2. 关闭 JMX 时端口显示为空
  if [ "$ENABLE_JMX" != "1" ]; then
    jmx_port=""
  fi
  # 3. 开启 JMX 时拼完整参数；端口为空时自然不监听
  jmx_opts="$(jmx_for "$jar")"
  nohup java $COMMON_OPTS $heap $jmx_opts -jar "$jar" > "$LOG_DIR/${jar%.jar}.log" 2>&1 &
  echo "[启动] $jar pid=$! heap=$heap jmx=${jmx_port:-off}"
}

# 5) 第一批：基础业务服务（每个间隔 5 秒，避免同时抢 CPU/连接）
#    内存实在紧张时，seckill/third-party 可以按需注释；coupon/ware 在下单链路里不要省。
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

# 6) 第二批：订单 + AI 助手（等基础服务注册完）
sleep 20
start gulimall-order-0.0.1-SNAPSHOT.jar
sleep 10
start gulimall-agent-0.0.1-SNAPSHOT.jar
sleep 10

# 7) 第三批：管理端后端
start renren-fast.jar
sleep 10

# 8) 第四批：网关（最后启动）
start gulimall-gateway-0.0.1-SNAPSHOT.jar

echo "[完成] 确认命令："
echo "  ss -lntp | grep java"
echo "  tail -f $LOG_DIR/gulimall-agent-0.0.1-SNAPSHOT.log"
