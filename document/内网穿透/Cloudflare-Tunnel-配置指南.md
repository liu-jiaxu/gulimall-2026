# Cloudflare Tunnel 内网穿透配置指南

> 适用场景：本地/虚拟机运行的 `gulimall-order`（端口 9000）需要被支付宝沙箱回调访问。
>
> 当前开发环境：
> - 业务服务：Windows 本机 `<your-windows-host-ip>:9000`
> - 中间件：虚拟机 `<your-vm-host-ip>`（Nacos / MySQL / Redis / RabbitMQ / Seata）
> - 最终目标：项目整体迁移到虚拟机运行
>
> 本文只讨论 Cloudflare Tunnel，不涉及 frp / ngrok / natapp。
>
> **本项目实际域名：`gulimall.vip`。**
> 支付回调建议只使用 `pay.gulimall.vip`，站点本身继续用 `order.gulimall.com`，
> 避免 `SESSION_COOKIE_DOMAIN=gulimall.com` 的登录会话在 `gulimall.vip` 上失效。

---

## 一、安装在哪台机器上

**原则：`cloudflared` 要安装在“能访问目标服务”的机器上。**

| 阶段 | 推荐做法 |
|---|---|
| 现在本地开发（order 跑在 Windows） | 在 Windows 上装 `cloudflared`，ingress 指向 `http://localhost:9000`；或者先用 Quick Tunnel 临时测试 |
| 最终项目跑在虚拟机 | 在虚拟机里装 `cloudflared`，ingress 指向 `http://localhost:9000` |
| 过渡期：cloudflared 在虚拟机、order 还在 Windows | 在虚拟机里装 `cloudflared`，ingress 指向 `http://<your-windows-host-ip>:9000`，先确认虚拟机可以 `curl` 通 Windows 的 9000 端口 |

**建议：**

1. **长期方案装在虚拟机**，因为最终项目在虚拟机运行，这样可以只维护一条命名隧道；
2. **当前本地临时测试**，直接在 Windows 上跑 Quick Tunnel 最省事；
3. 不要同时维护“Windows 一条命名隧道 + 虚拟机一条命名隧道”，域名和回调地址容易混乱。

---

## 二、两种 Tunnel 怎么选

| 对比项 | Quick Tunnel（一次性域名） | Named Tunnel（自定义域名） |
|---|---|---|
| 是否需要域名 | 不需要 | 需要，并且域名要托管到 Cloudflare |
| 是否需要 Cloudflare 账号 | 不需要 | 需要 |
| 公网地址 | 每次启动随机生成 `https://xxx.trycloudflare.com` | 固定，例如 `https://order.example.com` |
| 重启后地址 | **会变** | 不变 |
| 稳定性 | 官方定位为调试/临时分享，无可用性保证 | 适合长期使用 |
| 并发限制 | 约 200 个 in-flight 请求 | 正常 Tunnel 限制 |
| SSE | 不支持 | 支持 |
| 适合场景 | 支付宝回调临时测试、演示 | 日常开发、长期回调、准生产 |

**结论：**

- 只想马上让支付宝回调打进来：用 **Quick Tunnel**；
- 想长期稳定、少改配置：买域名，用 **Named Tunnel**；
- 生产环境不要用 Quick Tunnel。

---

## 三、安装 cloudflared

### 3.1 Windows（当前本地开发）

方式一：winget

```bat
winget install --id Cloudflare.cloudflared
```

方式二：手动下载

1. 打开 Cloudflare 官方下载页：
   <https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/>
2. 下载 `cloudflared-windows-amd64.exe`；
3. 重命名为 `cloudflared.exe`，放到例如 `D:\SoftwareInstallation\cloudflared\`；
4. 把目录加入 PATH，或者使用完整路径。

验证：

```bat
cloudflared --version
```

### 3.2 Linux 虚拟机（Ubuntu / Debian）

> 本项目推荐直接用仓库里的离线 RPM 安装，步骤见 §5.9：
> 本地只负责 `scp` 到 Linux，安装和注册服务都在 Linux 上执行。

```bash
# 下载 deb 包（版本号以官方最新为准）
wget https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb

sudo dpkg -i cloudflared-linux-amd64.deb
cloudflared --version
```

如果是 CentOS / Rocky：

```bash
wget https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-x86_64.rpm
sudo rpm -ivh cloudflared-linux-x86_64.rpm
cloudflared --version
```

### 3.3 Docker 方式（可选）

```bash
docker run -d --name cloudflared --restart unless-stopped \
  cloudflare/cloudflared:latest \
  tunnel --no-autoupdate run --token <TOKEN>
```

适合不想在宿主机安装二进制的情况。

---

## 四、Quick Tunnel：一次性随机域名

### 4.1 启动

在运行 order 服务的机器上执行：

```bat
cloudflared tunnel --url http://localhost:9000
```

如果 cloudflared 在虚拟机、order 在 Windows：

```bash
cloudflared tunnel --url http://<your-windows-host-ip>:9000
```

启动后终端会输出类似：

```text
https://random-words-here.trycloudflare.com
```

这个就是公网地址。

### 4.2 用到支付宝

假设生成的地址是：

```text
https://random-words-here.trycloudflare.com
```

配置：

```yaml
gulimall:
  alipay:
    notify-url: https://random-words-here.trycloudflare.com/pay/notify
    return-url: https://random-words-here.trycloudflare.com/pay/return
```

或者通过环境变量：

```text
ALIPAY_NOTIFY_URL=https://random-words-here.trycloudflare.com/pay/notify
ALIPAY_RETURN_URL=https://random-words-here.trycloudflare.com/pay/return
```

然后重启 order 服务，重新发起支付。

### 4.3 Quick Tunnel 的限制

- **进程停止或重启后地址会变**；每次都要改配置并重启 order；
- 没有可用性保证；
- 约 200 个 in-flight 请求上限；
- 不支持 SSE；
- 国内网络下 `trycloudflare.com` 可能解析失败或被限速；
- 不适合生产环境。

---

## 五、Named Tunnel：自定义域名

本节按顺序完成：添加域名 → 修改 NS → 等 Active → 创建 Tunnel → 添加 Route → 测试 → 配置支付宝。

### 5.1 前置条件

- 一个域名，例如 `gulimall.vip`
- Cloudflare 账号
- 域名可以修改 NS（阿里云、腾讯云等注册商都可以）
- 目标机器能访问 `gulimall-order` 的 9000 端口

### 5.2 把域名添加到 Cloudflare

1. 登录 <https://dash.cloudflare.com>；
2. 在首页点 **Add a domain**，或点左侧 **Domains → Add a domain**；
3. 输入域名 `gulimall.vip`；
4. 选择 **Free** 计划，继续；
5. Cloudflare 会扫描现有 DNS 记录。新域名显示 0 条记录是正常的，不需要手动添加 A/CNAME 记录，直接继续；
6. Cloudflare 会给出两个 nameserver，复制下来备用。

### 5.3 在阿里云修改 NS

1. 登录阿里云域名控制台；
2. 进入 **域名与网站 → 域名列表 → gulimall.vip → DNS 修改**；
3. 把当前的 nameserver（例如 `dns19.hichina.com`、`dns20.hichina.com`）替换成 Cloudflare 给出的两个 nameserver；
4. 输入时不要带末尾的点，不要有空格；
5. 点确定；
6. 回到 Cloudflare，等待域名状态变成 **Active**；
7. 验证：

```bash
nslookup -type=ns gulimall.vip
```

看到 `ns.cloudflare.com` 就说明 NS 已生效。

> 切换到 Cloudflare NS 后，注册商原来的解析记录不再生效。如果域名有 MX、TXT 等记录，要先在 Cloudflare 重建；新域名通常没有这些记录。
>
> 不要在阿里云手动添加 `pay` 的 CNAME，后面 Cloudflare 会自动创建。

### 5.4 创建 Tunnel

1. 在 Cloudflare Dashboard 左侧进入 **Zero Trust → Networks → Tunnels**，或 **Networking → Tunnels**；
2. 点击 **Create a tunnel**；
3. Connector 选择 **Cloudflared**；
4. 隧道名称填 `gulimall`；
5. 选择运行环境：**Windows**、**Debian**、**Red Hat** 或 **Docker**；
6. 复制页面给出的安装命令，在目标机器执行：
   - Windows（管理员 CMD/PowerShell）：
     ```bat
     cloudflared.exe service install <TOKEN>
     ```
   - Linux（root）：
     ```bash
     sudo cloudflared service install <TOKEN>
     ```
   - Docker：
     ```bash
     docker run -d --name cloudflared --restart unless-stopped \
       cloudflare/cloudflared:latest tunnel --no-autoupdate run --token <TOKEN>
     ```
7. 等待 Tunnel 状态变成 **Healthy**，并且 **Active replicas** 至少为 1。

### 5.5 添加 Route（公网主机名）

1. 打开刚创建的 Tunnel 详情页；
2. 点 **Routes → Add route**，或中间的 **+ Add route**；
3. 选择 **Public hostname**；
4. 填写：

| 字段 | 填写 |
|---|---|
| Subdomain | `pay` |
| Domain | `gulimall.vip` |
| Path | 留空 |
| Type | HTTP |
| Service URL | 见下方说明 |

5. 保存后，Routes 从 0 变成 1，Cloudflare 会自动创建 `pay.gulimall.vip` 的 CNAME 记录。

**Service URL 怎么写：**

- `order` 在 Linux 虚拟机（当前项目最终形态）：`http://<your-vm-host-ip>:9000`（方案 A，直连 order）；
- `cloudflared` 和 `order` 在同一台 Linux 上：也可以写 `http://localhost:9000` —— 迁移到 Linux 后 localhost 就是虚拟机；
- `cloudflared` 在虚拟机、`order` 还在 Windows（过渡期）：`http://<your-windows-host-ip>:9000`
  - `<your-windows-host-ip>` 是 Windows 当前的局域网 IP，不是固定值；
  - 用 `ipconfig`（Windows）或 `ip addr`（Linux）查实际 IP；
  - 任何 `cloudflared` 能访问到的 order 地址都可以。
- 使用 Docker：
  - Linux 最好加 `--network host`，Service URL 写 `http://localhost:9000`；
  - Windows Docker Desktop 写 `http://host.docker.internal:9000`。

#### 5.5.1 本项目全部域名的 Route

Nginx 已经按 `Host` 区分站点，所以除 `pay` 外都指向 Nginx 的 80 端口：

| Subdomain | Domain | Path | Type | Service URL |
|---|---|---|---|---|
| 留空（根域名） | `gulimall.vip` | 留空 | HTTP | `http://<your-vm-host-ip>:80` |
| `admin` | `gulimall.vip` | 留空 | HTTP | `http://<your-vm-host-ip>:80` |
| `img` | `gulimall.vip` | 留空 | HTTP | `http://<your-vm-host-ip>:80` |
| `search` | `gulimall.vip` | 留空 | HTTP | `http://<your-vm-host-ip>:80` |
| `auth` | `gulimall.vip` | 留空 | HTTP | `http://<your-vm-host-ip>:80` |
| `cart` | `gulimall.vip` | 留空 | HTTP | `http://<your-vm-host-ip>:80` |
| `order` | `gulimall.vip` | 留空 | HTTP | `http://<your-vm-host-ip>:80` |
| `seckill` | `gulimall.vip` | 留空 | HTTP | `http://<your-vm-host-ip>:80` |
| `member`  | `gulimall.vip` | 留空 | HTTP | `http://<your-vm-host-ip>:80` |
| `pay` | `gulimall.vip` | 留空 | HTTP | `http://<your-vm-host-ip>:9000` |

说明：

- 前 9 个走 Nginx，由 `server_name` 选择对应站点；
- `pay` 直连 order 的 `9000`（方案 A），因为 Nginx 没有 `pay` 的 server 块；
- order 迁移到 Linux 后，`pay` 也可以写 `http://localhost:9000` —— localhost 就是虚拟机；
- 如果 cloudflared 装在虚拟机，其他域名也可以把 `<your-vm-host-ip>:80` 换成 `localhost:80`；
- **AI 助手不需要新增 `agent.gulimall.vip` 的 Route**：agent 接口都挂在现有域名的 `/gulimall/agent/**` 下，用户端聊天窗嵌在 `gulimall/search/auth/cart/order/seckill/member` 页面里，管理端页面在 `admin.gulimall.vip/#/ai/chat`。现有的根域名和 `admin` Route 已经覆盖，不需要多一条。
- **只在你想用 `https://agent.gulimall.vip` 独立访问时才需要**：新增一条 Route 指向 `http://<your-vm-host-ip>:80`，并在 Nginx 增加 `server_name agent.gulimall.vip` 的 server 块；当前项目没有独立 agent 页面，所以不建议。
- **SSE 注意**：聊天流式接口是 `POST /gulimall/agent/chat/stream`，需要 Named Tunnel；Quick Tunnel 不支持 SSE。Cloudflare 的缓存规则里 `/gulimall/` 已经 bypass，不会缓存聊天内容。
- 先验证 Nginx 能收到 Host：
  ```bash
  curl -I -H "Host: gulimall.vip" http://<your-vm-host-ip>/
  ```
  返回 200/302 再添加 Route；如果 502，先修 Nginx upstream。

### 5.6 测试隧道

```bash
curl -I https://pay.gulimall.vip/pay.html
```

返回 200 或 302 就说明隧道已经通到 order。

再测试支付宝回调接口：

```bash
curl -X POST https://pay.gulimall.vip/pay/notify -d "test=1"
```

预期返回纯文本：

```text
failure
```

这是因为手工请求没有支付宝签名，验签失败是正常的。能返回 `failure` 说明：

- 隧道通了；
- `/pay/notify` 没有被登录拦截器重定向。

如果返回的是登录页 HTML，检查：

- `OrderWebConfiguration` 是否放行 `/pay/notify`；
- Cloudflare Access 是否拦截了 `/pay/notify`。

### 5.7 配置支付宝回调地址

```text
ALIPAY_NOTIFY_URL=https://pay.gulimall.vip/pay/notify
ALIPAY_RETURN_URL=http://order.gulimall.com/pay/return
```

说明：

- `notify` 是支付宝服务器访问，必须走公网 `pay.gulimall.vip`；
- `return` 是用户浏览器访问，保留 `order.gulimall.com`，这样原来的登录 Cookie 仍然有效；
- 不要把用户平时访问的支付页也改成 `pay.gulimall.vip`，否则会因为 Cookie 域不同而要求重新登录。

### 5.8 多个 Tunnel 和 Replica

- 一个 Cloudflare 账号可以创建多个 Tunnel；
- 同一个 Public Hostname（例如 `pay.gulimall.vip`）只能挂在一个 Tunnel 的一条 Route 上；
- Windows 和 Linux 两个环境都要用时，建议使用不同子域名：
  - 开发：`pay-dev.gulimall.vip`
  - 最终：`pay.gulimall.vip`
- 或者切换环境时，删除/停用旧 Route，再把同一个域名挂到新 Tunnel；
- 同一个 Tunnel 可以加多个 Replica 做高可用，但每个 connector 必须能访问同一个 Service URL；
  - 如果 Service URL 是 `localhost:9000`，Windows 和 Linux 的 localhost 是两个不同的服务；
  - 只有两个 connector 都能访问同一台 order 时，多 Replica 才有意义。

### 5.9 从 Windows 迁移到 Linux（Dashboard 管理）

Dashboard 管理的 Tunnel，**Routes 存在 Cloudflare 云端，不在 cloudflared 本地文件里**。在 Linux 上用同一个 Tunnel token 安装后，原来的 Hostname/Route 自动沿用，不需要在 Linux 上重新写 Route。

**迁移步骤：**

1. **本地只做一件事：把 RPM 传到 Linux**：
   ```powershell
   scp D:\SoftwareInstallation\gulimall\deploy\cloudflare\cloudflared-linux-x86_64.rpm root@<your-vm-host-ip>:/tmp/
   ```
2. **登录 Linux，后续命令都在 Linux 上执行**：
   ```bash
   ssh root@<your-vm-host-ip>

   # 安装 RPM（如果提示 already installed，用 rpm -Uvh --replacepkgs）
   rpm -ivh /tmp/cloudflared-linux-x86_64.rpm
   cloudflared --version
   ```
3. 如果之前装过 `cloudflared service`
   - `cloudflared service install` 在服务已经存在时会报错（already installed / service already exists）。
   先停掉、卸载旧服务，再重新安装：

    ```bash
    # 1. 停掉并禁用旧服务
    systemctl stop cloudflared 2>/dev/null || true
    systemctl disable cloudflared 2>/dev/null || true
    
    # 2. 用 cloudflared 自带命令卸载旧 service
    cloudflared service uninstall
    ```
4. **在 Linux 上注册服务**：
   ```bash
   cloudflared service install <TOKEN>
   systemctl enable --now cloudflared
   systemctl status cloudflared --no-pager
   journalctl -u cloudflared -n 50 --no-pager | grep -i 'Registered tunnel connection'
   ```
5. 域名：
   - `pay.gulimall.vip` → `http://localhost:9000`无需修改
   - 其他域名继续指向 `http://<your-vm-host-ip>:80`（Nginx）；
   - AI 不需要单独的 `agent.gulimall.vip` Route，见 §5.5.1。
6. 停掉 Windows 上的 cloudflared：
   ```powershell
   Get-Service *cloudflare*
   Stop-Service cloudflared
   Set-Service cloudflared -StartupType Disabled
   # 确认不再需要后可卸载
   cloudflared.exe service uninstall
   ```
7. Dashboard 删除旧 Windows connector：
   Zero Trust → Networks → Tunnels → 对应 Tunnel → Connectors/Replicas → 删除 Windows 那条。
   **删除 connector 不会删 Tunnel，也不会删 Routes**。
8. 验证：
   ```bash
   # 在不配 hosts 的设备上执行
   curl -I https://gulimall.vip/
   curl -I https://admin.gulimall.vip/
   curl -X POST https://pay.gulimall.vip/pay/notify -d "test=1"   # 预期 failure（验签失败正常）
   ```

## 六、Named Tunnel：CLI 本地管理方式（可选）

如果你不想用 Dashboard 的 token，也可以在本地用配置文件管理。

### 6.1 登录并创建隧道

```bash
cloudflared tunnel login
cloudflared tunnel create gulimall-dev
```

创建成功后会生成：

```text
C:\Users\<你的用户名>\.cloudflared\<TUNNEL_ID>.json
```

### 6.2 绑定域名

```bash
cloudflared tunnel route dns gulimall-dev order.example.com
```

### 6.3 配置文件

用户目录配置文件：

```text
C:\Users\<你的用户名>\.cloudflared\config.yml
```

内容：

```yaml
tunnel: <TUNNEL_ID>
credentials-file: C:\Users\<你的用户名>\.cloudflared\<TUNNEL_ID>.json

ingress:
  - hostname: order.example.com
    service: http://localhost:9000
  - service: http_status:404
```

运行：

```bash
cloudflared tunnel run gulimall-dev
```

### 6.4 Windows 作为服务运行（重要坑）

`cloudflared` 作为 Windows 服务运行时，是以 **LocalSystem** 账户运行的，**不会读取你用户目录下的 `config.yml`**。

服务实际读取的路径是：

```text
C:\Windows\System32\config\systemprofile\.cloudflared\config.yml
```

凭证文件也要放到这个目录：

```text
C:\Windows\System32\config\systemprofile\.cloudflared\<TUNNEL_ID>.json
```

然后修改注册表：

```text
HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Services\Cloudflared
```

把 `ImagePath` 改成类似：

```text
C:\SoftwareInstallation\cloudflared\cloudflared.exe --config=C:\Windows\System32\config\systemprofile\.cloudflared\config.yml tunnel run
```

注意不要有多余空格或引号，否则服务可能启动失败。

重启服务：

```powershell
Restart-Service cloudflared
```

> 更推荐 Dashboard + token 方式，不需要折腾 LocalSystem 配置路径。

---

## 七、域名购买建议

### 7.1 推荐注册商

| 注册商 | 优点 | 注意事项 |
|---|---|---|
| **Cloudflare Registrar** | 按成本价，续费不涨价；自带 Cloudflare DNS | 必须把域名放在 Cloudflare；支持的 TLD 比其它家少 |
| **Porkbun** | 价格接近成本，TLD 多，WHOIS 隐私免费 | 需要自己把 NS 指到 Cloudflare |
| **Namecheap** | 首年便宜，TLD 多，服务全 | 续费通常比首年贵很多，买之前看续费价 |
| 阿里云 / 腾讯云 | 国内访问方便，支付方便 | 需要实名；面向中国大陆正式提供服务通常涉及备案 |

> **Cloudflare Registrar 的支付问题**：它主要支持境外信用卡 / PayPal，国内常用的支付宝、微信、部分银联卡可能无法完成支付。
> 如果付款不方便，可以在国内注册商购买，再把 NS 改到 Cloudflare：
>
> - 阿里云万网：<https://wanwang.aliyun.com/>
> - 腾讯云域名：<https://buy.cloud.tencent.com/domain>
>
> 国内注册商需要实名认证；Cloudflare Tunnel 不改变 ICP 备案等合规要求。

### 7.2 买什么后缀

- 只是开发测试：`.xyz`、`.top` 等首年便宜的后缀即可；
- 长期使用：`.com`、`.dev`、`.app` 更稳；
- 买之前一定要看**续费价格**，不要只看首年；
- 不要买特别冷门、后续无法转出的后缀。

### 7.3 购买后接入 Cloudflare

1. 在注册商购买域名；
2. Cloudflare Dashboard 首页点 **Add a domain** 卡片，或左侧 **Domains → Add a domain**；
3. 输入 `gulimall.vip`，选择 Free 计划；
4. Cloudflare 扫描现有 DNS 记录（新域名一般没有），继续；
5. Cloudflare 给出两个 nameserver；
6. 回阿里云“DNS 修改”页面，把原来的 `dns19.hichina.com` / `dns20.hichina.com`
   替换成 Cloudflare 的两个 nameserver；
7. 等待 Cloudflare 显示 Active（通常几分钟到几小时）；
8. 再创建 Tunnel 的 Public Hostname。

如果直接在 Cloudflare Registrar 买，域名天然就在 Cloudflare，不需要改 NS。

---

## 八、支付宝回调专项说明

### 8.1 notify 和 return

| 地址 | 谁访问 | 要求 |
|---|---|---|
| `notify_url` | 支付宝服务器 POST | 必须公网可访问；处理成功返回纯文本 `success` |
| `return_url` | 用户浏览器 GET | 可以是回调域名；不能作为支付成功唯一依据 |

### 8.2 必须排除登录拦截

`gulimall-order` 的 `LoginUserInterceptor` 拦截所有路径。支付宝服务器没有登录会话，必须放行：

```java
.excludePathPatterns("/error",
        "/confirm/js/**", "/confirm/css/**", "/confirm/img/**",
        "/pay/css/**", "/pay/js/**", "/pay/img/**",
        "/pay/notify", "/pay/return")
```

### 8.3 不要开 Cloudflare Access 拦截 notify

如果启用了 Access：

- 支付宝 POST `/pay/notify` 会收到登录页，支付状态永远不同步；
- 必须为 `/pay/notify` 创建 Bypass 策略，或者不要对 `order.example.com` 启用 Access。

### 8.4 测试顺序

1. 启动 order、ware、MySQL、Redis、Nacos；
2. 启动 `cloudflared`；
3. 浏览器访问 `https://order.example.com/pay.html?orderSn=...`，确认能打开；
4. 发起支付，用沙箱买家账号付款；
5. 观察 order 日志是否收到 `/pay/notify`；
6. 检查 `oms_order.status`、`oms_payment_info`、`wms_ware_order_task.task_status`。

### 8.5 国内网络提示

Cloudflare 的边缘节点在海外，国内访问可能出现：

- 延迟高；
- 偶尔超时；
- Quick Tunnel 的 `trycloudflare.com` 在部分运营商网络下解析失败。

### 8.6 OAuth 回调（GitHub / Gitee / 微信）

OAuth 回调和支付宝一样走 Tunnel，要求配置与平台后台**完全一致**：

| 平台 | 配置项 | 后台登记 |
|---|---|---|
| GitHub | `OAUTH_GITHUB_REDIRECT_URI=http://auth.gulimall.vip/oauth2/github/success` | GitHub OAuth App 的 Authorization callback URL |
| Gitee | `OAUTH_GITEE_REDIRECT_URI=http://auth.gulimall.vip/oauth2/gitee/success` | Gitee 应用回调地址 |
| 微信 | `OAUTH_WECHAT_REDIRECT_URI=http://auth.gulimall.vip/oauth2/wechat/success` | 测试号「网页服务 → 授权回调页面域名」填 `auth.gulimall.vip`（不能带端口/路径） |

注意事项：

1. **协议统一**：如果 Cloudflare 强制跳 `https://`，回调地址也要同步改成 `https://`，否则报 `redirect_uri_mismatch`；
2. **Access 不能拦回调**：给 `auth.gulimall.vip` 开 Cloudflare Access 时，要给 `/oauth2/*` 加 Bypass；
3. 微信测试号只能用于调试，不能上线。

支付宝沙箱回调测试一般能用，但**生产支付回调**建议：

- 使用稳定的公网服务器 + 自己的域名；
- 或使用国内合规的接入方式；
- 正式对外提供服务仍需遵守 ICP 备案等规定。

---

## 九、缓存规则（Cache Rules）

Tunnel 打通后，Cloudflare 默认会缓存一部分静态资源。如果不显式配置，可能出现
"改了 css 但页面没变""接口被缓存导致数据不刷新"这类问题。本节结合本项目 Nginx 的源站头配置。

### 9.1 先分清哪些能缓存

| 内容 | 项目里的源站头 | 能否让 Cloudflare 缓存 |
|---|---|---|
| `/`、`*.html`（Thymeleaf 页面、admin 入口） | 无 | ❌ 不能。页面里有登录态、价格、库存 |
| `/gulimall/**`（所有 API） | 无 | ❌ 不能。接口数据随用户/库存变化 |
| `/es/fenci.txt`（IK 词库） | `no-store` | ❌ 不能。IK 每分钟要拉最新词库 |
| `pay.gulimall.vip/**`（支付宝回调） | 无 | ❌ 不能 |
| `*.css/js/png/jpg/...`（各站点静态） | `max-age=30d, immutable` | ✅ 可以，但**文件名不带 hash** |
| `/oss/**`、`img.gulimall.vip/**`（图片） | `max-age=7d` | ✅ 可以 |

> Nginx 对业务站点的静态资源已经设置了 `expires 30d; Cache-Control: public, immutable`，
> 对 `/oss/` 图片设置 `expires 7d`，对 `/es/fenci.txt` 设置 `no-store`。
> Cloudflare 的规则是在此基础上决定**边缘节点**缓存多久。

### 9.2 操作路径

Cloudflare Dashboard → 选中 `gulimall.vip` → **Caching → Cache Rules → Create rule**。

规则**按顺序匹配，第一条命中后停止**，所以 Bypass 必须放最前面。

> **中文界面字段对照**：Cache eligibility = **缓存资格**；Bypass cache = **绕过缓存**；
> Eligible for cache = **有资格获得缓存**；Edge TTL = **边缘 TTL**；
> Browser TTL = **浏览器 TTL**；Override origin = **覆盖原点并使用这个 TTL**。
> 表达式粘贴到「如果收到的请求匹配 → 自定义滤波表达式」下面的输入框。

### 9.3 规则 1：bypass-dynamic（动态内容一律不缓存）

When incoming requests match（Custom filter expression）：

```
(starts_with(http.request.uri.path, "/gulimall/"))
or (http.host eq "pay.gulimall.vip")
or (http.request.uri.path eq "/es/fenci.txt")
or (ends_with(http.request.uri.path, ".html"))
or (http.request.uri.path eq "/")
or (http.cookie contains "GULIMALLSESSION")
```

Then：**Cache eligibility = Bypass cache**。

> 最后一条 `GULIMALLSESSION` 是双保险：任何带登录 Cookie 的请求都不进缓存。
> `/` 和 `.html` 已覆盖所有 Thymeleaf 页面、admin 的 `index.html`、`pay.html`。

### 9.4 规则 2：cache-images（图片长缓存）

```
(starts_with(http.request.uri.path, "/oss/"))
or (http.host eq "img.gulimall.vip")
```

Then：

| 选项 | 值 |
|---|---|
| Cache eligibility | Eligible for cache |
| Edge TTL | Ignore cache-control header and use this TTL → **7 days** |
| Browser TTL | Override origin → **7 days** |

### 9.5 规则 3：cache-static（css/js/字体）

```
(ends_with(http.request.uri.path, ".css"))
or (ends_with(http.request.uri.path, ".js"))
or (ends_with(http.request.uri.path, ".png"))
or (ends_with(http.request.uri.path, ".jpg"))
or (ends_with(http.request.uri.path, ".jpeg"))
or (ends_with(http.request.uri.path, ".gif"))
or (ends_with(http.request.uri.path, ".ico"))
or (ends_with(http.request.uri.path, ".svg"))
or (ends_with(http.request.uri.path, ".webp"))
or (ends_with(http.request.uri.path, ".woff"))
or (ends_with(http.request.uri.path, ".woff2"))
or (ends_with(http.request.uri.path, ".ttf"))
or (ends_with(http.request.uri.path, ".eot"))
```

> **不要用 `matches` 正则**。Cloudflare 免费版会报：
> `not entitled: the use of operator Matches is not allowed, a Business plan or a WAF Advanced plan is required`。
> 上面的 `ends_with` 组合在免费版可用，效果等价。

Then：

| 选项 | 值 |
|---|---|
| Cache eligibility | Eligible for cache |
| Edge TTL | Ignore cache-control header and use this TTL → **1 day** |
| Browser TTL | Override origin → **4 hours** |

> 为什么静态资源只给 1 天 / 4 小时？因为项目里的文件名**不带内容 hash**
> （如 `jquery-3.1.1.min.js`、`GL.css`），改了文件但 URL 不变。
> 设成 30 天会导致发版后用户长时间拿旧文件。图片文件内容不变，可以放心 7 天。

### 9.6 全局设置（Caching → Configuration）

| 项 | 值 | 原因 |
|---|---|---|
| Caching Level | Standard | 只缓存静态扩展名，不缓存 HTML |
| Browser Cache TTL | Respect Existing Headers | 由上面的 Cache Rules 分别覆盖 |
| Always Online | Off | 动态站点，缓存旧页面会误导用户 |
| Development Mode | 仅调试时临时开 | 开启后 3 小时绕过全部缓存 |
| Cache Deception Armor | On | 防止攻击者把动态页面伪装成静态资源缓存 |

### 9.7 建议关闭的优化项（Speed → Optimization）

| 项 | 建议 | 原因 |
|---|---|---|
| Rocket Loader | **Off** | 会异步改写页面里的 JS，旧版 jQuery/Swiper 容易失效 |
| Auto Minify | **Off** | Cloudflare 已不推荐，可能改坏内联脚本 |
| Email Obfuscation | **Off** | 会改 HTML 内容 |
| Brotli | On | 文本资源压缩，安全 |

### 9.8 发版后清缓存

Cloudflare Dashboard → Caching → Configuration → **Purge Cache → Purge Everything**。

因为静态文件名不带 hash，**改了 css/js 必须清**，否则边缘节点还会返回旧文件 1 天，
浏览器还可能再留 4 小时。长期方案是给静态资源 URL 加版本参数（如 `?v=20261027`）。

### 9.9 验证

```bash
# 静态资源：第二次请求应出现 cf-cache-status: HIT（第一次 MISS）
curl -I https://gulimall.vip/index/css/GL.css

# 页面 / API / 词库 / 支付：应为 BYPASS 或 DYNAMIC，不能是 HIT
curl -I https://gulimall.vip/
curl -I https://gulimall.vip/gulimall/product/brand/list
curl -I https://gulimall.vip/es/fenci.txt
curl -I https://pay.gulimall.vip/pay.html
```

> Cloudflare 只缓存 `GET`/`HEAD`。支付宝 notify 是 `POST`，本身不会被缓存；
> 规则里 bypass `pay` 是为了避免它的 GET 页面和静态资源被缓存后干扰排查。

---

## 十、常见问题

| 现象 | 排查 |
|---|---|
| 本地访问 `.vip` 返回 **530**，远程地址是 `127.0.0.1:7897` | 浏览器走了 Clash/TUN，不是本机 hosts。给 `DOMAIN-SUFFIX,gulimall.vip` 加 `DIRECT` 并重载，或关闭 Clash 的 DNS 覆写/TUN |
| 502 Bad Gateway | cloudflared 没连上，或 ingress 的 `service` 地址/端口不对 |
| 404 | Public Hostname 和实际访问域名不一致；或本地服务路径不对 |
| Tunnel 显示不 Healthy | 检查出站网络、token 是否过期、防火墙是否允许出站 |
| 支付宝 notify 收不到 | 地址是否公网、是否被 Access 拦截、是否被登录拦截器拦截、是否返回 `success` |
| 改了 config 不生效 | Windows 服务读的是 LocalSystem 配置路径，重启服务 |
| Quick Tunnel 地址变了 | 正常现象，重启后会变，更新支付宝配置并重启 order |
| 域名一直 Pending | NS 没改对，或还没生效 |

---

## 十一、命令速查

```bash
# 查看版本
cloudflared --version

# 一次性隧道（不需要域名/账号）
cloudflared tunnel --url http://localhost:9000

# 登录、创建命名隧道
cloudflared tunnel login
cloudflared tunnel create gulimall-dev

# 绑定域名
cloudflared tunnel route dns gulimall-dev order.example.com

# 运行命名隧道
cloudflared tunnel run gulimall-dev

# 安装为 Windows 服务（Dashboard 给的 token 方式）
cloudflared.exe service install <TOKEN>

# 安装为 Linux 服务（Dashboard 给的 token 方式）
sudo cloudflared service install <TOKEN>

# Docker 方式
docker run -d --name cloudflared --restart unless-stopped \
  cloudflare/cloudflared:latest \
  tunnel --no-autoupdate run --token <TOKEN>
```

---

## 参考

- Cloudflare Tunnel 快速开始：<https://developers.cloudflare.com/tunnel/get-started/>
- Quick Tunnels：<https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/>
- Windows 作为服务运行：<https://developers.cloudflare.com/tunnel/features/locally-managed-tunnels/as-a-service/windows/>
- 创建 Tunnel（Dashboard）：<https://developers.cloudflare.com/learning-paths/clientless-access/connect-private-applications/create-tunnel/>
- 支付宝沙箱环境：<https://opendocs.alipay.com/open/02np8i>
- 支付宝同步/异步通知：<https://opendocs.alipay.com/support/01raw5>
