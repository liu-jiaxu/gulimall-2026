# gulimall 访问流程说明

> 一次请求从浏览器发出后，经过哪些环节到达最终服务。
>
> 新增模块时怎么配静态资源，见同目录 **`新模块静态资源接入.md`**。

---

## 一、当前访问流程

当前项目通过 **Cloudflare Tunnel** 对外提供访问：

```text
浏览器
  │  ① 访问 https://xxx.gulimall.vip
  ↓
Cloudflare DNS / Edge
  │  ② 域名解析到 Cloudflare，HTTPS 在 Cloudflare 边缘终结
  ↓
Cloudflare Tunnel（cloudflared，运行在 Windows）
  │  ③ 隧道把请求转发到配置的 Service URL
  │
  ├── pay.gulimall.vip  -> http://localhost:9000（order，直接回源）
  │
  └── 其它 *.gulimall.vip -> http://<your-vm-host-ip>:80（虚拟机 Nginx）
                                  │
                                  │ ④ Nginx 按 HTTP 请求头里的 Host 选 server 块
                                  ↓
                              按路径选 location
                                  │
                                  ├── /              -> product :10000
                                  ├── /gulimall/**   -> Gateway :88 -> Nacos -> 微服务
                                  ├── search 站点    -> search :12000
                                  ├── admin 站点     -> Nginx 静态 html/admin/
                                  ├── auth 站点      -> auth :20000
                                  ├── cart 站点      -> cart :13000
                                  ├── order 站点     -> order :9000
                                  └── img 站点       -> MinIO :9000
```

### Cloudflare Route 与本地服务

| 公网域名 | Cloudflare Route 的 Service URL | 最终去向 |
|---|---|---|
| `gulimall.vip` | `http://<your-vm-host-ip>:80` | Nginx → product / Gateway |
| `admin.gulimall.vip` | `http://<your-vm-host-ip>:80` | Nginx → 静态文件 / Gateway |
| `img.gulimall.vip` | `http://<your-vm-host-ip>:80` | Nginx → MinIO |
| `search.gulimall.vip` | `http://<your-vm-host-ip>:80` | Nginx → search |
| `auth.gulimall.vip` | `http://<your-vm-host-ip>:80` | Nginx → auth |
| `cart.gulimall.vip` | `http://<your-vm-host-ip>:80` | Nginx → cart |
| `order.gulimall.vip` | `http://<your-vm-host-ip>:80` | Nginx → order |
| `pay.gulimall.vip` | `http://localhost:9000` | 直接到 order，供支付宝 notify |

> `cloudflared` 运行在哪台机器，Service URL 就按那台机器能访问到的地址写。
>
> - cloudflared 和 order 都在 Windows：`http://localhost:9000`
> - cloudflared 在虚拟机、order 在 Windows：`http://<your-windows-host-ip>:9000`
> - cloudflared 在虚拟机、Nginx 也在虚拟机：`http://localhost:80`

### 域名被用了两次

| | 用在哪 | 作用 | 谁负责 |
|---|---|---|---|
| 第 1 次 | DNS 解析 | 找到 Cloudflare / 服务器 | Cloudflare DNS |
| 第 2 次 | HTTP 请求头的 `Host` | 在同一台 Nginx 上选出站点 | Nginx `server_name` |

Cloudflare Tunnel 默认保留原始 `Host`，所以 Nginx 收到的仍然是：

```text
Host: auth.gulimall.vip
```

Nginx 才能用 `server_name` 匹配到对应站点。

### 本地直连方式（调试用）

如果只想在局域网内绕过 Cloudflare：

```text
浏览器
  -> hosts: auth.gulimall.vip = <your-vm-host-ip>
  -> Nginx :80
  -> Host 匹配 -> auth :20000
```

这种方式需要访问端自己配 hosts；公网访问则走 Cloudflare DNS，不需要 hosts。

---

## 二、各站点怎么分流

### 1. 页面 / 接口

| 请求 | Nginx 处理 |
|---|---|
| `gulimall.vip/` | 反代 `gulimall-product :10000`，Thymeleaf 现场渲染 |
| `gulimall.vip/gulimall/**` | 反代 Gateway :88，再由 Nacos 找具体微服务 |
| `search.gulimall.vip/` | 反代 `gulimall-search :12000` |
| `search.gulimall.vip/gulimall/**` | 反代 Gateway :88 |
| `admin.gulimall.vip/` | Nginx 直接返回磁盘 `html/admin/`（Vue dist） |
| `admin.gulimall.vip/gulimall/**` | 反代 Gateway :88 |
| `auth.gulimall.vip/` | 反代 `gulimall-auth-server :20000` |
| `cart.gulimall.vip/` | 反代 `gulimall-cart :13000` |
| `order.gulimall.vip/` | 反代 `gulimall-order :9000` |
| `img.gulimall.vip/` | 反代 MinIO :9000 |
| `pay.gulimall.vip/pay/notify` | 直接到 order :9000，不走 Nginx |

### 2. 图片访问

#### `/oss/<bucket>/<对象>`

数据库里存的是根相对路径，例如：

```text
/oss/gulimall/brand/2026-09-07/xxx.png
```

浏览器用当前页面的源补全，Nginx 命中 `img-proxy.inc`：

```text
location ^~ /oss/ -> proxy_pass http://minio:9000/
```

#### `img.gulimall.vip/<bucket>/<对象>`

Nginx 的 `img.gulimall.vip` 站点把整个 `/` 反代到 MinIO，适合独立图片直链。

两条路都不经过 Gateway，也不经过微服务。

### 3. 服务之间调用

```text
order -> OpenFeign(@FeignClient("gulimall-ware"))
      -> Nacos 查实例
      -> ware 的私有 IP:端口
```

这条链路不经过 Nginx，也不经过 Cloudflare。

---

## 三、静态资源目录约定

### product / search：引用带模块名前缀

页面在根路径下，例如：

```text
http://gulimall.vip/
```

页面引用：

```html
<link rel="stylesheet" href="index/css/xxx.css">
<img src="item/img/xxx.jpg">
```

浏览器解析成：

```text
http://gulimall.vip/index/css/xxx.css
http://gulimall.vip/item/img/xxx.jpg
```

Nginx 的 `root` 指到 `html/`，磁盘路径正好是：

```text
/usr/share/nginx/html/index/css/xxx.css
/usr/share/nginx/html/item/img/xxx.jpg
```

### 其它模块：引用不带前缀

页面在模块子路径下，例如：

```text
http://gulimall.vip/coupon/
```

页面引用：

```html
<link rel="stylesheet" href="css/xxx.css">
```

浏览器会补成：

```text
http://gulimall.vip/coupon/css/xxx.css
```

Nginx 的 `root` 指到 `html/<模块>/`。

### 判断规则

- 页面 URL 在根下：引用带模块名前缀，`root` 指 `html/`
- 页面 URL 在模块子路径下：引用不带前缀，`root` 指 `html/<模块>/`

两种约定不能混，混了必然 404。

---

## 四、端口一览

| 服务 | 端口 | 说明 |
|---|---|---|
| Nginx | 80 | 对 Cloudflare Tunnel 提供入口 |
| Gateway | 88 | 只给 Nginx 转发 |
| product | 10000 | 页面 + 接口 |
| search | 12000 | 搜索页 + 接口 |
| auth | 20000 | 登录注册 |
| cart | 13000 | 购物车 |
| order | 9000 | 订单 / 支付回调 |
| ware | 11000 | 库存 |
| MinIO | 9000 | 图片对象 |
| Nacos | 8848 / 9849 | 注册/配置中心，不建议公网暴露 |

---

## 五、Nginx 配置修改后的注意事项

改完 `gulimall.conf` 后必须 reload：

```bash
docker exec nginx nginx -t
docker exec nginx nginx -s reload
```

否则 Nginx 仍然使用内存里的旧配置。

典型症状：

- 新加的 `server_name auth.gulimall.vip` 没生效；
- 请求落到第一个 server（`gulimall.vip`）；
- 最终由 product 返回 `No static resource login.html` 的 404。

### 排查顺序

1. 确认容器里加载的配置：

```bash
docker exec nginx nginx -T | grep -n "server_name"
```

2. 直接带 Host 测试 Nginx：

```bash
curl -I -H "Host: auth.gulimall.vip" http://<your-vm-host-ip>/login.html
```

3. 测 Nginx 到后端：

```bash
docker exec nginx wget -S -O- http://host.docker.internal:20000/login.html
```

4. 看 Nginx 访问日志：

```bash
docker logs nginx --tail 50
```

- 404 且响应体是 Spring 的 JSON：多半是 Host 没匹配到正确 server，落到了主站；
- 502：Nginx 到后端服务不通；
- 200/302：链路正常。

---

## 六、Cloudflare Tunnel 与本地访问的区别

| 方式 | 域名解析 | 请求路径 | 是否依赖 cloudflared |
|---|---|---|---|
| 公网访问 | Cloudflare DNS | Cloudflare → Tunnel → Nginx/order | 是 |
| 局域网直连 | 本机 hosts | 浏览器 → <your-vm-host-ip>:80 | 否 |

公网访问时不要在本机 hosts 再写 `.vip`，否则浏览器会绕过 Cloudflare，直接连内网 IP。
