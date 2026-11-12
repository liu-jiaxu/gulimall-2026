# JWT 原理与 gulimall 项目使用说明

JWT（JSON Web Token）是一种用字符串表示身份凭证的开放标准。服务端签发 JWT，客户端保存 JWT，后续请求携带 JWT，服务端验签后确认请求身份。

gulimall 中 JWT 只用于 AI 助手。商城用户端和管理端的常规登录态分别由 Spring Session 和 renren-fast 数据库 token 承担，AI 助手使用独立的 RS256 JWT。

---

# 一、JWT 基础

## 1.1 结构

JWT 由两个英文句点分隔的三段字符串组成：

```text
Base64URL(Header) . Base64URL(Payload) . Base64URL(Signature)
```

| 部分 | 内容 | 可读性 |
| --- | --- | --- |
| Header | 算法 `alg`、类型 `typ`、密钥编号 `kid` | Base64URL 解码后可读 |
| Payload | 签发方 `iss`、接收方 `aud`、主体 `sub`、过期时间 `exp`、业务声明 | Base64URL 解码后可读 |
| Signature | 对前两段拼接结果的签名 | 没有私钥无法伪造 |

Header 示例：

```json
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "user-2026-01"
}
```

Payload 示例：

```json
{
  "iss": "gulimall-auth-server",
  "aud": "gulimall-agent",
  "sub": "1",
  "username": "zhangsan",
  "role": "user",
  "iat": 1762200000,
  "exp": 1762201800,
  "jti": "4f3c2a10-6d9a-4c47-9f2e-123456789abc"
}
```

JWT 的编码规则：

1. Header 和 Payload 使用 Base64URL，不使用普通 Base64。
2. Base64URL 把 `+` 替换成 `-`，把 `/` 替换成 `_`，并去掉末尾的 `=` 填充。
3. Signature 是二进制签名结果再做 Base64URL 编码后的文本。
4. Payload 只做编码，不做加密。拿到 JWT 的任何人都能解开并读取内容。

## 1.2 签名算法

JWT 常见的两种签名算法：

| 对比项 | HS256 | RS256 |
| --- | --- | --- |
| 算法类型 | 对称加密，HMAC-SHA256 | 非对称加密，RSA + SHA-256 |
| 签发使用 | 同一个密钥 | 私钥 |
| 验证使用 | 同一个密钥 | 公钥 |
| 密钥持有者 | 所有签发方和验证方 | 私钥只在签发方，验证方只持有公钥 |
| 伪造风险 | 任意验证方泄露共享密钥即可伪造 | 验证方只有公钥，泄露公钥无法伪造 |
| 适用场景 | 单一签发方、单一验证方 | 多个签发方、多个验证方 |

RSA 的密钥对应关系：

1. 一个签发方对应一套 RSA 密钥对，包括一个私钥和一个公钥。
2. 私钥只保存在签发服务中，用于生成签名。
3. 公钥可以分发给所有验证方，用于验证签名。
4. 多个验证方可以共用同一把公钥。
5. 多个签发方各自持有独立的密钥对，彼此不能互相签发。

gulimall 的 AI 助手使用两套 RSA 密钥对：

| 签发方 | 私钥 | 公钥 | `kid` | 角色 |
| --- | --- | --- | --- | --- |
| `gulimall-auth-server` | `jwt/user/jwt_private.pem` | `jwt/user/jwt_public.pem` | `user-2026-01` | `user` |
| `renren-fast` | `jwt/admin/jwt_private.pem` | `jwt/admin/jwt_public.pem` | `admin-2026-01` | `admin` |

`gulimall-agent` 只保存两把公钥，不持有任何私钥。

## 1.3 RS256 签发流程

1. 后端把 Header、Payload 分别序列化为 JSON。
2. 分别做 Base64URL 编码，得到 `b64u(Header)` 和 `b64u(Payload)`。
3. 拼接签名输入：

   ```text
   signingInput = b64u(Header) + "." + b64u(Payload)
   ```

4. 对 `signingInput` 计算 SHA-256 摘要：

   ```text
   digest = SHA-256(signingInput)
   ```

5. 把摘要算法标识和 digest 编码成 DER 结构的 `DigestInfo`。
6. 使用 PKCS#1 v1.5 填充，用私钥对 `DigestInfo` 签名：

   ```text
   signature = RSA_Sign(PKCS1_v1_5, PRIVATE_KEY, DigestInfo(SHA-256, digest))
   ```

7. 对签名结果做 Base64URL 编码。
8. 拼接最终 JWT：

   ```text
   JWT = signingInput + "." + b64u(signature)
   ```

## 1.4 RS256 验签流程

1. 按 `.` 拆分 JWT，必须正好得到三段。
2. 解码 Header，确认 `alg` 是允许的算法。
3. 读取 Header 中的 `kid`，按 `kid` 选择对应公钥。
4. 重新拼接签名输入：

   ```text
   signingInput = 第一段 + "." + 第二段
   ```

5. 计算本地摘要：

   ```text
   digestA = SHA-256(signingInput)
   ```

6. 使用公钥验证签名，RSA 验签会从签名中恢复摘要 `digestB`，并与 `digestA` 比较：

   ```text
   valid = RSA_Verify(PKCS1_v1_5, PUBLIC_KEY, signature, DigestInfo(SHA-256, digestA))
   ```

7. 验签通过后校验声明：

   ```text
   exp > 当前时间
   nbf <= 当前时间（存在时校验）
   iss == 期望的签发方
   aud 包含期望的接收方
   role 与 kid 对应的角色一致
   sub 非空并且可以解析为用户 id
   ```

8. 全部校验通过后，才信任 `sub`、`role` 等业务字段。

## 1.5 签名的作用范围

签名可以防止：

1. 篡改 Payload。修改 `role=user` 为 `role=admin` 后无法生成合法 Signature。
2. 冒充其他用户。`sub` 被修改后签名失效。
3. 跨签发方混用凭证。`kid`、`iss`、`role` 共同限制凭证来源。

签名不能防止：

1. Payload 被读取。JWT 不是加密容器，不能存放密码、手机号、身份证号等敏感信息。
2. JWT 被复制后在有效期内重放。JWT 是无状态凭证，服务端不查库、不查 Redis，复制走的 JWT 在 `exp` 之前仍然有效。
3. HTTP 明文链路上的窃取。JWT 必须通过 HTTPS 传输。
4. 私钥泄露后的伪造。私钥必须只保存在签发服务中。

## 1.6 密钥生成

在 Git Bash 中执行：

```bash
mkdir -p /d/SoftwareInstallation/gulimall/jwt/admin /d/SoftwareInstallation/gulimall/jwt/user

openssl genrsa -out /d/SoftwareInstallation/gulimall/jwt/admin/jwt_private.pem 2048
openssl rsa -in /d/SoftwareInstallation/gulimall/jwt/admin/jwt_private.pem -pubout -out /d/SoftwareInstallation/gulimall/jwt/admin/jwt_public.pem

openssl genrsa -out /d/SoftwareInstallation/gulimall/jwt/user/jwt_private.pem 2048
openssl rsa -in /d/SoftwareInstallation/gulimall/jwt/user/jwt_private.pem -pubout -out /d/SoftwareInstallation/gulimall/jwt/user/jwt_public.pem

chmod 600 /d/SoftwareInstallation/gulimall/jwt/admin/jwt_private.pem
chmod 600 /d/SoftwareInstallation/gulimall/jwt/user/jwt_private.pem
```

文件格式要求：

1. Java 私钥使用 `PKCS8EncodedKeySpec` 加载，私钥文件头必须是 `-----BEGIN PRIVATE KEY-----`。
2. Java 公钥使用 `X509EncodedKeySpec` 加载，公钥文件头必须是 `-----BEGIN PUBLIC KEY-----`。
3. OpenSSL 3.x 的 `genrsa` 默认输出 PKCS#8，符合 Java 加载要求。
4. 旧版 OpenSSL 输出 `-----BEGIN RSA PRIVATE KEY-----` 时，需要转换为 PKCS#8：

```bash
openssl pkcs8 -topk8 -nocrypt -in jwt_private.pem -out jwt_private_pkcs8.pem
```

当前仓库中的四把密钥文件头均为 `BEGIN PRIVATE KEY` 或 `BEGIN PUBLIC KEY`，与代码中的加载方式一致。

生产环境中私钥不能提交到 Git，应通过挂载目录或密钥管理系统注入，只挂载给对应的签发服务。

---

# 二、gulimall 的登录态与 JWT 定位

## 2.1 三套凭证

| 端 | 凭证 | 存储位置 | 有效期 | 使用范围 |
| --- | --- | --- | --- | --- |
| 用户端常规登录 | `GULIMALLSESSION` Cookie | Redis，namespace `gulimall:session` | 7 天滑动过期 | 商城页面登录态、auth-server 刷新 agent JWT |
| 管理端常规登录 | 请求头 `token` | MySQL 表 `sys_user_token` | 12 小时 | 管理后台接口、renren-fast 刷新 agent JWT |
| 两端 AI 助手 | `AGENT_JWT_USER` / `AGENT_JWT_ADMIN` Cookie | 浏览器 HttpOnly Cookie | 30 分钟 | `gulimall-agent` 的 AI 接口 |

登录成功后的凭证写入：

```text
用户登录
  ├─ Spring Session 写入 Redis（常规登录态）
  └─ 签发 agent JWT（AI 专用）

管理员登录
  ├─ sys_user_token 写入 MySQL（常规登录态）
  └─ 签发 agent JWT（AI 专用）
```

agent JWT 独立于常规登录态的原因：

1. `gulimall-agent` 不需要读取 Redis 中的 Spring Session。
2. `gulimall-agent` 不需要查询 MySQL 中的 `sys_user_token`。
3. agent 只保存公钥，本地验签，不访问登录数据库。
4. 用户端和管理端使用独立密钥，角色和 Cookie 不会混用。
5. JWT 不落 MySQL、不落 Redis，过期后由原登录服务重新签发。

## 2.2 agent JWT 配置

| 配置项 | 用户端 | 管理端 |
| --- | --- | --- |
| 签发方 `iss` | `gulimall-auth-server` | `renren-fast` |
| 接收方 `aud` | `gulimall-agent` | `gulimall-agent` |
| 角色 `role` | `user` | `admin` |
| 密钥编号 `kid` | `user-2026-01` | `admin-2026-01` |
| Cookie 名 | `AGENT_JWT_USER` | `AGENT_JWT_ADMIN` |
| Cookie 域 | `.gulimall.vip` | `admin.gulimall.vip` |
| Cookie 路径 | `/gulimall/agent/` | `/gulimall/agent/` |
| 有效期 | 30 分钟 | 30 分钟 |
| 公钥 | `jwt/user/jwt_public.pem` | `jwt/admin/jwt_public.pem` |

Payload 字段：

| 字段 | 含义 |
| --- | --- |
| `iss` | 签发方 |
| `aud` | 接收方 |
| `sub` | 会员 id 或管理员 id |
| `username` | 登录名，用于页面展示 |
| `role` | `user` 或 `admin` |
| `iat` | 签发时间 |
| `exp` | 过期时间 |
| `jti` | JWT 唯一 id |

Cookie 属性：

| 属性 | 作用 |
| --- | --- |
| `HttpOnly` | JavaScript 无法读取，降低 XSS 窃取风险 |
| `Secure` | 只允许 HTTPS 发送，开发环境通过配置关闭 |
| `SameSite=Lax` | 降低跨站请求携带风险 |
| `Path=/gulimall/agent/` | 只有 AI 接口请求携带 |

## 2.3 三个 AgentJwtService

| 类 | 包 | 职责 |
| --- | --- | --- |
| 用户端签发服务 | `com.atguigu.gulimall.auth_server.agent.AgentJwtService` | 加载用户端私钥，签发 `role=user` JWT |
| 管理端签发服务 | `io.renren.modules.sys.agent.AgentJwtService` | 加载管理端私钥，签发 `role=admin` JWT |
| agent 验签服务 | `com.atguigu.gulimall.agent.auth.jwt.AgentJwtService` | 加载两把公钥，只验签，不签发 |

## 2.4 agent 请求路由

浏览器请求：

```text
http://gulimall.vip/gulimall/agent/auth/me
```

路由链路：

```text
Nginx 站点 gulimall.vip
  └─ include ai-widget.inc
       location ^~ /gulimall/agent/
         └─ proxy_pass http://gulimall_gateway

Spring Cloud Gateway
  └─ agent-route
       Path=/gulimall/agent/**
       StripPrefix=1
       转发到 lb://gulimall-agent

gulimall-agent
  └─ 接收 /agent/auth/me
```

Nginx 配置 `deploy/nginx/conf/conf.d/ai-widget.inc:8-28`：

```nginx
location ^~ /gulimall/agent/ {
    proxy_pass http://gulimall_gateway;

    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

    proxy_buffering off;
    proxy_cache off;
    gzip off;

    proxy_read_timeout 300s;
    proxy_send_timeout 300s;
}
```

网关配置 `gulimall-gateway/src/main/resources/application-gateway.yaml:232-239`：

```yaml
- id: agent-route
  uri: lb://gulimall-agent
  predicates:
    - Path=/gulimall/agent/**
  filters:
    - StripPrefix=1
  order: 7
```

`admin_route` 是兜底路由，`order: 99`：

```yaml
- id: admin_route
  uri: lb://renren-fast
  predicates:
    - Path=/gulimall/**
  filters:
    - RewritePath=/gulimall/(?<segment>.*),/renren-fast/$\{segment}
  order: 99
```

`agent-route` 的 order 小于 `admin_route`，`/gulimall/agent/**` 优先匹配 agent 路由。

---

# 三、用户端 JWT 流程

## 流程 1：用户登录并写入 Spring Session

用户通过密码登录，或通过 GitHub、Gitee、微信授权登录。登录成功后，auth-server 把会员信息写入 Spring Session。

`gulimall-auth-server/src/main/java/com/atguigu/gulimall/auth_server/web/LoginController.java:74-101`：

```java
@PostMapping("/login")
public String login(UserLoginVo userLoginVo,
                    @RequestParam(value = "returnUrl", required = false) String returnUrl,
                    RedirectAttributes redirectAttributes,
                    HttpSession session,
                    HttpServletResponse response) {

    R login = memberFeignClient.login(userLoginVo);
    if (login.getCode() == 0) {
        // 1. 取出登录成功的会员信息
        MemberResponseVo member = login.getData("data", new TypeReference<MemberResponseVo>() {
        });

        // 2. 写入 Spring Session，底层保存到 Redis
        session.setAttribute(AuthServerConstant.LOGIN_USER, member);

        // 3. 签发 AI 助手使用的用户端 JWT
        agentJwtService.issue(member, response);

        // 4. 登录成功后的跳转
        String target = isSafeReturnUrl(returnUrl) ? returnUrl : shopHomeUrl;
        return loginRedirectService.afterLogin(member, target);
    } else {
        redirectAttributes.addFlashAttribute("msg", login.get("msg").toString());
        return "redirect:" + authUrl + "/login.html";
    }
}
```

社交登录回调使用同一条签发逻辑：

| 登录方式 | 文件 | 行号 |
| --- | --- | --- |
| 微信 H5 回调 | `gulimall-auth-server/.../WechatOAuthController.java` | 279 |
| 微信跨浏览器轮询完成 | `gulimall-auth-server/.../WechatOAuthController.java` | 195 |
| Gitee 回调 | `gulimall-auth-server/.../GiteeOAuthController.java` | 177 |
| GitHub 回调 | `gulimall-auth-server/.../GitHubOAuthController.java` | 212 |

## 流程 2：使用用户端私钥签发 JWT

`gulimall-auth-server/src/main/java/com/atguigu/gulimall/auth_server/agent/AgentJwtService.java:77-106`：

```java
public long issue(Long memberId, String username, HttpServletResponse response) {
    if (memberId == null) {
        throw new IllegalArgumentException("会员 id 不能为空");
    }

    // 1. 计算签发时间和过期时间
    Instant now = Instant.now();
    Instant expiresAt = now.plus(properties.getTtl());

    // 2. 组装 Header 和 Payload，使用用户端私钥做 RS256 签名
    String token = Jwts.builder()
            .header().keyId(properties.getKeyId()).and()
            .issuer(properties.getIssuer())
            .audience().add(properties.getAudience()).and()
            .subject(String.valueOf(memberId))
            .claim("username", username == null ? String.valueOf(memberId) : username)
            .claim("role", "user")
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .id(UUID.randomUUID().toString())
            .signWith(privateKey, Jwts.SIG.RS256)
            .compact();

    // 3. 写入 HttpOnly Cookie
    ResponseCookie cookie = ResponseCookie.from(properties.getCookieName(), token)
            .domain(properties.getCookieDomain())
            .path(properties.getCookiePath())
            .httpOnly(true)
            .secure(properties.isCookieSecure())
            .sameSite("Lax")
            .maxAge(properties.getTtl())
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

    // 4. 返回过期时间戳
    return expiresAt.toEpochMilli();
}
```

私钥加载 `AgentJwtService.java:41-56`：

```java
@PostConstruct
public void loadPrivateKey() {
    try {
        // 1. 读取 PEM 文件，去掉 BEGIN/END 行和空白字符
        String pem = Files.readString(Path.of(properties.getPrivateKeyPath()), StandardCharsets.UTF_8)
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");

        // 2. Base64 解码后按 PKCS#8 格式生成 RSA 私钥
        byte[] encoded = Base64.getDecoder().decode(pem);
        privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(encoded));

        log.info("用户端 agent JWT 私钥加载完成 kid={}", properties.getKeyId());
    } catch (Exception ex) {
        throw new IllegalStateException("加载用户端 agent JWT 私钥失败："
                + properties.getPrivateKeyPath(), ex);
    }
}
```

## 流程 3：写入 HttpOnly Cookie

`AgentJwtService.java:96-104`：

```java
ResponseCookie cookie = ResponseCookie.from(properties.getCookieName(), token)
        .domain(properties.getCookieDomain())
        .path(properties.getCookiePath())
        .httpOnly(true)
        .secure(properties.isCookieSecure())
        .sameSite("Lax")
        .maxAge(properties.getTtl())
        .build();
response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
```

用户端 Cookie 参数：

| 参数 | 取值 | 作用 |
| --- | --- | --- |
| `name` | `AGENT_JWT_USER` | agent 按名称读取 |
| `domain` | `.gulimall.vip` | 对 `gulimall.vip` 的所有子域生效 |
| `path` | `/gulimall/agent/` | 只有 AI 接口请求携带 |
| `httpOnly` | `true` | 前端脚本无法读取 |
| `secure` | `${AGENT_JWT_COOKIE_SECURE}` | 生产环境只允许 HTTPS |
| `sameSite` | `Lax` | 跨站 POST 不携带 |
| `maxAge` | 30 分钟 | 与 JWT 的 `exp` 一致 |

配置 `config/gulimall-auth-server.yaml:51-63`：

```yaml
gulimall:
  agent:
    jwt:
      issuer: gulimall-auth-server
      audience: gulimall-agent
      cookie-name: AGENT_JWT_USER
      cookie-domain: .gulimall.vip
      cookie-path: /gulimall/agent/
      cookie-secure: ${AGENT_JWT_COOKIE_SECURE}
      ttl: 30m
      key-id: user-2026-01
      private-key-path: ${AGENT_JWT_USER_PRIVATE_KEY_PATH}
```

## 流程 4：使用 Spring Session 刷新 JWT

agent JWT 有效期为 30 分钟。Spring Session 仍然有效时，auth-server 可以重新签发 JWT，用户不需要重新登录。

`gulimall-auth-server/src/main/java/com/atguigu/gulimall/auth_server/agent/AgentJwtRefreshController.java:34-45`：

```java
@PostMapping("/agent/jwt/refresh")
public R refresh(HttpServletRequest request, HttpServletResponse response) {

    // 1. 读取当前请求的 Session
    HttpSession session = request.getSession(false);
    Object loginUser = session == null
            ? null
            : session.getAttribute(AuthServerConstant.LOGIN_USER);

    // 2. Session 中没有会员信息，旧登录态已经失效
    if (!(loginUser instanceof MemberResponseVo member) || member.getId() == null) {
        return R.error(15001, "登录已过期，请重新登录");
    }

    // 3. 重新签发 30 分钟 JWT，并更新 Cookie
    long expiresAt = agentJwtService.issue(member, response);

    // 4. 返回新的过期时间
    return R.ok().put("data", Map.of("expiresAt", expiresAt));
}
```

刷新接口只读取 Spring Session，不读取旧 JWT：

1. Session 有效时，即使旧 JWT 已过期，也可以换到新 JWT。
2. Session 过期时，即使旧 JWT 仍在有效期，也拒绝刷新。
3. 刷新过程不写数据库、不写 Redis，只重新签名。

用户端页面位于 `gulimall.vip`，刷新接口位于 `auth.gulimall.vip`，属于跨子域请求。auth-server 的跨域配置 `gulimall-auth-server/src/main/java/com/atguigu/gulimall/auth_server/config/GulimallWebConfig.java:27-35`：

```java
@Override
public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/agent/jwt/refresh")
            .allowedOriginPatterns("http://gulimall.vip", "http://*.gulimall.vip",
                    "https://gulimall.vip", "https://*.gulimall.vip")
            .allowedMethods("POST", "OPTIONS")
            .allowCredentials(true)
            .maxAge(3600);
}
```

## 流程 5：浏览器携带 Cookie 请求 agent

用户端聊天控件 `gulimall-agent/src/main/resources/static/agent/widget/chat-widget.js:19-21`：

```javascript
var API = '/gulimall/agent';
var LOGIN_URL = 'http://auth.gulimall.vip/login.html';
var AUTH_API = (window.location.protocol === 'https:' ? 'https://' : 'http://') + 'auth.gulimall.vip';
```

请求 agent 时携带 `credentials: 'include'`，浏览器才会发送 `AGENT_JWT_USER` Cookie。

刷新 JWT 的代码 `chat-widget.js:154-176`：

```javascript
async function refreshAgentJwt() {
    // 1. 请求 auth-server 刷新接口，携带 Spring Session Cookie
    var response = await fetch(AUTH_API + '/agent/jwt/refresh', {
        method: 'POST',
        credentials: 'include'
    });

    // 2. 解析统一响应
    var text = await response.text();
    var json = {};
    try {
        json = text ? JSON.parse(text) : {};
    } catch (e) {
        throw new Error('JWT 刷新返回格式异常');
    }

    // 3. 15001 表示旧登录态也已过期
    if (json.code === 15001) {
        state.loggedIn = false;
        showLoginMask(true);
    }

    // 4. 刷新失败时抛出错误，成功时浏览器已写入新的 Set-Cookie
    if (json.code !== 0) {
        var error = new Error(json.msg || '登录已过期，请重新登录');
        error.code = json.code;
        throw error;
    }
    return json;
}
```

普通请求遇到 `15012` 时刷新 JWT 并重试一次，`chat-widget.js:178-214`：

```javascript
async function request(path, options, retried) {
    options = options || {};
    var headers = Object.assign({}, options.headers || {});
    if (!(options.body instanceof FormData)) {
        headers['Content-Type'] = 'application/json';
    }

    // 1. 请求 agent，携带 HttpOnly Cookie
    var response = await fetch(API + path, Object.assign({}, options, {
        credentials: 'include',
        headers: headers
    }));

    // 2. 解析统一响应
    var text = await response.text();
    var json = {};
    try {
        json = text ? JSON.parse(text) : {};
    } catch (e) {
        throw new Error('服务返回格式异常');
    }

    // 3. 15012 表示 JWT 无效或过期，刷新后重试一次
    if (json.code === 15012 && !retried) {
        await refreshAgentJwt();
        return request(path, options, true);
    }

    // 4. 15001 表示没有 JWT，显示登录遮罩
    if (json.code === 15001) {
        state.loggedIn = false;
        showLoginMask(true);
        var authError = new Error(json.msg || '请先登录');
        authError.code = json.code;
        throw authError;
    }

    // 5. 其他业务错误直接抛出
    if (json.code !== 0) {
        var error = new Error(json.msg || ('请求失败：' + json.code));
        error.code = json.code;
        throw error;
    }
    return json;
}
```

流式接口的 JWT 过期处理 `chat-widget.js:1285-1334`：

```javascript
var contentType = response.headers.get('content-type') || '';
if (contentType.indexOf('text/event-stream') < 0) {
    // 1. 未登录时后端返回 JSON
    var text = await response.text();
    var json = {};
    try { json = JSON.parse(text); } catch (e) { /* ignore */ }

    // 2. JWT 过期，刷新后重新发起流式请求
    if (json.code === 15012 && !retried) {
        await refreshAgentJwt();
        return sendStream(body, true);
    }

    // 3. 没有 JWT，显示登录遮罩
    if (json.code === 15001) {
        state.loggedIn = false;
        showLoginMask(true);
        throw new Error(json.msg || '请先登录');
    }
    throw new Error(json.msg || '流式请求失败');
}

if (jwtExpired) {
    // 4. 删除未完成的回答
    var pos = state.messages.indexOf(placeholder);
    if (pos >= 0) {
        state.messages.splice(pos, 1);
        renderMessages();
    }

    // 5. 已经重试过仍然失败，说明旧登录态也过期了
    if (retried) {
        state.loggedIn = false;
        showLoginMask(true);
        throw new Error('登录已过期，请重新登录');
    }

    // 6. 刷新 JWT 后重新发起流式请求
    await refreshAgentJwt();
    return sendStream(body, true);
}
```

## 流程 6：请求经过 Nginx 和网关

请求链路：

1. 浏览器访问 `gulimall.vip/gulimall/agent/auth/me`。
2. Nginx 匹配 `location ^~ /gulimall/agent/`，转发到网关。
3. 网关匹配 `agent-route`，使用 `StripPrefix=1` 去掉 `/gulimall`。
4. `gulimall-agent` 收到 `/agent/auth/me`。

`ChatController` 的类级映射 `gulimall-agent/src/main/java/com/atguigu/gulimall/agent/chat/controller/ChatController.java:23-26`：

```java
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class ChatController {
```

## 流程 7：agent 解析 Cookie 并验签

### 7.1 按 Cookie 名称解析身份

`gulimall-agent/src/main/java/com/atguigu/gulimall/agent/auth/PrincipalResolver.java:34-52`：

```java
public Principal resolve(HttpServletRequest request) {

    // 1. 有 AGENT_JWT_ADMIN 就按管理端验签
    String adminToken = readCookie(request, properties.getAdminCookie());
    if (StringUtils.hasText(adminToken)) {
        return agentJwtService.verify(adminToken);
    }

    // 2. 没有管理端 Cookie，再看用户端 Cookie
    String userToken = readCookie(request, properties.getUserCookie());
    if (StringUtils.hasText(userToken)) {
        return agentJwtService.verify(userToken);
    }

    // 3. 两个 Cookie 都没有，返回 null，由上层转成 15001
    return null;
}
```

### 7.2 验签

`gulimall-agent/src/main/java/com/atguigu/gulimall/agent/auth/jwt/AgentJwtService.java:69-119`：

```java
public Principal verify(String token) {
    try {
        // 1. 读取 kid，按 kid 选择公钥
        String kid = readKeyId(token);
        KeyEntry entry = keys.get(kid);
        if (entry == null) {
            throw new AgentException(AgentErrorCode.AGENT_TOKEN_INVALID, "登录凭证密钥不匹配");
        }

        // 2. 使用公钥验签，同时校验 exp 等标准声明
        Claims claims = Jwts.parser()
                .verifyWith(entry.key())
                .clockSkewSeconds(properties.getClockSkewSeconds())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        // 3. 校验签发方
        if (!entry.issuer().equals(claims.getIssuer())) {
            throw new AgentException(AgentErrorCode.AGENT_TOKEN_INVALID, "登录凭证签发方不匹配");
        }

        // 4. 校验接收方
        if (claims.getAudience() == null
                || !claims.getAudience().contains(properties.getAudience())) {
            throw new AgentException(AgentErrorCode.AGENT_TOKEN_INVALID, "登录凭证接收方不匹配");
        }

        // 5. 校验角色与签发密钥一致
        String role = claims.get("role", String.class);
        if (!entry.role().equals(role)) {
            throw new AgentException(AgentErrorCode.AGENT_TOKEN_INVALID, "登录凭证角色不匹配");
        }

        // 6. 解析用户 id 和用户名
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new AgentException(AgentErrorCode.AGENT_TOKEN_INVALID, "登录凭证缺少用户 id");
        }
        long id = Long.parseLong(subject);
        String username = claims.get("username", String.class);
        if (username == null || username.isBlank()) {
            username = subject;
        }

        // 7. role=admin 映射 ADMIN，其余映射 USER
        PrincipalType type = ROLE_ADMIN.equals(role) ? PrincipalType.ADMIN : PrincipalType.USER;
        return new Principal(type, id, username, username);

    } catch (AgentException ex) {
        throw ex;
    } catch (Exception ex) {
        log.warn("agent JWT 校验失败：{}", ex.getMessage());
        throw new AgentException(AgentErrorCode.AGENT_TOKEN_INVALID,
                "登录凭证无效或已过期，请重新登录", ex);
    }
}
```

读取 `kid`，`AgentJwtService.java:127-145`：

```java
private String readKeyId(String token) {

    // 1. JWT 必须正好三段
    String[] parts = token == null ? new String[0] : token.split("\\.");
    if (parts.length != 3) {
        throw new AgentException(AgentErrorCode.AGENT_TOKEN_INVALID, "登录凭证格式不正确");
    }

    try {
        // 2. 解码 Header
        byte[] header = Base64.getUrlDecoder().decode(parts[0]);
        JsonNode node = objectMapper.readTree(header);

        // 3. 读取 kid
        String kid = node.path("kid").asText("");
        if (kid.isBlank()) {
            throw new AgentException(AgentErrorCode.AGENT_TOKEN_INVALID, "登录凭证缺少 kid");
        }
        return kid;
    } catch (AgentException ex) {
        throw ex;
    } catch (Exception ex) {
        throw new AgentException(AgentErrorCode.AGENT_TOKEN_INVALID, "登录凭证 header 解析失败", ex);
    }
}
```

启动时加载公钥，`AgentJwtService.java:49-61`：

```java
@PostConstruct
public void loadKeys() {

    // 1. 用户端公钥只接受 gulimall-auth-server 签发、role=user 的 JWT
    keys.put(properties.getUserKeyId(), new KeyEntry(
            loadPublicKey(properties.getUserPublicKeyPath()),
            properties.getIssuerUser(),
            ROLE_USER));

    // 2. 管理端公钥只接受 renren-fast 签发、role=admin 的 JWT
    keys.put(properties.getAdminKeyId(), new KeyEntry(
            loadPublicKey(properties.getAdminPublicKeyPath()),
            properties.getIssuerAdmin(),
            ROLE_ADMIN));

    log.info("agent JWT 公钥加载完成 userKid={} adminKid={}",
            properties.getUserKeyId(), properties.getAdminKeyId());
}
```

公钥加载 `AgentJwtService.java:153-165`：

```java
private PublicKey loadPublicKey(String path) {
    try {
        // 1. 读取 PEM，去掉 BEGIN/END 行和空白
        String pem = Files.readString(Path.of(path), StandardCharsets.UTF_8)
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");

        // 2. Base64 解码后按 X.509 格式生成 RSA 公钥
        byte[] encoded = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(encoded));
    } catch (Exception ex) {
        throw new IllegalStateException("加载 agent JWT 公钥失败：" + path, ex);
    }
}
```

配置 `config/gulimall-agent.yaml:68-79`：

```yaml
gulimall:
  agent:
    jwt:
      issuer-user: gulimall-auth-server
      issuer-admin: renren-fast
      audience: gulimall-agent
      user-cookie: AGENT_JWT_USER
      admin-cookie: AGENT_JWT_ADMIN
      user-public-key-path: ${AGENT_JWT_USER_PUBLIC_KEY_PATH}
      admin-public-key-path: ${AGENT_JWT_ADMIN_PUBLIC_KEY_PATH}
      user-key-id: user-2026-01
      admin-key-id: admin-2026-01
      clock-skew-seconds: 30
```

## 流程 8：把身份注入 Controller

`Principal` 表示当前 AI 请求身份，`gulimall-agent/src/main/java/com/atguigu/gulimall/agent/auth/Principal.java`：

```java
public record Principal(PrincipalType type, Long id, String username, String displayName) {

    public String subject() {
        return type.name() + ":" + id;
    }

    public boolean isAdmin() {
        return type == PrincipalType.ADMIN;
    }
}
```

参数解析器 `gulimall-agent/src/main/java/com/atguigu/gulimall/agent/auth/PrincipalArgumentResolver.java:46-60`：

```java
@Override
public Object resolveArgument(@NonNull MethodParameter parameter,
                              ModelAndViewContainer mavContainer,
                              NativeWebRequest webRequest,
                              WebDataBinderFactory binderFactory) {

    // 1. 从当前请求解析身份
    HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
    Principal principal = request == null ? null : principalResolver.resolve(request);

    // 2. 没有身份时直接拒绝
    if (principal == null) {
        throw new AgentException(AgentErrorCode.NOT_LOGIN, "请先登录后再使用 AI 助手");
    }
    return principal;
}
```

解析器注册 `gulimall-agent/src/main/java/com/atguigu/gulimall/agent/config/WebMvcConfig.java:29-31`：

```java
@Override
public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(principalArgumentResolver);
}
```

Controller 使用 `Principal` 参数，`ChatController.java:90-98`：

```java
@GetMapping("/auth/me")
public R me(Principal principal) {
    // 1. 身份由 PrincipalArgumentResolver 从 agent JWT 解析得到
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("type", principal.type().name());
    data.put("id", principal.id());
    data.put("username", principal.username());
    data.put("displayName", principal.displayName());

    // 2. 返回当前身份类型和展示信息
    return R.ok().put("data", data);
}
```

流式接口手动解析身份，`ChatController.java:55-67`：

```java
@PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(HttpServletRequest httpRequest, @RequestBody ChatRequest request) {

    Principal principal;
    try {
        // 1. 手动解析，AgentException 转换为 SSE error 事件
        principal = principalResolver.resolve(httpRequest);
    } catch (AgentException ex) {
        return errorEmitter(ex.getCode(), ex.getMessage());
    }

    // 2. 没有 Cookie 时返回 15001
    if (principal == null) {
        return errorEmitter(AgentErrorCode.NOT_LOGIN, "请先登录后再使用 AI 助手");
    }

    // 3. 进入流式对话
    return chatOrchestrator.stream(principal, request);
}
```

## 流程 9：退出登录并清除 Cookie

`LoginController.java:126-131`：

```java
@GetMapping("/logout.html")
public String logout(HttpSession session, HttpServletResponse response) {

    // 1. 作废 Spring Session
    session.invalidate();

    // 2. 让浏览器删除 agent JWT Cookie
    agentJwtService.clear(response);

    // 3. 返回登录页
    return "redirect:" + authUrl + "/login.html";
}
```

`AgentJwtService.clear()`，`gulimall-auth-server/.../AgentJwtService.java:113-123`：

```java
public void clear(HttpServletResponse response) {
    ResponseCookie cookie = ResponseCookie.from(properties.getCookieName(), "")
            .domain(properties.getCookieDomain())
            .path(properties.getCookiePath())
            .httpOnly(true)
            .secure(properties.isCookieSecure())
            .sameSite("Lax")
            .maxAge(0)
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
}
```

退出登录只清除浏览器 Cookie。已经复制走的 JWT 在 30 分钟 `exp` 之前仍然有效。

---

# 四、管理端 JWT 流程

## 流程 1：管理员登录

`renren-fast/src/main/java/io/renren/modules/sys/controller/SysLoginController.java:72-98`：

```java
@PostMapping("/sys/login")
public Map<String, Object> login(@RequestBody SysLoginForm form,
                                 HttpServletResponse response) throws IOException {

    // 1. 校验验证码
    boolean captcha = sysCaptchaService.validate(form.getUuid(), form.getCaptcha());
    if (!captcha) {
        return R.error("验证码不正确");
    }

    // 2. 查询管理员并校验密码
    SysUserEntity user = sysUserService.queryByUserName(form.getUsername());
    if (user == null || !user.getPassword().equals(
            new Sha256Hash(form.getPassword(), user.getSalt()).toHex())) {
        return R.error("账号或密码不正确");
    }

    // 3. 校验账号锁定状态
    if (user.getStatus() == 0) {
        return R.error("账号已被锁定,请联系管理员");
    }

    // 4. 生成常规登录 token，保存到 sys_user_token
    R r = sysUserTokenService.createToken(user.getUserId());

    // 5. 签发管理端 agent JWT，写入 admin.gulimall.vip 的 HttpOnly Cookie
    agentJwtService.issue(user, response);

    return r;
}
```

## 流程 2：生成管理端常规 token

renren-fast 中的 `JWTFilter`、`JWTToken`、`JWTRealm` 并不处理 JWT，实际使用的是数据库随机 token：

1. 登录时生成随机字符串。
2. 字符串存入 `sys_user_token` 表。
3. 前端把字符串放入请求头 `token`。
4. 后端按字符串查表，比对 token 和过期时间。

`renren-fast/src/main/java/io/renren/modules/sys/service/impl/SysUserTokenServiceImpl.java:29-61`：

```java
@Override
public R createToken(long userId) {

    // 1. 生成随机 token
    String token = TokenGenerator.generateValue();

    // 2. 计算 12 小时后的过期时间
    Date now = new Date();
    Date expireTime = new Date(now.getTime() + EXPIRE * 1000);

    // 3. 一个用户只保留一条记录
    SysUserTokenEntity tokenEntity = this.getById(userId);
    if (tokenEntity == null) {
        tokenEntity = new SysUserTokenEntity();
        tokenEntity.setUserId(userId);
        tokenEntity.setToken(token);
        tokenEntity.setUpdateTime(now);
        tokenEntity.setExpireTime(expireTime);
        this.save(tokenEntity);
    } else {
        tokenEntity.setToken(token);
        tokenEntity.setUpdateTime(now);
        tokenEntity.setExpireTime(expireTime);
        this.updateById(tokenEntity);
    }

    // 4. 返回给前端
    return R.ok().put("token", token).put("expire", EXPIRE);
}
```

`TokenGenerator` 的核心逻辑 `renren-fast/src/main/java/io/renren/modules/sys/jwt/TokenGenerator.java:41-51`：

```java
public static String generateValue(String param) {
    try {
        // 1. 使用 MD5 把 UUID 转成 32 位十六进制字符串
        MessageDigest algorithm = MessageDigest.getInstance("MD5");
        algorithm.reset();
        algorithm.update(param.getBytes());
        byte[] messageDigest = algorithm.digest();

        // 2. 转成十六进制文本
        return toHexString(messageDigest);
    } catch (Exception e) {
        throw new RRException("生成Token失败", e);
    }
}
```

## 流程 3：校验管理端常规 token

`renren-fast/src/main/java/io/renren/modules/sys/jwt/JWTFilter.java:97-107`：

```java
private String getRequestToken(HttpServletRequest httpRequest) {

    // 1. 优先从请求头 token 读取
    String token = httpRequest.getHeader("token");

    // 2. 请求头没有时从请求参数读取
    if (StringUtils.isBlank(token)) {
        token = httpRequest.getParameter("token");
    }
    return token;
}
```

`renren-fast/src/main/java/io/renren/modules/sys/jwt/JWTRealm.java:59-77`：

```java
@Override
protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token)
        throws AuthenticationException {

    // 1. 取出请求中的 token 字符串
    String accessToken = (String) token.getPrincipal();

    // 2. 按 token 查询 sys_user_token
    SysUserTokenEntity tokenEntity = shiroService.queryByToken(accessToken);

    // 3. token 不存在或已过期时认证失败
    if (tokenEntity == null
            || tokenEntity.getExpireTime().getTime() < System.currentTimeMillis()) {
        throw new IncorrectCredentialsException("token失效，请重新登录");
    }

    // 4. 查询用户并校验锁定状态
    SysUserEntity user = shiroService.queryUser(tokenEntity.getUserId());
    if (user.getStatus() == 0) {
        throw new LockedAccountException("账号已被锁定,请联系管理员");
    }

    return new SimpleAuthenticationInfo(user, accessToken, getName());
}
```

Shiro 的过滤规则 `renren-fast/src/main/java/io/renren/config/ShiroConfig.java:52-64`：

```java
Map<String, String> filterMap = new LinkedHashMap<>();
filterMap.put("/webjars/**", "anon");
filterMap.put("/druid/**", "anon");
filterMap.put("/app/**", "anon");
filterMap.put("/sys/login", "anon");
filterMap.put("/swagger/**", "anon");
filterMap.put("/v3/api-docs/**", "anon");
filterMap.put("/swagger-ui.html", "anon");
filterMap.put("/swagger-ui/**", "anon");
filterMap.put("/captcha.jpg", "anon");
filterMap.put("/aaa.txt", "anon");
filterMap.put("/**", "jwt");
shiroFilter.setFilterChainDefinitionMap(filterMap);
```

## 流程 4：使用管理端私钥签发 JWT

`renren-fast/src/main/java/io/renren/modules/sys/agent/AgentJwtService.java:65-91`：

```java
public long issue(SysUserEntity user, HttpServletResponse response) {

    // 1. 计算签发时间和过期时间
    Instant now = Instant.now();
    Instant expiresAt = now.plus(properties.getTtl());

    // 2. 组装 Header 和 Payload，使用管理端私钥做 RS256 签名
    String token = Jwts.builder()
            .header().keyId(properties.getKeyId()).and()
            .issuer(properties.getIssuer())
            .audience().add(properties.getAudience()).and()
            .subject(String.valueOf(user.getUserId()))
            .claim("username", user.getUsername())
            .claim("role", "admin")
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .id(UUID.randomUUID().toString())
            .signWith(privateKey, Jwts.SIG.RS256)
            .compact();

    // 3. 写入 admin.gulimall.vip 的 HttpOnly Cookie
    ResponseCookie cookie = ResponseCookie.from(properties.getCookieName(), token)
            .domain(properties.getCookieDomain())
            .path(properties.getCookiePath())
            .httpOnly(true)
            .secure(properties.isCookieSecure())
            .sameSite("Lax")
            .maxAge(properties.getTtl())
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

    // 4. 返回过期时间
    return expiresAt.toEpochMilli();
}
```

管理端配置 `renren-fast/src/main/resources/application.yml:75-86`：

```yaml
gulimall:
  agent:
    jwt:
      issuer: renren-fast
      audience: gulimall-agent
      cookie-name: AGENT_JWT_ADMIN
      cookie-domain: admin.gulimall.vip
      cookie-path: /gulimall/agent/
      cookie-secure: ${AGENT_JWT_COOKIE_SECURE:false}
      ttl: 30m
      key-id: admin-2026-01
      private-key-path: ${AGENT_JWT_ADMIN_PRIVATE_KEY_PATH:../jwt/admin/jwt_private.pem}
```

## 流程 5：前端保存并携带常规 token

`renren-fast-vue/src/views/common/login.vue:73-86`：

```javascript
this.$http({
  url: this.$http.adornUrl('/sys/login'),
  method: 'post',
  data: this.$http.adornData({
    'username': this.dataForm.userName,
    'password': this.dataForm.password,
    'uuid': this.dataForm.uuid,
    'captcha': this.dataForm.captcha
  })
}).then(({ data }) => {
  if (data && data.code === 0) {
    // 1. 保存常规登录 token
    this.$cookie.set('token', data.token)

    // 2. 进入管理首页
    this.$router.replace({ name: 'home' })
  } else {
    this.getCaptcha()
    this.$message.error(data.msg)
  }
})
```

axios 请求拦截器 `renren-fast-vue/src/utils/httpRequest.js:8-24`：

```javascript
const http = axios.create({
  timeout: 1000 * 30,
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json; charset=utf-8'
  }
})

http.interceptors.request.use(config => {
  // 1. 每个请求携带常规登录 token
  config.headers['token'] = Vue.cookie.get('token')
  return config
}, error => {
  return Promise.reject(error)
})
```

## 流程 6：用常规 token 刷新 agent JWT

`renren-fast/src/main/java/io/renren/modules/sys/agent/AgentJwtRefreshController.java:33-41`：

```java
@PostMapping("/refresh")
public R refresh(HttpServletResponse response) {

    // 1. 从 Shiro 当前登录用户中取管理员
    SysUserEntity user = getUser();
    if (user == null || user.getUserId() == null) {
        return R.error(401, "登录已过期，请重新登录");
    }

    // 2. 重新签发 30 分钟 agent JWT
    long expiresAt = agentJwtService.issue(user, response);

    // 3. 返回新的过期时间
    return R.ok().put("data", Map.of("expiresAt", expiresAt));
}
```

刷新请求路径：

```text
admin.gulimall.vip/gulimall/sys/agent/jwt/refresh
  → Nginx location /gulimall/
  → 网关 admin_route
       Path=/gulimall/**
       RewritePath=/gulimall/(?<segment>.*),/renren-fast/$\{segment}
  → renren-fast 收到 /renren-fast/sys/agent/jwt/refresh
  → 去掉 context-path /renren-fast
  → Controller 命中 /sys/agent/jwt/refresh
```

## 流程 7：管理端 AI 页面刷新 JWT

`renren-fast-vue/src/views/modules/ai/chat.vue:337-348`：

```javascript
async refreshAgentJwt () {
  // 1. 用常规登录 token 换取新的 agent JWT
  const { data } = await this.$http({
    url: this.$http.adornUrl('/sys/agent/jwt/refresh'),
    method: 'post'
  })

  // 2. 常规登录 token 也过期时，刷新失败
  if (data.code !== 0) {
    const error = new Error(data.msg || '登录已过期，请重新登录')
    error.code = data.code
    throw error
  }
}
```

普通接口遇到 `15012` 时刷新并重试一次，`chat.vue:349-365`：

```javascript
async get (path, params, retried) {
  const { data } = await this.$http({
    url: this.$http.adornUrl(path),
    method: 'get',
    params: params || {}
  })

  // 1. agent JWT 过期时刷新并重试一次
  if (data.code === 15012 && !retried) {
    await this.refreshAgentJwt()
    return this.get(path, params, true)
  }

  // 2. 其他错误直接抛出
  if (data.code !== 0) {
    const error = new Error(data.msg || '请求失败')
    error.code = data.code
    throw error
  }
  return data
}
```

`post`、`put`、`del` 使用相同处理方式，见 `chat.vue:366-415`。

流式请求 `renren-fast-vue/src/utils/aiSse.js:7-18`：

```javascript
export default function streamSse (url, body, token, onEvent, signal) {
  return fetch(url, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream',
      'token': token || ''
    },
    body: JSON.stringify(body),
    signal: signal
  })
```

`token` 请求头用于 renren-fast 的常规 token 校验。`gulimall-agent` 只读取 Cookie 中的 agent JWT。

流式接口的 `15012` 处理 `chat.vue:633-666`：

```javascript
} else if (event === 'error') {
  // 1. agent JWT 过期或无效
  if (payload.code === 15012) {
    placeholder.streaming = false
    jwtExpired = true
    return
  }

  // 2. 其他模型调用错误
  placeholder.streaming = false
  placeholder.content = payload.message || 'AI 调用失败'
  this.$message.error(placeholder.content)
}

if (jwtExpired) {
  // 3. 删除未完成的回答
  const pos = this.messages.indexOf(placeholder)
  if (pos >= 0) {
    this.messages.splice(pos, 1)
  }

  // 4. 已经重试过仍然失败，常规登录 token 也已过期
  if (retried) {
    this.$message.error('登录已过期，请重新登录')
    const error = new Error('登录已过期，请重新登录')
    error.code = 15012
    throw error
  }

  // 5. 刷新 JWT 后重新发起流式请求
  await this.refreshAgentJwt()
  return this.sendStream(body, true)
}
```

## 流程 8：管理端请求进入 agent 并验签

管理端页面域名为 `admin.gulimall.vip`，Nginx 同样引入 `ai-widget.inc`：

1. `/gulimall/agent/**` 命中 `location ^~ /gulimall/agent/`。
2. 网关 `agent-route` 的 order 为 7，小于 `admin_route` 的 99。
3. 请求转发到 `gulimall-agent`，路径去掉 `/gulimall`。

agent 侧 `PrincipalResolver.java:34-52`：

```java
public Principal resolve(HttpServletRequest request) {

    // 1. 有 AGENT_JWT_ADMIN 就按管理端验签
    String adminToken = readCookie(request, properties.getAdminCookie());
    if (StringUtils.hasText(adminToken)) {
        return agentJwtService.verify(adminToken);
    }

    // 2. 没有管理端 Cookie，再看用户端 Cookie
    String userToken = readCookie(request, properties.getUserCookie());
    if (StringUtils.hasText(userToken)) {
        return agentJwtService.verify(userToken);
    }

    // 3. 两个 Cookie 都没有，返回 null，由上层转成 15001
    return null;
}
```

管理端公钥与角色绑定 `AgentJwtService.java:49-61`：

```java
@PostConstruct
public void loadKeys() {

    // 1. 用户端公钥只接受 gulimall-auth-server 签发、role=user 的 JWT
    keys.put(properties.getUserKeyId(), new KeyEntry(
            loadPublicKey(properties.getUserPublicKeyPath()),
            properties.getIssuerUser(),
            ROLE_USER));

    // 2. 管理端公钥只接受 renren-fast 签发、role=admin 的 JWT
    keys.put(properties.getAdminKeyId(), new KeyEntry(
            loadPublicKey(properties.getAdminPublicKeyPath()),
            properties.getIssuerAdmin(),
            ROLE_ADMIN));
}
```

用户端 Cookie 的域为 `.gulimall.vip`，浏览器访问 `admin.gulimall.vip` 时也会携带该 Cookie。`PrincipalResolver` 先读 `AGENT_JWT_ADMIN`，存在就按管理端验签；不存在才读 `AGENT_JWT_USER`，所以管理端身份优先。管理端 Cookie 的域为 `admin.gulimall.vip`，不会发送到用户端域名。

完整验签逻辑见第三章流程 7.2。

## 流程 9：管理端退出登录

`SysLoginController.java:107-112`：

```java
@PostMapping("/sys/logout")
public R logout(HttpServletResponse response) {

    // 1. 更新 sys_user_token，使常规 token 立即失效
    sysUserTokenService.logout(getUserId());

    // 2. 让浏览器删除 agent JWT Cookie
    agentJwtService.clear(response);

    return R.ok();
}
```

管理端前端退出 `renren-fast-vue/src/views/main-navbar.vue:92-107`：

```javascript
logoutHandle() {
  this.$confirm(`确定进行[退出]操作?`, '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(() => {
    this.$http({
      url: this.$http.adornUrl('/sys/logout'),
      method: 'post',
      data: this.$http.adornData()
    }).then(({ data }) => {
      if (data && data.code === 0) {
        clearLoginInfo()
        this.$router.push({ name: 'login' })
      }
    })
  })
}
```

---

# 五、两端对照与排错

## 5.1 用户端与管理端对照

| 项目 | 用户端 | 管理端 |
| --- | --- | --- |
| 登录入口 | `auth.gulimall.vip/login` | `admin.gulimall.vip` → `/sys/login` |
| 常规登录态 | Spring Session，Redis | `sys_user_token` 表 |
| 常规凭证名 | Cookie `GULIMALLSESSION` | 请求头 `token` |
| 常规凭证有效期 | 7 天 | 12 小时 |
| 签发服务 | `gulimall-auth-server` | `renren-fast` |
| 签发类 | `com.atguigu.gulimall.auth_server.agent.AgentJwtService` | `io.renren.modules.sys.agent.AgentJwtService` |
| 私钥 | `jwt/user/jwt_private.pem` | `jwt/admin/jwt_private.pem` |
| `iss` | `gulimall-auth-server` | `renren-fast` |
| `role` | `user` | `admin` |
| `kid` | `user-2026-01` | `admin-2026-01` |
| Cookie 名 | `AGENT_JWT_USER` | `AGENT_JWT_ADMIN` |
| Cookie 域 | `.gulimall.vip` | `admin.gulimall.vip` |
| Cookie 路径 | `/gulimall/agent/` | `/gulimall/agent/` |
| agent JWT 有效期 | 30 分钟 | 30 分钟 |
| 刷新接口 | `POST auth.gulimall.vip/agent/jwt/refresh` | `POST admin.gulimall.vip/gulimall/sys/agent/jwt/refresh` |
| 刷新依据 | Spring Session | renren-fast 常规 token |
| 前端携带方式 | `fetch(..., credentials: 'include')` | axios `withCredentials: true` / `credentials: 'include'` |
| 角色映射 | `PrincipalType.USER` | `PrincipalType.ADMIN` |

## 5.2 错误码

| 错误码 | 含义 | 用户端处理 | 管理端处理 |
| --- | --- | --- | --- |
| `15001` | 没有 agent JWT Cookie | 显示登录遮罩 | 跳管理端登录页 |
| `15012` | JWT 无效、过期、签发方/接收方/角色/kid 不匹配 | 刷新 JWT 后重试一次 | 刷新 JWT 后重试一次 |
| `401` | 管理端常规 token 失效 | 不适用 | 清除登录信息并跳登录页 |

错误码定义位于 `gulimall-agent/src/main/java/com/atguigu/gulimall/agent/exception/AgentErrorCode.java:14-53`。

## 5.3 Cookie 未生效

检查顺序：

1. 登录接口响应是否包含 `Set-Cookie`。
2. `cookie-secure` 是否为 `true`，而当前页面使用 HTTP。Secure Cookie 在 HTTP 下会被浏览器丢弃。
3. 访问域名是否匹配 Cookie 域：
   - 用户端必须是 `gulimall.vip` 或其子域；
   - 管理端必须是 `admin.gulimall.vip`。
4. 请求路径是否匹配 `/gulimall/agent/`。
5. 跨域请求是否携带 `credentials: 'include'`，服务端是否允许 `allowCredentials(true)`。
6. 浏览器是否拦截了第三方 Cookie。

## 5.4 持续返回 15012

检查顺序：

1. JWT 是否超过 30 分钟有效期。
2. Header 中的 `kid` 是否与 agent 配置的 `user-key-id` 或 `admin-key-id` 一致。
3. 签发方与公钥是否配对：
   - `gulimall-auth-server` 签发的 JWT 使用 `jwt/user/jwt_public.pem`；
   - `renren-fast` 签发的 JWT 使用 `jwt/admin/jwt_public.pem`。
4. `iss` 是否与 `issuer-user` 或 `issuer-admin` 一致。
5. `role` 是否与密钥匹配。管理端私钥签发的 JWT 必须是 `role=admin`，用户端私钥签发的 JWT 必须是 `role=user`。
6. `aud` 是否包含 `gulimall-agent`。
7. 服务端时间是否偏差过大。默认允许 30 秒时钟偏差。
8. 私钥是否为 PKCS#8 格式，公钥是否为 X.509 格式。

## 5.5 刷新成功但 agent 仍返回 15012

检查顺序：

1. 刷新接口响应是否包含新的 `AGENT_JWT_*` Cookie。
2. Cookie 的 `Domain`、`Path` 是否与 agent 请求匹配。
3. 浏览器是否因为 `Secure`、`SameSite` 或第三方 Cookie 策略丢弃 Cookie。
4. 前端是否在刷新成功后重试原请求。

## 5.6 管理端返回 401

401 来自 renren-fast 的常规 token 校验，与 agent JWT 无关：

1. 请求头是否携带 `token`。
2. `sys_user_token` 表中是否存在对应用户记录。
3. `expire_time` 是否已经过期。
4. 是否调用过 `/sys/logout`，退出会把数据库中的 token 更新为新值。

## 5.7 安全要求

1. 私钥只保存在签发服务，agent 只保存公钥。
2. 私钥不提交 Git，生产环境通过挂载目录或密钥管理系统注入，文件权限 `600`。
3. 生产环境使用 HTTPS，并保持 `cookie-secure: true`。
4. agent JWT 保持短有效期，依靠常规登录态刷新。
5. Payload 不存放敏感信息。
6. 退出登录只清除 Cookie，没有服务端撤销列表。被复制的 JWT 在过期前仍然有效。
7. agent 当前只加载配置中的两个 `kid`。新增密钥需要修改配置和代码，并重启 agent。
