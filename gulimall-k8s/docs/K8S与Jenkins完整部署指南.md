# gulimall-k8s 的 Kubernetes 与 Jenkins CI/CD 部署

部署目标：在 CentOS Stream 10 单节点上完成 Docker、containerd、Kubernetes、Jenkins、MySQL、Redis 和 gulimall-k8s AI 服务的安装与部署，并通过 Cloudflare Tunnel 暴露 `https://k8s.gulimall.vip`。

部署完成后，代码提交到 GitHub，Jenkins 手动触发流水线，自动完成 Maven 构建、Docker 镜像构建、推送 Docker Hub、更新 Kubernetes Deployment 和滚动发布。

## 版本基线

| 层面 | 版本或取值 |
| --- | --- |
| 操作系统 | CentOS Stream 10 (Coughlan) |
| 架构 | x86_64 |
| CPU / 内存 / 磁盘 | 8 核 / 15GiB / 根分区 66GiB |
| 主机名 | gulimall-k8s |
| 局域网 IP | <your-k8s-master-ip>（ens36） |
| NAT 网卡 | <your-vm-ip>（ens33，默认路由） |
| 代理 | http://<your-vmware-nat-ip>:7897 |
| Kubernetes | v1.34.x，kubeadm 单节点 |
| 容器运行时 | containerd 2.x |
| CNI | Flannel |
| 存储 | local-path-provisioner |
| Jenkins | 2.580.2 LTS WAR + systemd，端口 8080，手动触发 |
| JDK / Maven | JDK 21 / Maven 3.9.9 |
| MySQL / Redis | 8.4.11 / 7.2.12 |
| 应用镜像 | liujiaxucn/gulimall-k8s（Docker Hub 公开仓库） |
| 应用端口 | 18080，NodePort 30080 |
| 公网域名 | https://k8s.gulimall.vip |

## 一、Cloudflare Tunnel 公网访问配置

公网访问使用 Cloudflare Named Tunnel。Quick Tunnel 每次启动域名都会变化，而且不支持 SSE；聊天接口使用 SSE，因此必须使用 Named Tunnel。

### 1.1 前置条件

1. `gulimall.vip` 已添加到 Cloudflare，状态为 Active；
2. Cloudflare 账号可以进入 Zero Trust → Networks → Tunnels；
3. 虚拟机 `<your-k8s-master-ip>` 可以访问外网；
4. 使用新的 Tunnel `gulimall-k8s`，不复用 `.200` 上已有的 `gulimall` Tunnel。

### 1.2 安装 cloudflared

通过代理下载官方 RPM：

> **命令说明：** 在「1.2 安装 cloudflared」阶段，该命令安装 cloudflared RPM，并查看版本确认安装成功。

```bash
curl -x http://<your-vmware-nat-ip>:7897 -L \
  -o /tmp/cloudflared.rpm \
  https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-x86_64.rpm
rpm -ivh /tmp/cloudflared.rpm
cloudflared --version
```

仓库中也提供离线 RPM：`deploy/cloudflare/cloudflared-linux-x86_64.rpm`。代码克隆到 `/opt/gulimall` 后可以执行：

> **命令说明：** 在「1.2 安装 cloudflared」阶段，该命令安装 cloudflared RPM，并查看版本确认安装成功。

```bash
rpm -ivh /opt/gulimall/deploy/cloudflare/cloudflared-linux-x86_64.rpm
cloudflared --version
```

如果提示已经安装，使用下面的命令覆盖安装：

> **命令说明：** 在「1.2 安装 cloudflared」阶段，该命令安装或覆盖安装 cloudflared RPM。

```bash
rpm -Uvh --replacepkgs /tmp/cloudflared.rpm
```

### 1.3 创建 Tunnel

1. 进入 Cloudflare Dashboard；
2. 打开 Zero Trust → Networks → Tunnels；
3. 点击 Create a tunnel；
4. Connector 选择 Cloudflared；
5. Tunnel 名称填写 `gulimall-k8s`；
6. 运行环境选择 Red Hat；
7. 复制页面上的安装命令。

安装命令的格式如下，`<TOKEN>` 使用 Dashboard 生成的真实值：

> **命令说明：** 在「1.3 创建 Tunnel」阶段，该命令把 cloudflared 注册为系统服务，并用 Tunnel Token 启动连接。

```bash
cloudflared service install <TOKEN>
systemctl enable --now cloudflared
systemctl status cloudflared --no-pager
```

确认 Tunnel 已经连接：

> **命令说明：** 在「1.3 创建 Tunnel」阶段，该命令查看 systemd 服务日志，定位启动和连接问题。

```bash
journalctl -u cloudflared -n 50 --no-pager | grep -i 'Registered tunnel connection'
```

日志中出现 `Registered tunnel connection` 表示连接成功。

### 1.4 添加 Public Hostname

在 Tunnel 详情页进入 Routes → Add route → Public hostname，填写：

| 字段 | 值 |
| --- | --- |
| Subdomain | k8s |
| Domain | gulimall.vip |
| Path | 留空 |
| Type | HTTP |
| Service URL | http://127.0.0.1:30080 |

保存后 Cloudflare 会自动创建 `k8s.gulimall.vip` 的 DNS 记录。

此时应用还没有部署，访问 `https://k8s.gulimall.vip` 返回 502 属于正常现象；应用部署完成并且 NodePort 30080 就绪后会恢复。

### 1.5 缓存和优化设置

聊天接口和页面是动态内容，不允许 Cloudflare 缓存。进入 Caching → Cache Rules → Create rule：

> **命令说明：** 在「1.5 缓存和优化设置」阶段，这是 Cloudflare Cache Rule 的匹配表达式，用于过滤域名或路径。

```text
(http.host eq "k8s.gulimall.vip")
```

Then 选择 **Bypass cache**。

进入 Speed → Setting → Content Optimization，设置：

| 选项 | 值 |
| --- | --- |
| Rocket Loader | Off |
| Auto Minify | Off |
| Brotli | On |

Rocket Loader 会异步改写 JavaScript，容易让聊天页面的原生 JS 失效。

### 1.6 SSE 说明

1. Named Tunnel 支持 SSE，Quick Tunnel 不支持；
2. Cloudflare 免费版代理存在约 100 秒超时，应用的流式超时配置为 90 秒；
3. 如果模型长时间没有输出 token，连接可能被 Cloudflare 断开并返回 524；
4. 当前链路是 Cloudflare Tunnel 直接访问 NodePort，没有经过 Nginx；后续如果增加 ingress-nginx，需要关闭 `proxy_buffering`；
5. Cloudflare 缓存规则必须绕过 `/` 和 `/api/*`，否则聊天和登录会被缓存。

### 1.7 Cloudflare Access

当前部署不开启 Cloudflare Access，登录门槛由应用邀请码承担。

公网开放后，任何拿到邀请码的人都可以注册和登录，并消耗服务端 AI Key。需要更高安全性时，可以在 Cloudflare Zero Trust → Access → Applications 中为 `k8s.gulimall.vip` 建立 Self-hosted 应用，只允许指定邮箱访问；SSE 请求会携带 Access Cookie，可以正常工作。

### 1.8 常见问题

| 现象 | 排查 |
| --- | --- |
| 502 Bad Gateway | `systemctl status cloudflared`；`curl -I http://127.0.0.1:30080/`；`kubectl -n gulimall-k8s get svc,pods` |
| 530 | Tunnel 未连接；检查 `journalctl -u cloudflared` 和外网出站 |
| 524 | 模型响应超过 Cloudflare 超时；检查应用日志和模型服务 |
| SSE 没有流式效果 | 关闭 Rocket Loader；确认 Cache Rule 已绕过 `/api/*` |
| 修改配置后仍返回旧页面 | Cloudflare 缓存；执行 Purge Cache，或确认 Cache Rule |
| Tunnel 一直不 Healthy | 检查 UDP 7844 是否被网络限制；可把 cloudflared 改成 HTTP/2 传输 |

强制使用 HTTP/2 的方法：

> **命令说明：** 在「1.8 常见问题」阶段，该命令打开 systemd 服务覆盖配置，修改 cloudflared 启动参数。

```bash
systemctl edit cloudflared
```

在打开的编辑器中加入：

> **命令说明：** 在「1.8 常见问题」阶段，这是 systemd 服务覆盖配置，用于调整 cloudflared 的启动参数。

```ini
[Service]
ExecStart=
ExecStart=/usr/bin/cloudflared --no-autoupdate tunnel run --protocol http2 --token <TOKEN>
```

保存后执行：

> **命令说明：** 在「1.8 常见问题」阶段，该命令重新加载 systemd 配置，使新增或修改的服务生效。

```bash
systemctl daemon-reload
systemctl restart cloudflared
```

## 二、部署总览

部署顺序：

1. Cloudflare Tunnel；
2. 系统准备：主机名、代理、swap、SELinux、防火墙、内核参数；
3. Docker CE 和 containerd；
4. kubeadm 单节点 Kubernetes、Flannel、local-path；
5. Jenkins、Maven、GitHub 和 Docker Hub 凭据；
6. 创建 namespace、Secret、MySQL、Redis；
7. Jenkins 构建应用镜像并部署到 Kubernetes；
8. Cloudflare、健康检查、登录和聊天验证；
9. 日常运维、日志、回滚和排错。

资源分配：

| 组件 | 内存 | 磁盘 |
| --- | ---: | ---: |
| 系统 + containerd + kubelet | 1～1.5GiB | 8～10GiB |
| kubeadm 控制平面 | 1.2～1.5GiB | 2～3GiB |
| Jenkins（空闲/构建） | 1～1.5GiB / 3～4GiB | 3～5GiB |
| MySQL | 0.6～1GiB | 10GiB PVC |
| Redis | 0.2～0.5GiB | 2GiB PVC |
| gulimall-k8s 应用 | 0.5～0.8GiB | 0.5～1GiB |
| 镜像和日志 | — | 5～10GiB |

单节点说明：

1. 只有一个控制平面和一个 etcd，节点宕机时全部服务不可用；
2. 适合学习和 CI/CD 流程练习，不具备高可用；
3. 业务 Pod 可以调度到控制平面节点，需要移除控制平面污点；
4. MySQL 和 Redis 都是单副本，数据保存在 local-path 的本地目录。

## 三、系统准备

所有命令在 `<your-k8s-master-ip>` 上以 root 执行。

### 3.1 设置主机名和 hosts

> **命令说明：** 在「3.1 设置主机名和 hosts」阶段，该命令验证主机名解析或网络连通性。

```bash
hostnamectl set-hostname gulimall-k8s
grep -q "gulimall-k8s" /etc/hosts || echo "<your-k8s-master-ip> gulimall-k8s" >> /etc/hosts
hostname
ping -c 1 gulimall-k8s
```

### 3.2 配置代理

当前会话生效：

> **命令说明：** 在「3.2 配置代理」阶段，该命令配置当前 shell 的代理环境变量，让后续下载走代理。

```bash
export http_proxy=http://<your-vmware-nat-ip>:7897
export https_proxy=http://<your-vmware-nat-ip>:7897
export no_proxy=localhost,127.0.0.1,192.168.119.0/24,<your-subnet>/24,10.244.0.0/16,10.96.0.0/12,.svc,.cluster.local
```

永久生效：

> **命令说明：** 在「3.2 配置代理」阶段，该命令写入永久代理配置，并让新登录的 shell 自动加载。

```bash
cat > /etc/profile.d/proxy.sh <<'EOF'
export http_proxy=http://<your-vmware-nat-ip>:7897
export https_proxy=http://<your-vmware-nat-ip>:7897
export no_proxy=localhost,127.0.0.1,192.168.119.0/24,<your-subnet>/24,10.244.0.0/16,10.96.0.0/12,.svc,.cluster.local
EOF
chmod +x /etc/profile.d/proxy.sh
source /etc/profile.d/proxy.sh
```

让 dnf 也走代理：

> **命令说明：** 在「3.2 配置代理」阶段，该命令检查 dnf 是否已有代理配置，不存在时追加，然后刷新仓库缓存。

```bash
grep -q '^proxy=' /etc/dnf/dnf.conf || echo 'proxy=http://<your-vmware-nat-ip>:7897' >> /etc/dnf/dnf.conf
dnf makecache
```

验证外网：

> **命令说明：** 在「3.2 配置代理」阶段，该命令通过代理测试地址是否可访问。

```bash
curl -I https://download.docker.com
curl -I https://github.com
curl -I https://pkgs.k8s.io
```

`no_proxy` 中的集群网段和 Pod/Service 网段必须保留，否则节点内部通信会走代理，出现随机超时。

### 3.3 关闭 swap

> **命令说明：** 在「3.3 关闭 swap」阶段，该命令关闭 swap 并禁止开机自动挂载。

```bash
swapoff -a
sed -ri 's/.*swap.*/#&/' /etc/fstab
free -h
swapon --show
```

`free -h` 的 Swap 一行必须全是 0，`swapon --show` 必须没有输出。

### 3.4 设置 SELinux 为 permissive

> **命令说明：** 在「3.4 设置 SELinux 为 permissive」阶段，该命令临时调整 SELinux 模式。

```bash
setenforce 0
sed -i 's/^SELINUX=enforcing/SELINUX=permissive/' /etc/selinux/config
getenforce
```

输出为 `Permissive` 即可。生产环境应保持 Enforcing 并加载正确的策略。

### 3.5 关闭 firewalld

> **命令说明：** 在「3.5 关闭 firewalld」阶段，该命令查看服务是否正在运行。

```bash
systemctl disable --now firewalld
systemctl is-active firewalld
```

输出为 `inactive` 即可。

如果必须保留 firewalld，至少放行：

| 端口/网段 | 用途 |
| --- | --- |
| 6443/tcp | kube-apiserver |
| 10250/tcp | kubelet |
| 8472/udp | Flannel VXLAN |
| 30080/tcp | 应用 NodePort（Cloudflare Tunnel 使用本机回环） |
| 10.244.0.0/16 | Pod 网段 |
| 10.96.0.0/12 | Service 网段 |

### 3.6 时间同步

> **命令说明：** 在「3.6 时间同步」阶段，该命令设置服务开机自启并立即启动。

```bash
systemctl enable --now chronyd
chronyc sources -v
```

### 3.7 内核模块和 sysctl

> **命令说明：** 在「3.7 内核模块和 sysctl」阶段，该命令写入配置文件，后续服务或脚本会读取该文件。

```bash
cat > /etc/modules-load.d/k8s.conf <<'EOF'
overlay
br_netfilter
EOF
modprobe overlay
modprobe br_netfilter
lsmod | grep -E 'overlay|br_netfilter'

cat > /etc/sysctl.d/99-kubernetes.conf <<'EOF'
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
EOF
sysctl --system
sysctl net.ipv4.ip_forward
```

### 3.8 确认 cgroup v2

> **命令说明：** 在「3.8 确认 cgroup v2」阶段，该命令确认系统 cgroup 版本。

```bash
stat -fc %T /sys/fs/cgroup/
```

输出必须是 `cgroup2fs`。CentOS Stream 10 默认使用 cgroup v2。

### 3.9 确认磁盘和内存

> **命令说明：** 在「3.9 确认磁盘和内存」阶段，该命令查看磁盘、内存和 CPU 状态。

```bash
df -h /
free -h
```

根分区应剩余约 60GiB，内存约 15GiB。

## 四、安装 Docker CE 和 containerd

Docker 只负责构建镜像；Kubernetes 的 Pod 由 containerd 运行。两个组件都需要配置代理。

### 4.1 确认 releasever

> **命令说明：** 在「4.1 确认 releasever」阶段，该命令查询系统发行版或软件包版本。

```bash
rpm -q --qf '%{VERSION}\n' centos-stream-release
```

输出应为 `10`。

### 4.2 配置 Docker CE 仓库

> **命令说明：** 在「4.2 配置 Docker CE 仓库」阶段，该命令写入配置文件，后续服务或脚本会读取该文件。

```bash
cat > /etc/yum.repos.d/docker-ce.repo <<'EOF'
[docker-ce-stable]
name=Docker CE Stable - $basearch
baseurl=https://download.docker.com/linux/rhel/$releasever/$basearch/stable
enabled=1
gpgcheck=1
gpgkey=https://download.docker.com/linux/rhel/gpg
proxy=http://<your-vmware-nat-ip>:7897
EOF

dnf makecache
dnf repolist | grep -i docker
```

`dnf` 4.x 不读取 `/etc/dnf/dnf.conf.d/`，代理必须写在 repo 段里。

### 4.3 安装 Docker CE

> **命令说明：** 在「4.3 安装 Docker CE」阶段，该命令通过代理安装软件包。

```bash
dnf install -y docker-ce docker-ce-cli containerd.io \
  docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
docker version
```

`docker version` 的 Server 段有输出，说明 Docker 守护进程正常。

### 4.4 配置 Docker 代理

> **命令说明：** 在「4.4 配置 Docker 代理」阶段，该命令配置 Docker 守护进程代理，并重启 Docker 使配置生效。

```bash
mkdir -p /etc/systemd/system/docker.service.d
cat > /etc/systemd/system/docker.service.d/http-proxy.conf <<'EOF'
[Service]
Environment="HTTP_PROXY=http://<your-vmware-nat-ip>:7897"
Environment="HTTPS_PROXY=http://<your-vmware-nat-ip>:7897"
Environment="NO_PROXY=localhost,127.0.0.1,192.168.119.0/24,<your-subnet>/24,10.244.0.0/16,10.96.0.0/12,.svc,.cluster.local"
EOF
systemctl daemon-reload
systemctl restart docker
systemctl show --property=Environment docker
docker run --rm hello-world
```

### 4.5 生成 containerd 配置

> **命令说明：** 在「4.5 生成 containerd 配置」阶段，该命令生成 containerd 配置或重启 containerd。

```bash
systemctl enable --now containerd
containerd config default > /etc/containerd/config.toml
sed -i 's/SystemdCgroup = false/SystemdCgroup = true/g' /etc/containerd/config.toml
grep -n "SystemdCgroup" /etc/containerd/config.toml
grep -n "sandbox_image" /etc/containerd/config.toml
```

如果 `grep SystemdCgroup` 没有输出，在 `[plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.runc.options]` 段下加入：

> **命令说明：** 在「4.5 生成 containerd 配置」阶段，这是 containerd 配置片段，用于指定 systemd cgroup 驱动。

```toml
SystemdCgroup = true
```

`SystemdCgroup` 必须为 true，否则节点会一直 NotReady。

### 4.6 配置 containerd 代理

> **命令说明：** 在「4.6 配置 containerd 代理」阶段，该命令配置 containerd 代理，并重启 containerd 使配置生效。

```bash
mkdir -p /etc/systemd/system/containerd.service.d
cat > /etc/systemd/system/containerd.service.d/http-proxy.conf <<'EOF'
[Service]
Environment="HTTP_PROXY=http://<your-vmware-nat-ip>:7897"
Environment="HTTPS_PROXY=http://<your-vmware-nat-ip>:7897"
Environment="NO_PROXY=localhost,127.0.0.1,192.168.119.0/24,<your-subnet>/24,10.244.0.0/16,10.96.0.0/12,.svc,.cluster.local"
EOF
systemctl daemon-reload
systemctl restart containerd
systemctl show --property=Environment containerd
```

验证 containerd 能拉取 Kubernetes 镜像：

> **命令说明：** 在「4.6 配置 containerd 代理」阶段，该命令使用 containerd 拉取或删除镜像，验证代理。

```bash
ctr -n k8s.io images pull registry.k8s.io/pause:3.10
ctr -n k8s.io images rm registry.k8s.io/pause:3.10
```

## 五、安装 Kubernetes 单节点集群

### 5.1 配置 Kubernetes 仓库

> **命令说明：** 在「5.1 配置 Kubernetes 仓库」阶段，该命令写入配置文件，后续服务或脚本会读取该文件。

```bash
cat > /etc/yum.repos.d/kubernetes.repo <<'EOF'
[kubernetes]
name=Kubernetes
baseurl=https://pkgs.k8s.io/core:/stable:/v1.34/rpm/
enabled=1
gpgcheck=1
gpgkey=https://pkgs.k8s.io/core:/stable:/v1.34/rpm/repodata/repomd.xml.key
exclude=kubelet kubeadm kubectl cri-tools kubernetes-cni
proxy=http://<your-vmware-nat-ip>:7897
EOF

dnf makecache
dnf repolist | grep -i kubernetes
```

`proxy=` 必须写在 `[kubernetes]` 段内，否则 dnf 无法访问 `pkgs.k8s.io`。

### 5.2 安装 kubeadm、kubelet、kubectl

> **命令说明：** 在「5.2 安装 kubeadm、kubelet、kubectl」阶段，该命令通过代理安装软件包。

```bash
dnf install -y kubelet kubeadm kubectl cri-tools --disableexcludes=kubernetes
kubeadm version -o short
kubelet --version
kubectl version --client

dnf install -y dnf-plugins-core python3-dnf-plugin-versionlock
dnf versionlock add kubelet kubeadm kubectl
systemctl enable kubelet
```

此时不要手动启动 kubelet。执行 `kubeadm init` 前没有配置文件，kubelet 启动失败是正常现象。

### 5.3 初始化集群

先执行预检：

> **命令说明：** 在「5.3 初始化集群」阶段，该命令执行 kubeadm 预检，提前发现系统、端口和镜像问题。

```bash
kubeadm init --dry-run
```

预检通过后执行初始化：

> **命令说明：** 在「5.3 初始化集群」阶段，该命令初始化 Kubernetes 控制平面，指定 API Server 地址、Pod 网段和 containerd。

```bash
kubeadm init \
  --apiserver-advertise-address=<your-k8s-master-ip> \
  --pod-network-cidr=10.244.0.0/16 \
  --kubernetes-version=$(kubeadm version -o short) \
  --cri-socket=unix:///run/containerd/containerd.sock
```

参数说明：

| 参数 | 作用 |
| --- | --- |
| `--apiserver-advertise-address` | API Server 对外公布 `<your-k8s-master-ip>`，Jenkins 和局域网客户端都连这个地址 |
| `--pod-network-cidr` | Flannel 默认使用 `10.244.0.0/16` |
| `--kubernetes-version` | 使用当前安装的版本，避免手写版本号 |
| `--cri-socket` | 明确指定 containerd，避免 kubeadm 探测到 Docker |

初始化成功后会输出一段 `kubeadm join` 命令。单节点部署不需要执行 join，可以忽略。

配置 kubectl：

> **命令说明：** 在「5.3 初始化集群」阶段，该命令复制管理员 kubeconfig，让 root 可以直接使用 kubectl。

```bash
mkdir -p $HOME/.kube
cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
chown $(id -u):$(id -g) $HOME/.kube/config
kubectl cluster-info
kubectl get nodes
```

此时节点状态为 `NotReady`，因为还没有安装 CNI。

### 5.4 安装 Flannel

> **命令说明：** 在「5.4 安装 Flannel」阶段，该命令通过代理下载安装包或配置文件，供后续安装使用。

```bash
curl -x http://<your-vmware-nat-ip>:7897 -L \
  -o /tmp/kube-flannel.yml \
  https://github.com/flannel-io/flannel/releases/latest/download/kube-flannel.yml
kubectl apply -f /tmp/kube-flannel.yml
kubectl -n kube-flannel get pods -o wide
```

等待 1～3 分钟，节点变为 Ready：

> **命令说明：** 在「5.4 安装 Flannel」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl get nodes -o wide
```

### 5.5 移除控制平面污点

单节点集群必须允许业务 Pod 调度到控制平面：

> **命令说明：** 在「5.5 移除控制平面污点」阶段，该命令移除单节点控制平面污点，让业务 Pod 可以调度。

```bash
kubectl taint nodes --all node-role.kubernetes.io/control-plane-
kubectl get nodes -o wide
```

多节点生产集群不要移除这个污点。

### 5.6 验证集群

> **命令说明：** 在「5.6 验证集群」阶段，该命令删除测试 Pod、临时 Service 或其他资源。

```bash
kubectl get nodes
kubectl get pods -n kube-system -o wide
kubectl run test-nginx --image=nginx:alpine --restart=Never
kubectl get pod test-nginx -o wide
kubectl delete pod test-nginx
```

所有 `kube-system` Pod 必须是 Running；测试 Pod 能启动说明 CNI 和镜像拉取正常。

### 5.7 安装 local-path 存储

MySQL 和 Redis 使用 PVC，需要默认 StorageClass。

> **命令说明：** 在「5.7 安装 local-path 存储」阶段，该命令通过代理下载安装包或配置文件，供后续安装使用。

```bash
curl -x http://<your-vmware-nat-ip>:7897 -L \
  -o /tmp/local-path-storage.yaml \
  https://raw.githubusercontent.com/rancher/local-path-provisioner/v0.0.30/deploy/local-path-storage.yaml
kubectl apply -f /tmp/local-path-storage.yaml
kubectl -n local-path-storage get pods
kubectl get storageclass

kubectl patch storageclass local-path \
  -p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'
kubectl get storageclass
```

`local-path` 的 StorageClass 必须带有 `(default)` 标记。数据默认保存在节点 `/opt/local-path-provisioner`，位于 66GiB 根分区内。

### 5.8 kubelet 资源预留

kubelet 默认把整机内存都算作可分配，业务 Pod 吃满后系统组件会被挤出内存。编辑：

> **命令说明：** 在「5.8 kubelet 资源预留」阶段，按该步骤目标执行这段命令，完成后对照输出检查结果。

```bash
vi /var/lib/kubelet/config.yaml
```

在文件顶层加入或修改以下字段，缩进与 `apiVersion` 同级：

> **命令说明：** 在「5.8 kubelet 资源预留」阶段，这是 kubelet 资源预留配置片段，合并到 /var/lib/kubelet/config.yaml 后重启 kubelet。

```yaml
systemReserved:
  cpu: "500m"
  memory: "1Gi"
  ephemeral-storage: "2Gi"
kubeReserved:
  cpu: "500m"
  memory: "1Gi"
evictionHard:
  memory.available: "300Mi"
  nodefs.available: "10%"
```

如果文件里已经存在同名段，只修改值，不要重复添加。

重启并确认：

> **命令说明：** 在「5.8 kubelet 资源预留」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
systemctl restart kubelet
kubectl describe node gulimall-k8s | grep -A6 Allocatable
```

`Allocatable` 的 memory 应该约为 13GiB。

### 5.9 安装 metrics-server（可选）

> **命令说明：** 在「5.9 安装 metrics-server（可选）」阶段，该命令通过代理下载安装包或配置文件，供后续安装使用。

```bash
curl -x http://<your-vmware-nat-ip>:7897 -L \
  -o /tmp/metrics-server.yaml \
  https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
kubectl apply -f /tmp/metrics-server.yaml

kubectl -n kube-system patch deployment metrics-server --type=json \
  -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'

kubectl -n kube-system get pods | grep metrics
kubectl top nodes
```

首次安装后 metrics 数据需要 1～2 分钟才出现。

### 5.10 获取部署文件

在 Windows 开发机仓库根目录执行，把新模块和部署文件推送到 GitHub：

> **命令说明：** 在「5.10 获取部署文件」阶段，该命令提交并推送代码到 GitHub。

```cmd
cd /d D:\SoftwareInstallation\gulimall && git add gulimall-k8s && git commit -m "feat(gulimall-k8s):新增独立AI对话模块、邀请码、12小时登录态和k8s部署文件" && git push origin develop/gulimall/v0.1
```

#### 5.10.1 安装 Git

虚拟机默认可能没有 Git，克隆代码前先安装：

> **命令说明：** 在「5.10.1 安装 Git」阶段，该命令通过代理安装软件包。

```bash
dnf install -y git
git --version
```

#### 5.10.2 准备 GitHub 凭据

GitHub 已经不支持用账号密码做 Git 鉴权，密码处必须填 Personal Access Token（PAT）。

私有仓库推荐直接使用 Classic PAT + `repo` 权限：

1. GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)；
2. Generate new token (classic)；
3. Scope 勾选 `repo`；
4. 生成并复制 Token。
<your-github-pat>

清理可能缓存的旧凭据：

> **命令说明：** 在「5.10.2 准备 GitHub 凭据」阶段，该命令配置 Git 代理或清理旧的凭据缓存。

```bash
git config --global --unset credential.helper
rm -f ~/.git-credentials
```

#### 5.10.3 克隆仓库

> **命令说明：** 在「5.10.3 克隆仓库」阶段，该命令克隆 GitHub 仓库。

```bash
git config --global http.proxy http://<your-vmware-nat-ip>:7897
git config --global https.proxy http://<your-vmware-nat-ip>:7897
cd /opt
git clone https://github.com/liu-jiaxu/gulimall.git
cd /opt/gulimall
git checkout develop/gulimall/v0.1
ls gulimall-k8s/k8s
```

克隆提示：

> **命令说明：** 在「5.10.3 克隆仓库」阶段，这是 Git 克隆时的输入提示，用户名填 GitHub 用户名，密码填 PAT。

```text
Username for 'https://github.com': liu-jiaxu
Password for 'https://liu-jiaxu@github.com': <粘贴 PAT，不粘贴账号密码>
```

用户名是 `liu-jiaxu`（一个连字符）。

如果目录已经存在：

> **命令说明：** 在「5.10.3 克隆仓库」阶段，该命令拉取仓库最新代码。

```bash
cd /opt/gulimall
git pull
```

拉取后确认以下文件存在：

> **命令说明：** 在「5.10.3 克隆仓库」阶段，这是仓库文件清单，用于确认部署文件完整。

```text
gulimall-k8s/Dockerfile
gulimall-k8s/Jenkinsfile
gulimall-k8s/sql/init.sql
gulimall-k8s/k8s/00-namespace.yaml
gulimall-k8s/k8s/10-mysql.yaml
gulimall-k8s/k8s/20-redis.yaml
gulimall-k8s/k8s/30-app.yaml
gulimall-k8s/k8s/40-jenkins-rbac.yaml
```

创建 namespace：

> **命令说明：** 在「5.10.3 克隆仓库」阶段，该命令把 YAML 清单提交给 Kubernetes，创建或更新对应资源。

```bash
kubectl apply -f /opt/gulimall/gulimall-k8s/k8s/00-namespace.yaml
kubectl get namespace gulimall-k8s
```

## 六、安装和配置 Jenkins

### 6.1 安装 JDK 21 和 Git

> **命令说明：** 在「6.1 安装 JDK 21 和 Git」阶段，该命令通过代理安装软件包。

```bash
dnf install -y java-21-openjdk java-21-openjdk-devel git
java -version
javac -version
git --version
ls -d /usr/lib/jvm/java-21-openjdk*
```

Git 在 5.10.1 已经安装；dnf 会自动跳过已安装的包。

确认 Java 路径，后面 systemd 配置需要用到：

> **命令说明：** 在「6.1 安装 JDK 21 和 Git」阶段，该命令查看 Java 实际安装路径。

```bash
readlink -f $(which java)
```

### 6.2 安装 Maven 3.9.9

> **命令说明：** 在「6.2 安装 Maven 3.9.9」阶段，该命令通过代理下载安装包或配置文件，供后续安装使用。

```bash
cd /tmp
curl -x http://<your-vmware-nat-ip>:7897 -L \
  -o apache-maven-3.9.9-bin.tar.gz \
  https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz

tar -xzf apache-maven-3.9.9-bin.tar.gz -C /opt
ln -sfn /opt/apache-maven-3.9.9 /opt/maven
ln -sfn /opt/maven/bin/mvn /usr/bin/mvn
mvn -v
```

### 6.3 创建 Jenkins 用户

> **命令说明：** 在「6.3 创建 Jenkins 用户」阶段，该命令创建 Jenkins 用户并加入 docker 组。

```bash
useradd --create-home --home-dir /var/lib/jenkins --shell /bin/bash jenkins
usermod -aG docker jenkins
id jenkins
```

`id jenkins` 的输出中必须包含 `docker` 组。

### 6.4 配置 Maven 代理

为 root 创建 Maven 配置：

> **命令说明：** 在「6.4 配置 Maven 代理」阶段，该命令写入配置文件，后续服务或脚本会读取该文件。

```bash
mkdir -p /root/.m2
cat > /root/.m2/settings.xml <<'EOF'
<settings>
  <proxies>
    <proxy>
      <id>local-proxy</id>
      <active>true</active>
      <protocol>http</protocol>
      <host><your-vmware-nat-ip></host>
      <port>7897</port>
      <nonProxyHosts>localhost|127.0.0.1|192.168.*|10.*</nonProxyHosts>
    </proxy>
  </proxies>
</settings>
EOF
```

为 Jenkins 用户复制同一份配置：

> **命令说明：** 在「6.4 配置 Maven 代理」阶段，该命令创建目录。

```bash
mkdir -p /var/lib/jenkins/.m2
cp /root/.m2/settings.xml /var/lib/jenkins/.m2/settings.xml
chown -R jenkins:jenkins /var/lib/jenkins/.m2
```

### 6.5 下载 Jenkins WAR

> **命令说明：** 在「6.5 下载 Jenkins WAR」阶段，该命令通过代理下载安装包或配置文件，供后续安装使用。

```bash
mkdir -p /opt/jenkins
curl -fL --retry 5 --retry-delay 3 --connect-timeout 20 \
  -x http://<your-vmware-nat-ip>:7897 \
  -o /opt/jenkins/jenkins.war \
  https://get.jenkins.io/war-stable/latest/jenkins.war
ls -lh /opt/jenkins/jenkins.war
file /opt/jenkins/jenkins.war
jar tf /opt/jenkins/jenkins.war | head
```

`file` 应返回 `Java archive data (JAR)`，文件大小约 100MB；`jar tf` 能列出 `META-INF/`、`WEB-INF/` 才算完整。没有 `file` 命令时执行 `dnf install -y file`。

如果文件只有几 KB、开头是 HTML，说明代理返回了错误页，删除后重新走代理下载：

> **命令说明：** 在「6.5 下载 Jenkins WAR」阶段，该命令通过代理下载安装包或配置文件，供后续安装使用。

```bash
rm -f /opt/jenkins/jenkins.war
curl -fL --retry 5 --retry-delay 3 --connect-timeout 20 \
  -x http://<your-vmware-nat-ip>:7897 \
  -o /opt/jenkins/jenkins.war \
  https://get.jenkins.io/war-stable/latest/jenkins.war
```

所有下载都走代理 `<your-vmware-nat-ip>:7897`。如果仍然失败，检查代理连通性和 `curl` 的输出，不要改用直连或其它下载源。

下载完成后再次执行 `file` 和 `jar tf` 校验。

### 6.6 配置 Jenkins systemd 服务

> **命令说明：** 在「6.6 配置 Jenkins systemd 服务」阶段，该命令写入配置文件，后续服务或脚本会读取该文件。

```bash
cat > /etc/systemd/system/jenkins.service <<'EOF'
[Unit]
Description=Jenkins
After=network.target docker.service
Requires=docker.service

[Service]
User=jenkins
Group=jenkins
Environment="JENKINS_HOME=/var/lib/jenkins"
Environment="JAVA_HOME=/usr/lib/jvm/java-21-openjdk"
Environment="JAVA_OPTS=-Xms512m -Xmx1g -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"
Environment="HTTP_PROXY=http://<your-vmware-nat-ip>:7897"
Environment="HTTPS_PROXY=http://<your-vmware-nat-ip>:7897"
Environment="NO_PROXY=localhost,127.0.0.1,192.168.119.0/24,<your-subnet>/24,10.244.0.0/16,10.96.0.0/12,.svc,.cluster.local"
Environment="PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/maven/bin"
ExecStart=/usr/bin/java -jar /opt/jenkins/jenkins.war --httpPort=8080
Restart=on-failure
RestartSec=10
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now jenkins
systemctl status jenkins --no-pager
```

如果 `/usr/lib/jvm/java-21-openjdk` 不存在，把 `JAVA_HOME` 改成 `ls -d /usr/lib/jvm/java-21-openjdk*` 实际返回的路径。

查看启动日志：

> **命令说明：** 在「6.6 配置 Jenkins systemd 服务」阶段，该命令查看 systemd 服务日志，定位启动和连接问题。

```bash
journalctl -u jenkins -f
```

获取 Jenkins 初始管理员密码：

> **命令说明：** 在「6.6 配置 Jenkins systemd 服务」阶段，该命令读取 Jenkins 初始管理员密码，用于浏览器 Setup Wizard。

```bash
cat /var/lib/jenkins/secrets/initialAdminPassword
```
<your-jenkins-password>


### 6.7 Jenkins 初始化

1. 浏览器打开 `http://<your-k8s-master-ip>:8080`；
2. 输入初始管理员密码；
3. 选择 Install suggested plugins；
4. 创建 Jenkins 管理员账号；gulimall <your-password>
5. Jenkins URL 填写 http://<your-k8s-master-ip>:8080/
6. 保存并完成。

### 6.8 安装额外插件

进入 Manage Jenkins → Plugins → Available plugins，搜索并安装：

| 插件 | 是否必需 | 用途 |
| --- | --- | --- |
| Git | 必需 | checkout scm 拉取代码 |
| Pipeline | 必需 | 声明式流水线 |
| Pipeline: Declarative | 必需 | Jenkinsfile 的 pipeline 语法 |
| Credentials Binding | 必需 | withCredentials 注入凭据 |
| Docker Pipeline | 可选 | 流水线直接调用 shell docker，不装也能跑 |
| Kubernetes CLI | 可选 | 流水线直接调用 shell kubectl，不装也能跑 |

部分插件Jenkins已经安装完成，插件安装过程中常见若干可选插件报错，例如 Ant、Gradle、Subversion、Mercurial、LDAP、PAM、Mailer、SSH Build Agents 等。这些插件不参与当前流水线，报错可以忽略。

检查必需插件：

1. Manage Jenkins → Plugins → Installed plugins；
2. 搜索 Git、Pipeline、Credentials Binding；
3. 确认 Pipeline: Declarative 已安装。

如果必需插件安装失败，进入 Manage Jenkins → System → HTTP Proxy Configuration，配置：

| 字段 | 值 |
| --- | --- |
| Server | <your-vmware-nat-ip> |
| Port | 7897 |
| No Proxy Hosts | localhost、127.0.0.1、<your-k8s-master-ip>、192.168.119.0/24、<your-subnet>/24 |
| Test URL | https://updates.jenkins.io |

No Proxy Hosts 在页面上每行填一个。

Jenkins 的 HTTP 代理只作用于 Jenkins 自身和插件下载；流水线里的 `git`、`mvn`、`docker`、`kubectl` 仍然使用 systemd 环境变量、Maven settings、Docker/containerd 代理配置。

保存后重新安装失败项。代理仍然失败时检查代理本身，不要改用直连或其它下载源。

重启 Jenkins：

方式一：浏览器登录后打开安全重启地址，等待当前构建结束：

> **命令说明：** 在「6.8 安装额外插件」阶段，这是访问地址或下载地址，用于浏览器或 curl 访问。

```text
http://<your-k8s-master-ip>:8080/safeRestart
```

方式二：浏览器打开强制重启地址：

> **命令说明：** 在「6.8 安装额外插件」阶段，这是访问地址或下载地址，用于浏览器或 curl 访问。

```text
http://<your-k8s-master-ip>:8080/restart
```

方式三：在虚拟机上使用 systemd：

> **命令说明：** 在「6.8 安装额外插件」阶段，该命令查看服务是否正在运行。

```bash
systemctl restart jenkins
systemctl status jenkins --no-pager
journalctl -u jenkins -f
```

重启后等待 30～60 秒，再访问 `http://<your-k8s-master-ip>:8080/login`。

### 6.9 配置 JDK、Maven 和 Git

进入 Manage Jenkins → Tools（部分界面显示为 Global Tool Configuration）：

| 工具 | 配置 |
| --- | --- |
| JDK | 名称 `jdk21`，JAVA_HOME 填 `/usr/lib/jvm/java-21-openjdk` |
| Maven | 名称 `maven3`，MAVEN_HOME 填 `/opt/maven` |
| Git | 名称 `Default`，Path 填 `/usr/bin/git` |

流水线直接使用系统 `mvn` 和 `docker`，工具配置主要用于 Jenkins 页面展示和后续扩展。

### 6.10 创建 Jenkins 凭据

进入 Manage Jenkins → Credentials → System → Global credentials (unrestricted) → Add Credentials。

#### 6.10.1 GitHub PAT

私有仓库优先使用 Classic PAT + `repo` 权限，权限配置最直接，出现 403 的概率最低。

在 Jenkins 中新增：

| 字段 | 值 |
| --- | --- |
| Kind | Username with password |
| ID | github-pat |
| Username | liu-jiaxu |
| Password | Classic PAT（repo 权限）或 Fine-grained PAT |
| Description | GitHub PAT |

Classic PAT 的 `repo` 权限包含写权限，用于只读拉取也正常；需要最小权限时使用 Fine-grained 只读 Token。

如果 Jenkins 中存在错误的 GitHub 凭据，先删除旧凭据再重新创建。

#### 6.10.2 Docker Hub 仓库和 Access Token

先创建公开仓库：

1. Docker Hub → Repositories；
2. Create repository；
3. Namespace 选择 `liujiaxucn`；
4. Repository name 填 `gulimall-k8s`；
5. Visibility 选择 Public；
6. Create。


再创建 Access Token：

1. 登录 Docker Home：`https://app.docker.com`；
2. 点击右上角头像，选择 Account settings；
3. 选择 Personal access tokens；
4. 点击 Generate new token；
5. 名称填 `jenkins-gulimall-k8s`，Expiration 按需选择；
6. Repository permissions 选择 Read & Write，Cloud Sandboxes 不需要；
7. 点击 Generate，Token 只显示一次，立即复制。
   <your-dockerhub-pat>

在 Jenkins 中新增：

| 字段 | 值 |
| --- | --- |
| Kind | Username with password |
| ID | dockerhub-credentials |
| Username | liujiaxucn |
| Password | Docker Hub Access Token |
| Description | Docker Hub push token |

如果 Token 没有复制，删除后重新生成。

#### 6.10.3 Kubernetes RBAC 和 kubeconfig

应用 RBAC：

> **命令说明：** 在「6.10.3 Kubernetes RBAC 和 kubeconfig」阶段，该命令把 YAML 清单提交给 Kubernetes，创建或更新对应资源。

```bash
kubectl apply -f /opt/gulimall/gulimall-k8s/k8s/40-jenkins-rbac.yaml
kubectl -n gulimall-k8s get serviceaccount,role,rolebinding
```

生成 Jenkins 专用 kubeconfig：

> **命令说明：** 在「6.10.3 Kubernetes RBAC 和 kubeconfig」阶段，该命令生成 Jenkins 专用 kubeconfig，包含 CA 证书、API Server 地址和 ServiceAccount Token。

```bash
CA_DATA=$(base64 -w0 /etc/kubernetes/pki/ca.crt)
TOKEN=$(kubectl -n gulimall-k8s create token jenkins-deployer --duration=8760h)

mkdir -p /var/lib/jenkins/.kube
cat > /var/lib/jenkins/.kube/config <<EOF
apiVersion: v1
kind: Config
clusters:
- name: gulimall-k8s
  cluster:
    certificate-authority-data: ${CA_DATA}
    server: https://<your-k8s-master-ip>:6443
users:
- name: jenkins-deployer
  user:
    token: ${TOKEN}
contexts:
- name: jenkins-deployer
  context:
    cluster: gulimall-k8s
    user: jenkins-deployer
    namespace: gulimall-k8s
current-context: jenkins-deployer
EOF

chown -R jenkins:jenkins /var/lib/jenkins/.kube
chmod 600 /var/lib/jenkins/.kube/config
```

测试 Jenkins 用户的权限：

> **命令说明：** 在「6.10.3 Kubernetes RBAC 和 kubeconfig」阶段，该命令以 jenkins 用户验证 kubeconfig 和 RBAC 权限。

```bash
sudo -u jenkins /usr/bin/kubectl \
  --kubeconfig=/var/lib/jenkins/.kube/config \
  -n gulimall-k8s get pods
```

返回 `No resources found` 或 Pod 列表都正常；返回 `Forbidden` 说明 RBAC 没有生效。

`--duration=8760h` 的有效期是一年。如果 API Server 限制了 token 时长，改用 `720h`，并在过期前重新生成 kubeconfig。

在 Jenkins 中新增 Secret file 凭据前，先把 kubeconfig 从虚拟机复制到 Windows。Jenkins 页面运行在 Windows 浏览器里，文件选择器只能选 Windows 本地文件，读不到虚拟机的 `/var/lib/jenkins/.kube/config`。

在 Windows `cmd` 执行：

> **命令说明：** 在「6.10.3 Kubernetes RBAC 和 kubeconfig」阶段，该命令在 Windows 和虚拟机之间复制文件。

```cmd
scp root@<your-k8s-master-ip>:/var/lib/jenkins/.kube/config C:\Users\zgh29\Downloads\jenkins-kubeconfig
```

输入 root 密码后，文件会复制到 `C:\Users\zgh29\Downloads\jenkins-kubeconfig`。没有 `scp` 时使用 WinSCP 或 MobaXterm 下载。

在 Jenkins 中新增 Secret file 凭据：

| 字段 | 值 |
| --- | --- |
| Kind | Secret file |
| ID | kubeconfig-gulimall-k8s |
| File | 选择 Windows 本地的 `jenkins-kubeconfig` |
| Description | Jenkins deployer kubeconfig |

上传完成后可以删除 Windows 本地副本：

> **命令说明：** 在「6.10.3 Kubernetes RBAC 和 kubeconfig」阶段，该命令删除 Windows 本地临时文件。

```cmd
del C:\Users\zgh29\Downloads\jenkins-kubeconfig
```

kubeconfig 包含 Jenkins deployer 的 token，不要提交到 Git。

也可以不使用 Secret file 凭据，直接让 Jenkins 使用 `/var/lib/jenkins/.kube/config`。此时需要把 Jenkinsfile 的 Kubernetes Deploy 阶段中 `withCredentials([file(...)])` 去掉，只保留 `kubectl` 命令。当前 Jenkinsfile 使用凭据方式，优先按上面的复制和上传步骤操作。

### 6.11 创建流水线任务

1. Jenkins 首页 → New Item / 新建任务；
2. 名称填写 `gulimall-k8s`；
3. 类型选择 Pipeline；
4. 点击 OK / 确定；
5. 在 Configure 页面左侧点击「流水线 / Pipeline」；
6. Definition / 定义选择 Pipeline script from SCM / 来自 SCM 的 Pipeline 脚本；
7. SCM 选择 Git；
8. Repository URL 填 `https://github.com/liu-jiaxu/gulimall.git`；
9. Credentials 选择 `github-pat`；
10. Branch Specifier 填 `*/develop/gulimall/v0.1`；
11. Script Path 填 `gulimall-k8s/Jenkinsfile`；
12. 保存。

新版 Jenkins 的 Pipeline 配置在左侧「流水线」菜单，不在 General 页面下方。General 页面只有描述、丢弃旧构建、并发构建等选项。

暂时不要点击 Build Now。先完成 MySQL、Redis 和应用清单的部署，再执行第一次构建。

### 6.12 Jenkins 常见问题

| 现象 | 原因和处理 |
| --- | --- |
| `permission denied` 访问 docker.sock | `usermod -aG docker jenkins` 后执行 `systemctl restart jenkins` |
| 拉取 GitHub 超时 | Jenkins systemd 中配置 `HTTP_PROXY`/`HTTPS_PROXY`，并确认 `NO_PROXY` 不含 github.com |
| GitHub 认证失败 | 私有仓库必须使用 PAT，不能用账号密码；检查 `github-pat` |
| `mvn: command not found` | 检查 `/usr/bin/mvn` 软链接和 systemd 的 PATH |
| `kubectl: command not found` | kubeadm 安装后 `/usr/bin/kubectl` 应存在；检查 PATH |
| `kubectl` 返回 Forbidden | 检查 `40-jenkins-rbac.yaml` 和 kubeconfig 的 namespace |
| Jenkins 启动 OOM | 降低 `JAVA_OPTS` 的 `-Xmx`，或给虚拟机增加内存 |

## 七、部署 MySQL 和 Redis

### 7.1 创建 Secret

MySQL 密码、Redis 密码、邀请码、AI Key 都放在 Kubernetes Secret 中，不写进 Deployment。

先从模块配置中查看 AI Key：

> **命令说明：** 在「7.1 创建 Secret」阶段，按该步骤目标执行这段命令，完成后对照输出检查结果。

```bash
grep -n 'api-key:' /opt/gulimall/gulimall-k8s/src/main/resources/application.yaml
```

输出中的值形如 `${AI_API_KEY:sk-xxxx}`，取 `sk-` 开头到 `}` 前的部分作为实际 Key。也可以使用新的模型 Key。

创建 Secret 前先读取 Key。`read -s` 不回显，避免 Key 进入 shell 历史：

> **命令说明：** 在「7.1 创建 Secret」阶段，该命令从终端读取 AI Key，不回显，避免写入 shell 历史。

```bash
read -rsp '请输入 AI_API_KEY: ' AI_KEY; echo
```

下面的 kubectl 必须保持单行，不要用反斜杠续行。多行粘贴时容易把 `--from-literal` 文本写进 `ai-api-key`，导致长度变成 42、前缀变成 `--from`：

> **命令说明：** 在「7.1 创建 Secret」阶段，该命令把 YAML 清单提交给 Kubernetes，创建或更新对应资源。

```bash
kubectl -n gulimall-k8s create secret generic gulimall-k8s-secrets --from-literal=mysql-password='<your-password>' --from-literal=redis-password='<your-password>' --from-literal=invite-code='<your-invite-code>' --from-literal=ai-api-key="$AI_KEY" --dry-run=client -o yaml | kubectl apply -f -
```

清理临时变量：

> **命令说明：** 在「7.1 创建 Secret」阶段，该命令清理临时变量。

```bash
unset AI_KEY
```

确认 Secret：

> **命令说明：** 在「7.1 创建 Secret」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s get secret gulimall-k8s-secrets
```

Secret 中有四个 key：

| Key | 用途 |
| --- | --- |
| mysql-password | MySQL root 密码，同时供应用连接 |
| redis-password | Redis 密码，同时供应用连接 |
| invite-code | 登录和注册邀请码 |
| ai-api-key | OpenAI 兼容模型密钥 |

### 7.2 创建 MySQL 初始化 ConfigMap

> **命令说明：** 在「7.2 创建 MySQL 初始化 ConfigMap」阶段，该命令把 YAML 清单提交给 Kubernetes，创建或更新对应资源。

```bash
kubectl -n gulimall-k8s create configmap mysql-init \
  --from-file=init.sql=/opt/gulimall/gulimall-k8s/sql/init.sql \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n gulimall-k8s get configmap mysql-init
```

`sql/init.sql` 创建数据库 `gulimall_k8s_ai`、用户表 `ai_user` 和默认配置表 `ai_default_config`。

MySQL 官方镜像只在数据目录为空时执行 `/docker-entrypoint-initdb.d` 下的脚本。如果 PVC 已有旧数据，初始化脚本不会再次执行，需要手动导入。

### 7.3 部署 MySQL

> **命令说明：** 在「7.3 部署 MySQL」阶段，该命令把 YAML 清单提交给 Kubernetes，创建或更新对应资源。

```bash
kubectl apply -f /opt/gulimall/gulimall-k8s/k8s/10-mysql.yaml
kubectl -n gulimall-k8s get pods,pvc,svc
```

等待 `mysql-0` 变为 `Running` 且 `READY 1/1`：

> **命令说明：** 在「7.3 部署 MySQL」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s get pod mysql-0 -w
```

查看日志：

> **命令说明：** 在「7.3 部署 MySQL」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s logs -f mysql-0
```

PVC 一直是 Pending 时：

> **命令说明：** 在「7.3 部署 MySQL」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl get storageclass
kubectl -n gulimall-k8s describe pvc mysql-data-mysql-0
kubectl -n local-path-storage get pods
kubectl -n local-path-storage logs deployment/local-path-provisioner
```

验证数据库和表：

> **命令说明：** 在「7.3 部署 MySQL」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s exec -it mysql-0 -- \
  mysql -uroot -p<your-password> -e "SHOW DATABASES; USE gulimall_k8s_ai; SHOW TABLES;"
```

预期表：

| 表 | 用途 |
| --- | --- |
| ai_user | 用户信息、BCrypt 密码哈希 |
| ai_default_config | 默认模型、系统提示词 |

如果表不存在，手动执行 SQL：

> **命令说明：** 在「7.3 部署 MySQL」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s exec -i mysql-0 -- \
  mysql -uroot -p<your-password> < /opt/gulimall/gulimall-k8s/sql/init.sql
```

### 7.4 部署 Redis

> **命令说明：** 在「7.4 部署 Redis」阶段，该命令把 YAML 清单提交给 Kubernetes，创建或更新对应资源。

```bash
kubectl apply -f /opt/gulimall/gulimall-k8s/k8s/20-redis.yaml
kubectl -n gulimall-k8s get pods,pvc,svc
```

等待 `redis-0` 变为 `Running` 且 `READY 1/1`，然后验证：

> **命令说明：** 在「7.4 部署 Redis」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s exec -it redis-0 -- redis-cli -a <your-password> ping
```

输出 `PONG` 表示 Redis 正常。

### 7.5 验证集群内网络

> **命令说明：** 在「7.5 验证集群内网络」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s run nettest --image=busybox:1.36 --restart=Never -- sleep 3600
kubectl -n gulimall-k8s wait --for=condition=Ready pod/nettest --timeout=180s
kubectl -n gulimall-k8s exec -it nettest -- sh -c \
  "nc -w 3 mysql 3306 </dev/null && echo mysql-open || echo mysql-closed; nc -w 3 redis 6379 </dev/null && echo redis-open || echo redis-closed"
kubectl -n gulimall-k8s delete pod nettest
```

`kubectl run` 只代表 Pod 对象创建成功，容器可能还在拉镜像。直接 `exec` 会报 `container not found`，必须先等待 Pod Ready。

BusyBox 的 `nc` 不支持 `-z`，使用 `nc -w 3`。输出 `mysql-open` 和 `redis-open` 说明 Service 和 DNS 正常。

如果 Pod 一直 `ImagePullBackOff`，执行 `kubectl -n gulimall-k8s describe pod nettest` 查看镜像拉取错误，检查 containerd 代理配置。

## 八、构建和部署 Java 服务

### 8.1 部署文件说明

| 文件 | 作用 |
| --- | --- |
| `gulimall-k8s/Dockerfile` | 使用 JRE 21 运行 Jenkins 打好的 jar |
| `gulimall-k8s/Jenkinsfile` | Maven 构建、Docker 构建推送、kubectl 发布 |
| `gulimall-k8s/k8s/30-app.yaml` | 应用 Deployment 和 NodePort Service |
| `gulimall-k8s/k8s/40-jenkins-rbac.yaml` | Jenkins 专用 ServiceAccount、Role、RoleBinding |

`30-app.yaml` 中的镜像名是 `liujiaxucn/gulimall-k8s:latest`。流水线会用构建号加 Git 短提交生成新 tag，并通过 `kubectl set image` 覆盖。

### 8.2 手动验证构建

在虚拟机上进入模块目录：

> **命令说明：** 在「8.2 手动验证构建」阶段，该命令执行 Maven 编译、测试和打包。

```bash
cd /opt/gulimall/gulimall-k8s
mvn -B clean package
ls -lh target/gulimall-k8s-1.0.0.jar
```

构建 Docker 镜像：

> **命令说明：** 在「8.2 手动验证构建」阶段，该命令构建应用镜像。

```bash
docker build -t liujiaxucn/gulimall-k8s:manual-test .
docker images | grep gulimall-k8s
```

这一步只验证 Dockerfile 和 jar 是否正常，不启动容器。应用正式由 Jenkins 构建并部署。

### 8.3 执行第一次 Jenkins 构建

1. Jenkins 首页进入 `gulimall-k8s` 任务；
2. 点击 Build Now；
3. 打开 Build History 中的最新构建；
4. 进入 Console Output，观察四个阶段：

| 阶段 | 预期结果 |
| --- | --- |
| Checkout | 拉取 `develop/gulimall/v0.1` |
| Maven Build | 执行测试并生成 jar |
| Docker Build and Push | 推送两个 tag 到 Docker Hub |
| Kubernetes Deploy | apply 清单、更新镜像、rollout 完成 |

第一次构建需要下载 Maven 依赖和 Docker 基础镜像，可能持续 5～15 分钟。

构建成功后，Docker Hub 中会出现：

> **命令说明：** 在「8.3 执行第一次 Jenkins 构建」阶段，这是镜像仓库和 tag 示例，用于对照 Docker Hub 产物。

```text
liujiaxucn/gulimall-k8s:<BUILD_NUMBER>-<GIT_SHORT_SHA>
liujiaxucn/gulimall-k8s:latest
```

### 8.4 流水线逻辑

`Jenkinsfile` 的关键逻辑：

1. `IMAGE_TAG` 使用构建号和 Git 短提交，不使用固定版本；
2. `mvn -B clean package` 会运行 `SessionUserTest`，测试失败则停止发布；
3. `docker login` 使用 Jenkins 凭据 `dockerhub-credentials`；
4. 同时推送版本 tag 和 `latest`；
5. `kubectl apply -f k8s/30-app.yaml` 创建或更新 Deployment 和 Service；
6. `kubectl set image` 把容器镜像改成当前构建的 tag；
7. `kubectl rollout status` 等待发布完成。

应用使用内存 HttpSession，Deployment 采用 `Recreate` 策略，发布期间会有短暂中断，但不会出现新旧 Pod 同时接收请求导致登录态丢失的问题。

### 8.5 验证应用部署

> **命令说明：** 在「8.5 验证应用部署」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s get pods,svc,deploy
kubectl -n gulimall-k8s rollout status deployment/gulimall-k8s
kubectl -n gulimall-k8s get deployment gulimall-k8s -o wide
```

查看应用日志：

> **命令说明：** 在「8.5 验证应用部署」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s logs -f deployment/gulimall-k8s
```

本机健康检查：

> **命令说明：** 在「8.5 验证应用部署」阶段，该命令测试应用健康检查端点，确认数据库和 Redis 状态。

```bash
curl -s http://127.0.0.1:30080/actuator/health
```

返回 `{"status":"UP"}` 表示应用、MySQL 和 Redis 都正常。

如果状态为 `DOWN`：

> **命令说明：** 在「8.5 验证应用部署」阶段，该命令测试应用健康检查端点，确认数据库和 Redis 状态。

```bash
curl -s http://127.0.0.1:30080/actuator/health | python3 -m json.tool
```

`components.db` 或 `components.redis` 的 `status` 会显示具体依赖状态。

### 8.6 第一次登录

浏览器打开：

> **命令说明：** 在「8.6 第一次登录」阶段，这是访问地址或下载地址，用于浏览器或 curl 访问。

```text
http://<your-k8s-master-ip>:30080
```

或公网地址：

> **命令说明：** 在「8.6 第一次登录」阶段，这是访问地址或下载地址，用于浏览器或 curl 访问。

```text
https://k8s.gulimall.vip
```

登录信息：

| 字段 | 值 |
| --- | --- |
| 用户名 | admin |
| 密码 | <your-password> |
| 邀请码 | <your-invite-code> |

登录和注册都必须填写邀请码。登录态为绝对 12 小时，超过 12 小时后页面自动回到登录页。

登录后验证：

1. 模型下拉框能加载；
2. 新建对话并发送一条消息；
3. 回答以流式方式逐段出现；
4. 左侧会话列表出现新会话；
5. 刷新页面后会话仍能加载。

### 8.7 修改管理员密码、邀请码和 AI Key

修改管理员密码可以使用登录页的「忘记密码」，或调用接口：

> **命令说明：** 在「8.7 修改管理员密码、邀请码和 AI Key」阶段，该命令通过代理请求接口，验证登录、聊天或配置修改。

```bash
curl -s -X POST https://k8s.gulimall.vip/api/auth/reset-password \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","newPassword":"新的管理员密码"}'
```

修改邀请码：重新创建 Secret，然后重启应用。kubectl 同样保持单行：

> **命令说明：** 在「8.7 修改管理员密码、邀请码和 AI Key」阶段，该命令把 YAML 清单提交给 Kubernetes，创建或更新对应资源。

```bash
kubectl -n gulimall-k8s create secret generic gulimall-k8s-secrets --from-literal=mysql-password='<your-password>' --from-literal=redis-password='<your-password>' --from-literal=invite-code='新的邀请码' --from-literal=ai-api-key='原来的AIKey' --dry-run=client -o yaml | kubectl apply -f -
kubectl -n gulimall-k8s rollout restart deployment/gulimall-k8s
```

轮换 AI Key 使用同样的方法，只替换 `ai-api-key`。

### 8.8 回滚

查看发布历史：

> **命令说明：** 在「8.8 回滚」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s rollout history deployment/gulimall-k8s
```

回滚到上一个版本：

> **命令说明：** 在「8.8 回滚」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s rollout undo deployment/gulimall-k8s
kubectl -n gulimall-k8s rollout status deployment/gulimall-k8s
```

回滚到指定版本：

> **命令说明：** 在「8.8 回滚」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s rollout undo deployment/gulimall-k8s --to-revision=1
```

### 8.9 Cloudflare 公网验证

页面：

> **命令说明：** 在「8.9 Cloudflare 公网验证」阶段，该命令通过代理测试地址是否可访问。

```bash
curl -I https://k8s.gulimall.vip/
```

返回 `HTTP/2 200` 表示 Cloudflare Tunnel、NodePort 和应用都正常。

登录：

> **命令说明：** 在「8.9 Cloudflare 公网验证」阶段，该命令通过代理请求接口，验证登录、聊天或配置修改。

```bash
rm -f /tmp/k8s-cookies.txt
curl -s -c /tmp/k8s-cookies.txt -X POST https://k8s.gulimall.vip/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<your-password>","inviteCode":"<your-invite-code>"}'
```

返回内容应包含 `user` 和 `loginAt`。

模型列表：

> **命令说明：** 在「8.9 Cloudflare 公网验证」阶段，该命令携带登录 Cookie 请求接口，验证登录态和业务返回。

```bash
curl -s -b /tmp/k8s-cookies.txt https://k8s.gulimall.vip/api/models
```

SSE 流式对话：

> **命令说明：** 在「8.9 Cloudflare 公网验证」阶段，该命令发起 SSE 流式请求，持续接收 meta、delta、done 事件。

```bash
curl -N -b /tmp/k8s-cookies.txt -X POST https://k8s.gulimall.vip/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"Say hi in one short sentence."}'
```

预期依次看到：

> **命令说明：** 在「8.9 Cloudflare 公网验证」阶段，这是 SSE 事件示例，用于对照流式返回格式。

```text
event:meta
event:delta
event:delta
event:done
```

返回 524 时，说明 Cloudflare 等待超时；检查模型是否响应过慢。

## 九、日常运维

### 9.1 查看资源状态

> **命令说明：** 在「9.1 查看资源状态」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl get nodes -o wide
kubectl -n gulimall-k8s get pods,svc,deploy,statefulset,pvc,secret,configmap
kubectl -n gulimall-k8s get events --sort-by=.lastTimestamp | tail -30
```

### 9.2 查看日志

> **命令说明：** 在「9.2 查看日志」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s logs -f deployment/gulimall-k8s
kubectl -n gulimall-k8s logs -f mysql-0
kubectl -n gulimall-k8s logs -f redis-0
```

容器重启后查看上一次日志：

> **命令说明：** 在「9.2 查看日志」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s logs deployment/gulimall-k8s --previous
```

### 9.3 进入容器排查

> **命令说明：** 在「9.3 进入容器排查」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s exec -it deployment/gulimall-k8s -- sh
kubectl -n gulimall-k8s exec -it mysql-0 -- sh
kubectl -n gulimall-k8s exec -it redis-0 -- sh
```

### 9.4 查看资源占用

> **命令说明：** 在「9.4 查看资源占用」阶段，该命令查看节点或 Pod 的 CPU 和内存占用。

```bash
kubectl top nodes
kubectl -n gulimall-k8s top pods
```

`kubectl top` 需要 metrics-server。

### 9.5 重启服务

重启应用：

> **命令说明：** 在「9.5 重启服务」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s rollout restart deployment/gulimall-k8s
kubectl -n gulimall-k8s rollout status deployment/gulimall-k8s
```

重启 MySQL/Redis 不会删除 PVC 数据：

> **命令说明：** 在「9.5 重启服务」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s delete pod mysql-0
kubectl -n gulimall-k8s delete pod redis-0
```

StatefulSet 会自动重建 Pod，并重新挂载原来的 PVC。

### 9.6 节点重启后的检查

> **命令说明：** 在「9.6 节点重启后的检查」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
systemctl status containerd
systemctl status docker
systemctl status kubelet
systemctl status jenkins
systemctl status cloudflared
kubectl get nodes
kubectl -n gulimall-k8s get pods
```

以上服务都已设置开机自启。

如果应用 Pod 一直 ImagePullBackOff，或者公网返回 502，按 10.6 处理。

### 9.7 MySQL 备份

> **命令说明：** 在「9.7 MySQL 备份」阶段，该命令导出 MySQL 数据库，生成备份文件。

```bash
mkdir -p /opt/backup
kubectl -n gulimall-k8s exec mysql-0 -- \
  mysqldump -uroot -p<your-password> --databases gulimall_k8s_ai \
  > /opt/backup/gulimall_k8s_ai-$(date +%F).sql
ls -lh /opt/backup
```

恢复：

> **命令说明：** 在「9.7 MySQL 备份」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s exec -i mysql-0 -- \
  mysql -uroot -p<your-password> < /opt/backup/gulimall_k8s_ai-2026-11-05.sql
```

### 9.8 Redis 数据

Redis 使用 AOF，数据保存在 PVC 中。手动触发一次落盘：

> **命令说明：** 在「9.8 Redis 数据」阶段，该命令触发 Redis 后台落盘，生成 AOF/RDB 备份。

```bash
kubectl -n gulimall-k8s exec redis-0 -- redis-cli -a <your-password> BGSAVE
```

local-path 的 PVC 数据目录位于节点 `/opt/local-path-provisioner`。节点磁盘损坏时数据无法恢复，重要数据需要额外备份。

### 9.9 磁盘清理

查看占用：

> **命令说明：** 在「9.9 磁盘清理」阶段，该命令查看 Docker 镜像、容器和缓存占用。

```bash
df -h /
du -h --max-depth=1 /var/lib/containerd /var/lib/docker /opt/local-path-provisioner 2>/dev/null | sort -rh | head
docker system df
```

清理未使用的 Docker 镜像和构建缓存：

> **命令说明：** 在「9.9 磁盘清理」阶段，该命令构建应用镜像。

```bash
docker image prune -a -f
docker builder prune -f
```

清理 containerd 中未被 Pod 使用的镜像：

> **命令说明：** 在「9.9 磁盘清理」阶段，该命令使用 containerd 拉取或删除镜像，验证代理。

```bash
ctr -n k8s.io images list
ctr -n k8s.io images prune
```

限制 journald 日志：

> **命令说明：** 在「9.9 磁盘清理」阶段，该命令写入配置文件，后续服务或脚本会读取该文件。

```bash
mkdir -p /etc/systemd/journald.conf.d
cat > /etc/systemd/journald.conf.d/size.conf <<'EOF'
[Journal]
SystemMaxUse=500M
SystemMaxFileSize=100M
EOF
systemctl restart systemd-journald
```

### 9.10 扩副本说明

当前 Deployment 副本数为 1，应用使用内存 HttpSession。直接扩到多个副本会出现登录态不共享的问题。

需要多副本时，先给应用接入 Spring Session Redis，把 Session 存到 Redis，再修改 Deployment 副本数并改用滚动更新策略。

## 十、故障排查

### 10.1 Kubernetes

| 现象 | 排查命令 | 常见原因 |
| --- | --- | --- |
| `kubeadm init` 失败 | `journalctl -u kubelet -n 100` | swap 未关闭、firewalld、SELinux、cgroup 配置、代理 |
| 节点 NotReady | `kubectl describe node gulimall-k8s`；`journalctl -u kubelet`；`journalctl -u containerd` | Flannel 未启动、SystemdCgroup 未开启、镜像拉取失败 |
| Pod ImagePullBackOff | `kubectl -n gulimall-k8s describe pod <pod>` | 镜像名错误、代理不通、Docker Hub 限流 |
| Pod Pending | `kubectl -n gulimall-k8s describe pod <pod>` | 资源不足、PVC 未绑定、节点污点 |
| PVC Pending | `kubectl -n gulimall-k8s describe pvc <pvc>` | local-path 未安装、StorageClass 未设为默认 |
| `kubectl` 连不上 | `kubectl cluster-info`；`systemctl status kubelet` | kubeconfig 缺失、apiserver 未启动 |
| 节点磁盘不足 | `df -h /`；`kubectl describe node` | 镜像、日志、PVC 占用 |

### 10.2 MySQL 和 Redis

| 现象 | 排查命令 | 常见原因 |
| --- | --- | --- |
| `mysql-0` CrashLoopBackOff | `kubectl -n gulimall-k8s logs mysql-0` | 密码、PVC 权限、初始化 SQL 失败 |
| MySQL 启动但连不上 | `kubectl -n gulimall-k8s exec -it mysql-0 -- mysqladmin ping` | Service、密码、网络 |
| 表不存在 | `kubectl -n gulimall-k8s exec -it mysql-0 -- mysql ...` | 初始化脚本只在空数据目录执行 |
| `redis-0` CrashLoopBackOff | `kubectl -n gulimall-k8s logs redis-0` | 密码变量、PVC 权限、启动命令 |
| Redis 连接认证失败 | `redis-cli -a 密码 ping` | Secret 中的密码和应用环境变量不一致 |

### 10.3 应用

| 现象 | 排查命令 | 常见原因 |
| --- | --- | --- |
| CrashLoopBackOff | `kubectl -n gulimall-k8s logs deployment/gulimall-k8s --previous` | MySQL/Redis 连接失败、表不存在、AI Key 为空 |
| readiness 一直 DOWN | `curl http://127.0.0.1:30080/actuator/health` | 数据库或 Redis 未就绪 |
| 启动后立即退出 | `kubectl -n gulimall-k8s describe deployment gulimall-k8s` | 镜像架构不匹配、权限、端口冲突 |
| 登录返回 403 | 检查请求体 | 邀请码缺失或错误 |
| 登录后 12 小时回登录页 | 正常行为 | 登录态绝对 12 小时 |
| 聊天返回 500 | `kubectl logs`；检查 AI Key | 模型服务不可用、Key 失效、模型名不存在 |
| 流式没有输出 | 浏览器 Network；Cloudflare 日志 | SSE 被缓存、超时、Rocket Loader |

### 10.4 Jenkins

| 现象 | 排查 | 处理 |
| --- | --- | --- |
| Checkout 失败 | Jenkins 凭据、代理、分支名 | 检查 `github-pat` 和 `HTTP_PROXY` |
| Maven 下载慢或失败 | `/var/lib/jenkins/.m2/settings.xml` | 确认 Maven 代理和 Jenkins 代理 |
| Docker 权限拒绝 | `id jenkins` | 加入 docker 组并重启 Jenkins |
| Docker Hub 推送 401 | `dockerhub-credentials` | 使用 Access Token，不使用登录密码 |
| kubectl Forbidden | Jenkins 用户测试命令 | 检查 RBAC 和 kubeconfig namespace |
| rollout 超时 | `kubectl describe pod`；`kubectl logs` | 查看新 Pod 启动失败原因 |

### 10.5 Cloudflare

| 现象 | 排查 | 处理 |
| --- | --- | --- |
| 502 | `curl http://127.0.0.1:30080`；`kubectl get svc,pods` | 应用未启动或 NodePort 不通 |
| 530 | `systemctl status cloudflared`；`journalctl -u cloudflared` | Tunnel 未连接，检查出站网络 |
| 524 | 应用日志、模型响应时间 | 模型超时，降低流式超时或换模型 |
| 页面是旧版本 | Cloudflare 缓存 | Purge Cache，检查 Cache Rule |
| SSE 一次性返回 | Cache Rule、Rocket Loader | 绕过 `/api/*`，关闭 Rocket Loader |

\
#### 注意

- 应用运行、MySQL/Redis 集群内访问、AI 接口调用和 Cloudflare Tunnel 都不使用 `<your-vmware-nat-ip>:7897` 这个 HTTP 代理；
- 只有拉取镜像、Jenkins 构建、Maven 和插件下载需要代理或外网；
- AI 接口返回 401 `invalid_credentials` 是 AI Key 问题，不是 502 的原因；网页恢复后仍需把 Secret 改成 51 位有效 Key；
- 重启后应用使用内存 HttpSession，所有用户需要重新登录；聊天历史在 Redis，3 天内仍然保留。

## 十一、安全和生产化建议

当前部署是学习和 CI/CD 练习环境，以下项目在生产环境必须调整。

### 11.1 密码和密钥

1. MySQL、Redis 密码当前为 `<your-password>`，生产环境必须替换为强口令；
2. 管理员默认密码 `<your-password>` 在第一次登录后立即修改；
3. AI Key 放在 Secret 中，不提交到 Git；
4. 邀请码 `<your-invite-code>` 建议定期更换；
5. Cloudflare Tunnel Token、GitHub PAT、Docker Hub Token 都属于敏感凭据。

### 11.2 访问控制

1. Cloudflare Access 当前未开启；公网开放时建议只允许指定邮箱；
2. Jenkins 只在局域网使用，不对公网暴露；
3. MySQL、Redis 只使用 ClusterIP，不创建 NodePort；
4. kube-apiserver 的 6443 只允许局域网访问；
5. Jenkins 使用命名空间级 ServiceAccount，不使用 admin.conf；
6. 应用的 `/api/auth/reset-password` 没有验证码，公网环境建议关闭或增加验证。

### 11.3 数据和高可用

1. 单节点 k8s、单副本 MySQL、单副本 Redis 都不具备高可用；
2. MySQL 生产环境应使用主从或托管数据库；
3. Redis 生产环境应使用哨兵或集群；
4. local-path 的数据在节点本地，节点损坏会丢数据；
5. 需要多副本应用时先接入 Spring Session Redis；
6. 定期备份 MySQL 和 Redis 数据。

### 11.4 镜像和发布

1. 镜像 tag 使用构建号加 Git 短提交，不使用固定版本；
2. Docker Hub 公开仓库不要放入任何密钥；
3. 生产环境建议使用私有仓库和 `imagePullSecret`；
4. 建议增加镜像漏洞扫描和依赖漏洞扫描；
5. 回滚使用 `kubectl rollout undo`，不要手工删除 Pod。

## 十二、文件清单

以下文件已经随 `gulimall-k8s` 模块提供：

| 文件 | 作用 |
| --- | --- |
| `gulimall-k8s/Dockerfile` | JRE 21 运行镜像 |
| `gulimall-k8s/Jenkinsfile` | Maven、Docker、kubectl 流水线 |
| `gulimall-k8s/sql/init.sql` | 数据库、用户表、默认配置表 |
| `gulimall-k8s/k8s/00-namespace.yaml` | 命名空间 |
| `gulimall-k8s/k8s/10-mysql.yaml` | MySQL StatefulSet、Service、PVC |
| `gulimall-k8s/k8s/20-redis.yaml` | Redis StatefulSet、Service、PVC |
| `gulimall-k8s/k8s/30-app.yaml` | 应用 Deployment、NodePort Service |
| `gulimall-k8s/k8s/40-jenkins-rbac.yaml` | Jenkins ServiceAccount、Role、RoleBinding |
| `gulimall-k8s/docs/K8S与Jenkins完整部署指南.md` | 当前部署文档 |

## 十三、命令速查

### 13.1 集群

> **命令说明：** 在「13.1 集群」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl get nodes -o wide
kubectl get pods -A -o wide
kubectl get storageclass
kubectl -n gulimall-k8s get all,pvc,secret,configmap
```

### 13.2 日志和排查

> **命令说明：** 在「13.2 日志和排查」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s logs -f deployment/gulimall-k8s
kubectl -n gulimall-k8s describe pod <pod>
kubectl -n gulimall-k8s get events --sort-by=.lastTimestamp
kubectl -n gulimall-k8s exec -it deployment/gulimall-k8s -- sh
```

### 13.3 发布和回滚

> **命令说明：** 在「13.3 发布和回滚」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s rollout status deployment/gulimall-k8s
kubectl -n gulimall-k8s rollout history deployment/gulimall-k8s
kubectl -n gulimall-k8s rollout undo deployment/gulimall-k8s
kubectl -n gulimall-k8s rollout restart deployment/gulimall-k8s
```

### 13.4 数据库

> **命令说明：** 在「13.4 数据库」阶段，该命令执行 Kubernetes 操作，读取或修改集群资源。

```bash
kubectl -n gulimall-k8s exec -it mysql-0 -- mysql -uroot -p<your-password>
kubectl -n gulimall-k8s exec -it redis-0 -- redis-cli -a <your-password> ping
```

### 13.5 Cloudflare

> **命令说明：** 在「13.5 Cloudflare」阶段，该命令通过代理测试地址是否可访问。

```bash
cloudflared --version
systemctl status cloudflared --no-pager
journalctl -u cloudflared -n 100 --no-pager
curl -I https://k8s.gulimall.vip/
```

### 13.6 Jenkins

> **命令说明：** 在「13.6 Jenkins」阶段，该命令读取 Jenkins 初始管理员密码，用于浏览器 Setup Wizard。

```bash
systemctl status jenkins --no-pager
journalctl -u jenkins -n 100 --no-pager
cat /var/lib/jenkins/secrets/initialAdminPassword
```

## 十四、部署完成检查表

| 检查项 | 命令 | 预期 |
| --- | --- | --- |
| 节点 Ready | `kubectl get nodes` | `Ready` |
| Flannel 正常 | `kubectl -n kube-flannel get pods` | 全部 Running |
| StorageClass 默认 | `kubectl get storageclass` | `local-path (default)` |
| MySQL Ready | `kubectl -n gulimall-k8s get pod mysql-0` | `1/1 Running` |
| Redis Ready | `kubectl -n gulimall-k8s get pod redis-0` | `1/1 Running` |
| 应用 Ready | `kubectl -n gulimall-k8s get deployment gulimall-k8s` | `1/1` |
| 健康检查 | `curl http://127.0.0.1:30080/actuator/health` | `UP` |
| 登录 | `https://k8s.gulimall.vip` | admin/<your-password> + 邀请码可登录 |
| 聊天 | 页面发送消息 | 流式返回 |
| 12 小时登录态 | 查看 `loginAt` | 到点回登录页 |
| Jenkins 构建 | Jenkins Build Now | 四个阶段成功 |
| Cloudflare | `curl -I https://k8s.gulimall.vip/` | 200 |



