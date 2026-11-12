# Gulimall 生产级多集群 Kubernetes 与 Jenkins 部署流程

## 一、目标与边界

这份方案面向完整的 gulimall 项目：13 个 Java 业务服务、网关、管理端和全套中间件。目标是生产级部署，不是单机实验。

生产级要求包括：

1. 多集群隔离和容灾；
2. 控制平面高可用；
3. 中间件高可用和备份；
4. 镜像不可变、可追溯、可扫描；
5. CI 与 CD 分离；
6. GitOps 发布和审批；
7. 监控、日志、链路追踪；
8. 密钥管理、最小权限和网络策略；
9. 灰度发布、回滚和灾难恢复演练。

单节点 kubeadm、单副本 MySQL、NodePort 暴露数据库、弱口令、直接把 jar 拷到服务器，都不属于生产方案。

## 二、生产化总原则

| 原则 | 说明 |
| --- | --- |
| 环境隔离 | dev、staging、prod 使用不同 namespace 或不同集群 |
| 集群隔离 | 生产至少两个集群，跨可用区或跨区域 |
| 镜像不可变 | 每次构建生成唯一 tag，不使用可变 latest 上线 |
| CI/CD 分离 | Jenkins 只负责构建和推镜像，Argo CD 负责部署 |
| GitOps | 集群期望状态放在 Git，Argo CD 持续同步 |
| 最小权限 | Jenkins、Argo CD、应用使用各自 ServiceAccount |
| 密钥外置 | Vault、KMS 或 External Secrets，不把密码写进 Git |
| 可观测 | 指标、日志、链路、告警、SLO 齐全 |
| 可恢复 | 数据库、对象存储、etcd、PVC 都有备份和恢复演练 |
| 可回滚 | 镜像 tag、Git commit、Argo CD 历史都可回滚 |

## 三、总体架构

```text
开发者
  → Git 仓库
  → Jenkins CI（测试、构建、扫描、推 Harbor）
  → GitOps 仓库（镜像 tag、Kustomize/Helm 环境参数）
  → Argo CD（多集群同步）
  → 生产集群 A / 生产集群 B / Staging 集群
  → 中间件集群或云托管中间件
```

角色划分：

| 组件 | 职责 |
| --- | --- |
| Git 仓库 | 保存业务代码和 GitOps 清单 |
| Jenkins | 单元测试、构建 jar、构建镜像、漏洞扫描、推送 Harbor |
| Harbor | 私有镜像仓库，保存不可变镜像和扫描结果 |
| Argo CD | 从 GitOps 仓库同步到多个集群，负责发布、回滚、漂移检测 |
| 生产集群 A/B | 运行业务 Pod，跨可用区或跨区域 |
| Staging 集群 | 生产前的验证环境 |
| 管理集群 | 运行 Argo CD、监控、日志、备份等管理组件 |
| 中间件 | MySQL、Redis、Nacos、ES、RabbitMQ、RocketMQ、MinIO、Milvus 等 |

## 四、集群规划

### 4.1 集群数量

| 集群 | 用途 | 最低规模 |
| --- | --- | --- |
| management | Argo CD、Prometheus、Grafana、Loki、Velero、Jenkins 可选 | 3 master + 3 worker |
| staging | 生产前验证 | 3 master + 3 worker |
| prod-a | 生产可用区 A | 3 master + 6 worker 起 |
| prod-b | 生产可用区 B 或灾备 | 3 master + 6 worker 起 |

生产控制平面必须是奇数台，通常 3 台 master，etcd 与控制平面同机部署，前面挂 VIP 或外部负载均衡。

### 4.2 网络和入口

1. 每个集群独立 Pod CIDR 和 Service CIDR；
2. 集群之间不要直接复用同一网段；
3. 入口使用 ingress-nginx 或云负载均衡；
4. 证书使用 cert-manager 自动签发；
5. 全局流量使用 DNS/GSLB 或云全局负载均衡；
6. 数据库、Redis、Nacos 不暴露公网。

### 4.3 多集群管理

可选方案：

- Argo CD 多集群管理；
- Rancher 统一纳管；
- Karmada 做跨集群调度；
- 云厂商多集群控制台。

生产最常用的是 **Argo CD 管理多个集群**，每个集群注册为 Argo CD 的 destination。

## 五、中间件部署

### 5.1 中间件清单

| 中间件 | 生产建议 |
| --- | --- |
| MySQL | 云 RDS 或 InnoDB Cluster/MGR，主从、多可用区、PITR |
| Redis | 云 Redis 或 Redis Cluster/Sentinel，主从和持久化 |
| Nacos | 3 节点集群，独立数据库，开启鉴权 |
| Elasticsearch | 3 节点以上集群，副本、快照、独立磁盘 |
| RabbitMQ | 3 节点镜像队列或 Quorum Queue |
| RocketMQ | NameServer 多节点、Broker 主从、DLedger |
| MinIO | 4 节点以上纠删码集群，跨集群复制 |
| Seata | TC 集群，注册到 Nacos，独立存储 |
| Sentinel | 规则持久化到 Nacos，Dashboard 高可用 |
| Jaeger/OTel | 采集端与存储分离，使用 ES 或对象存储 |
| Milvus | 独立集群，etcd、MinIO、Pulsar/Kafka 分离 |

### 5.2 中间件和业务集群分离

生产建议把中间件放在独立集群或使用云托管服务，业务集群只保留应用 Pod。原因：

1. 中间件对磁盘、内存、网络要求不同；
2. 中间件升级和业务发布互不影响；
3. 故障隔离；
4. 备份和恢复策略不同；
5. 安全边界更清晰。

### 5.3 连接方式

业务 Pod 通过内网域名或 Service 访问中间件：

```text
MYSQL_HOST=mysql.prod.internal
REDIS_HOST=redis.prod.internal
NACOS_ADDR=nacos.prod.internal:8848
ES_URIS=https://es.prod.internal:9200
RABBITMQ_HOST=mq.prod.internal
ROCKETMQ_NAME_SERVER=rocketmq.prod.internal:9876
MINIO_ENDPOINT=https://minio.prod.internal
MILVUS_HOST=milvus.prod.internal
```

密码、证书、AccessKey 放在 Vault 或 Kubernetes Secret 中，不写进镜像和 Git。

## 六、CI：Jenkins 构建流水线

### 6.1 Jenkins 生产化

1. Jenkins 至少两节点或主备，避免单点；
2. Jenkins 控制器不跑构建，构建交给 agent；
3. agent 使用容器或专用构建节点；
4. Jenkins 凭据使用 Vault 或 Jenkins Credentials；
5. Jenkins 不直接部署生产，只负责构建和推送镜像；
6. 构建日志、制品、镜像 tag 保留审计记录。

### 6.2 流水线阶段

```text
Checkout
→ 单元测试
→ 静态代码扫描（SAST）
→ 依赖漏洞扫描（SCA）
→ Maven 构建
→ 镜像构建
→ 镜像漏洞扫描（Trivy）
→ 生成 SBOM
→ 镜像签名（cosign）
→ 推送 Harbor
→ 更新 GitOps 仓库镜像 tag
→ 触发 Argo CD 同步
```

### 6.3 关键命令

> **命令说明：** 以下命令只展示流水线中的关键动作，实际参数按环境和仓库替换。所有下载通过代理完成。

```bash
export HTTP_PROXY=http://<your-vmware-nat-ip>:7897
export HTTPS_PROXY=http://<your-vmware-nat-ip>:7897
export NO_PROXY=localhost,127.0.0.1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,.svc,.cluster.local
```

```bash
mvn -B clean verify
```

```bash
docker build -t harbor.prod.internal/gulimall/product:${GIT_SHA} .
trivy image --exit-code 1 --severity HIGH,CRITICAL harbor.prod.internal/gulimall/product:${GIT_SHA}
docker push harbor.prod.internal/gulimall/product:${GIT_SHA}
```

```bash
cosign sign --key cosign.key harbor.prod.internal/gulimall/product:${GIT_SHA}
```

更新 GitOps 仓库中的镜像 tag：

```bash
cd gitops-repo
kustomize edit set image harbor.prod.internal/gulimall/product=harbor.prod.internal/gulimall/product:${GIT_SHA}
git add .
git commit -m "release: product ${GIT_SHA}"
git push
```

## 七、CD：Argo CD GitOps

### 7.1 GitOps 仓库结构

```text
gitops/
├── base/
│   ├── product/
│   ├── order/
│   ├── gateway/
│   └── ...
├── overlays/
│   ├── staging/
│   └── prod/
└── clusters/
    ├── prod-a/
    └── prod-b/
```

### 7.2 Argo CD Application

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: gulimall-product-prod
  namespace: argocd
spec:
  project: gulimall
  source:
    repoURL: https://git.example.com/gulimall/gitops.git
    targetRevision: main
    path: overlays/prod/product
  destination:
    server: https://prod-a.k8s.internal
    namespace: gulimall
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

### 7.3 发布流程

1. Jenkins 构建镜像并更新 GitOps 仓库；
2. Argo CD 检测到 Git 变更；
3. 先同步 staging；
4. staging 验证通过后，提升到 prod-a；
5. prod-a 灰度或 canary 验证；
6. 再同步 prod-b；
7. 监控错误率、延迟、饱和度；
8. 异常时在 Argo CD 回滚到上一个 Git commit 或镜像 tag。

### 7.4 Argo CD 常用命令

```bash
argocd app list
argocd app get gulimall-product-prod
argocd app sync gulimall-product-prod
argocd app history gulimall-product-prod
argocd app rollback gulimall-product-prod <history-id>
```

## 八、Kubernetes 生产基线

### 8.1 命名空间和配额

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: gulimall
---
apiVersion: v1
kind: ResourceQuota
metadata:
  name: gulimall-quota
  namespace: gulimall
spec:
  hard:
    requests.cpu: "40"
    requests.memory: 80Gi
    limits.cpu: "80"
    limits.memory: 160Gi
    persistentvolumeclaims: "20"
```

### 8.2 LimitRange

```yaml
apiVersion: v1
kind: LimitRange
metadata:
  name: gulimall-limits
  namespace: gulimall
spec:
  limits:
    - type: Container
      default:
        cpu: "500m"
        memory: 768Mi
      defaultRequest:
        cpu: "100m"
        memory: 256Mi
```

### 8.3 应用必须配置

| 配置 | 作用 |
| --- | --- |
| requests/limits | 资源调度和隔离 |
| startupProbe | 慢启动 Java 服务不被误杀 |
| readinessProbe | 未就绪不接流量 |
| livenessProbe | 进程假死自动重启 |
| PodDisruptionBudget | 节点维护时保留副本 |
| HorizontalPodAutoscaler | 按 CPU/QPS 扩缩容 |
| topologySpreadConstraints | 多可用区打散 |
| NetworkPolicy | 限制 Pod 之间访问 |
| securityContext | 非 root、只读根文件系统、丢弃 capabilities |

MySQL、Redis、Nacos 等中间件不要和业务 Pod 混在同一个 namespace 里。

## 九、可观测性

### 9.1 指标

```text
Prometheus + Grafana + Alertmanager
kube-state-metrics
node-exporter
应用 Micrometer / Actuator
```

关键指标：

- 节点 CPU、内存、磁盘、网络；
- Pod 重启次数、Ready 状态、资源使用；
- JVM 堆、GC、线程数；
- HTTP QPS、P95/P99 延迟、错误率；
- MySQL 连接数、慢查询、主从延迟；
- Redis 内存、命中率、连接数；
- MQ 堆积、消费延迟；
- ES 集群状态、分片、磁盘水位。

### 9.2 日志

```text
Loki + Promtail 或 ELK
```

应用日志输出到 stdout，由采集器统一收集；不要在容器里长期写本地文件。

### 9.3 链路

```text
OpenTelemetry Collector + Jaeger/Tempo
```

网关、业务服务、数据库调用统一 TraceID，方便定位跨服务问题。

### 9.4 告警

告警必须能落到人：

- 企业微信/钉钉/邮件/电话；
- 告警分级；
- 值班表；
- 抑制和静默；
- 每个告警有处理手册。

## 十、安全与合规

| 项目 | 生产做法 |
| --- | --- |
| 密钥 | Vault、KMS、External Secrets，禁止明文提交 Git |
| 镜像 | Harbor 私有仓库、Trivy 扫描、cosign 签名 |
| 镜像拉取 | imagePullSecret，禁止匿名拉私有镜像 |
| 权限 | 命名空间级 ServiceAccount，最小权限 |
| 网络 | NetworkPolicy，默认拒绝，按需放行 |
| Pod 安全 | PodSecurity Standards、非 root、只读根文件系统 |
| 策略 | Kyverno/OPA Gatekeeper 限制高危配置 |
| 运行时 | Falco 检测异常进程和逃逸行为 |
| 证书 | cert-manager 自动续期 |
| 入口 | WAF、限流、TLS、访问日志 |
| 数据访问 | 堡垒机、只读账号、审批、审计 |
| 镜像来源 | 只允许 Harbor 和生产基础镜像 |

## 十一、备份与灾难恢复

### 11.1 备份范围

| 对象 | 方式 |
| --- | --- |
| 集群资源 | Velero 备份 namespace 和 PV |
| etcd | 定期快照，至少每日一次 |
| MySQL | 全量 + binlog，支持 PITR |
| Redis | AOF/RDB，主从和持久化 |
| Elasticsearch | 快照仓库 |
| MinIO | 跨集群复制或版本化 |
| Milvus | 向量数据和元数据备份 |
| Jenkins | JENKINS_HOME 备份 |
| GitOps 仓库 | 远程仓库多副本 |

### 11.2 恢复演练

1. 每季度至少一次恢复演练；
2. 明确 RTO 和 RPO；
3. 恢复后校验数据一致性和业务可用性；
4. 记录演练结果和改进项；
5. 灾难切换要有 DNS、流量和数据同步预案。

## 十二、关键组件安装命令

> **命令说明：** 以下命令是生产集群基线的代表命令，不包含全部参数和证书细节。所有下载通过代理完成。

### 12.1 kubeadm 高可用初始化

```bash
kubeadm init \
  --control-plane-endpoint="k8s-api.prod.internal:6443" \
  --upload-certs \
  --pod-network-cidr=10.244.0.0/16 \
  --service-cidr=10.96.0.0/12 \
  --cri-socket=unix:///run/containerd/containerd.sock
```

```bash
kubeadm join k8s-api.prod.internal:6443 \
  --token <token> \
  --discovery-token-ca-cert-hash sha256:<hash> \
  --control-plane \
  --certificate-key <certificate-key>
```

```bash
kubeadm join k8s-api.prod.internal:6443 \
  --token <token> \
  --discovery-token-ca-cert-hash sha256:<hash>
```

### 12.2 网络和存储

```bash
kubectl apply -f calico.yaml
kubectl apply -f local-path-storage.yaml
```

生产建议使用云盘、Ceph、Longhorn 或企业存储，不使用单节点 local-path 存放核心数据。

### 12.3 ingress-nginx

```bash
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm repo update
helm install ingress-nginx ingress-nginx/ingress-nginx \
  --namespace ingress-nginx \
  --create-namespace
```

### 12.4 cert-manager

```bash
helm repo add jetstack https://charts.jetstack.io
helm repo update
helm install cert-manager jetstack/cert-manager \
  --namespace cert-manager \
  --create-namespace \
  --set crds.enabled=true
```

### 12.5 Argo CD

```bash
helm repo add argo https://argoproj.github.io/argo-helm
helm repo update
helm install argocd argo/argo-cd \
  --namespace argocd \
  --create-namespace
```

### 12.6 Prometheus 监控

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  --create-namespace
```

### 12.7 日志

```bash
helm repo add grafana https://grafana.github.io/helm-charts
helm repo update
helm install loki grafana/loki-stack \
  --namespace logging \
  --create-namespace
```

### 12.8 Velero 备份

```bash
helm repo add vmware-tanzu https://vmware-tanzu.github.io/helm-charts
helm repo update
helm install velero vmware-tanzu/velero \
  --namespace velero \
  --create-namespace \
  --set configuration.provider=<provider> \
  --set configuration.backupStorageLocation.bucket=<bucket>
```

### 12.9 镜像扫描和策略

```bash
helm repo add aqua https://aquasecurity.github.io/helm-charts
helm repo update
helm install trivy-operator aqua/trivy-operator \
  --namespace trivy-system \
  --create-namespace
```

```bash
helm repo add kyverno https://kyverno.github.io/kyverno
helm repo update
helm install kyverno kyverno/kyverno \
  --namespace kyverno \
  --create-namespace
```

### 12.10 External Secrets

```bash
helm repo add external-secrets https://charts.external-secrets.io
helm repo update
helm install external-secrets external-secrets/external-secrets \
  --namespace external-secrets \
  --create-namespace
```

### 12.11 Harbor

Harbor 建议单独部署在管理集群或独立主机，启用 HTTPS、Trivy 扫描、镜像签名和项目级 RBAC。

```bash
helm repo add harbor https://helm.goharbor.io
helm repo update
helm install harbor harbor/harbor \
  --namespace harbor \
  --create-namespace \
  --set expose.tls.enabled=true \
  --set expose.tls.certSource=secret
```

## 十三、完整上线流程

1. 准备服务器、负载均衡、DNS、证书、存储和网络；
2. 初始化 management、staging、prod-a、prod-b 四个集群；
3. 安装 CNI、存储、ingress-nginx、cert-manager；
4. 部署 Harbor、Jenkins、Argo CD、Vault/External Secrets；
5. 部署 Prometheus、Grafana、Alertmanager、Loki、Jaeger；
6. 部署 Velero 和数据库备份；
7. 部署 MySQL、Redis、Nacos、ES、RabbitMQ、RocketMQ、MinIO、Seata、Sentinel、Milvus；
8. 准备 GitOps 仓库和 base/overlays；
9. Jenkins 构建第一个服务镜像，推送 Harbor；
10. 更新 GitOps 仓库镜像 tag；
11. Argo CD 同步 staging；
12. staging 完成功能、性能、安全验证；
13. Argo CD 同步 prod-a，进行灰度或 canary；
14. 观察指标、日志、告警；
15. 同步 prod-b；
16. 记录发布版本、变更、审批和回滚点；
17. 定期做备份恢复和故障切换演练。

## 十四、生产上线检查表

| 检查项 | 是否完成 |
| --- | --- |
| 控制平面 3 节点、etcd 高可用 |  |
| 生产至少两个集群 |  |
| 中间件独立部署或云托管 |  |
| 数据库主从、备份、PITR |  |
| Redis 主从、持久化 |  |
| Nacos、ES、MQ 集群化 |  |
| Harbor 私有仓库和镜像扫描 |  |
| Jenkins 多节点、凭据管理 |  |
| Argo CD GitOps 发布 |  |
| staging 到 prod 的审批 |  |
| HPA、PDB、探针、资源限制 |  |
| NetworkPolicy、RBAC、PodSecurity |  |
| Vault/External Secrets |  |
| Prometheus、Grafana、告警 |  |
| 日志和链路追踪 |  |
| Velero、etcd、数据库备份 |  |
| 恢复演练和故障切换演练 |  |
| 回滚流程和发布记录 |  |
| 弱口令、公网数据库、NodePort 暴露已清理 |  |
