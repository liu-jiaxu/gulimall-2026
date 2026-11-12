# Spring Session + Redis 接入指南

> 目标：让**多个模块、多个实例**共享同一份登录态 —— 代码里照常写 `session.setAttribute(...)`，
> 值却自动落到 Redis，谁都能读到。

本文以本项目的 `gulimall-auth-server`（写会话）和 `gulimall-product`（读会话）为实例。

---

## 0. 为什么要它

不用 Spring Session 时，`HttpSession` 是**容器本地内存**里的对象，会撞上三堵墙：

| 问题 | 现象 |
|---|---|
| **多实例不共享** | 请求落到实例 A 写了会话，下次请求落到实例 B → 读不到，等于没登录 |
| **跨子域不共享** | 在 `auth.gulimall.vip` 登录，回到 `gulimall.vip` 首页 → 仍然显示未登录 |
| **重启即丢失** | 服务重启，所有在线用户被踢下线 |

Spring Session 做的事：注册一个 `SessionRepositoryFilter`，把容器原生的 `HttpSession`
**整个替换**成「读写 Redis 的实现」。从此会话存在 Redis 里，上面三个问题一起解决。

> 关键点：**业务代码一行都不用改**，还是标准的 `jakarta.servlet.http.HttpSession`。

---

## 1. 引入依赖（`pom.xml`）

```xml
<!-- ★ 主角：Spring Session 的 Redis 实现。
     它会传递引入 spring-session-core，不用单独加。
     版本不用写 —— Spring Boot 的 BOM 统一管理 -->
<dependency>
    <groupId>org.springframework.session</groupId>
    <artifactId>spring-session-data-redis</artifactId>
</dependency>

<!-- Redis 客户端 + 自动配置。
     ⚠️ 只负责"用什么连 Redis"，不管"会话怎么存"。
     项目里已经在用 Redis 的（StringRedisTemplate 等），这一项【已经有了】，不用重复加 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

---

## 2. 配置 Redis 连接（已有则跳过）

`application.yaml`：

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}
      password: ${REDIS_PASSWORD}
```

---

## 3. 开启会话存储

```yaml
spring:
  session:

    # 会话空闲多久算过期。不写默认就是 30 分钟
    timeout: 30m

    redis:
      # Redis 里 key 的前缀，最终形态：
      #   gulimall:session:sessions:<sessionId>          ← 会话内容
      #   gulimall:session:sessions:expires:<sessionId>  ← 过期标记
      #   gulimall:session:expirations:<时间戳>           ← 到期索引
      #
      # ⚠️ 多个模块要共享同一份会话时，这个值【必须完全一致】
      namespace: ${SESSION_REDIS_NAMESPACE}
```

---

## 4. 配置会话 Cookie

```yaml
server:
  servlet:
    session:
      cookie:
        # ⚠️ 多模块共享时【必须一致】，否则各是各的会话
        name: ${SESSION_COOKIE_NAME}

        # ⚠️ 不能带前导点！
        #    Spring Session 的 DefaultCookieSerializer 会直接抛
        #       IllegalArgumentException: Invalid cookie domain: .gulimall.vip
        #    单写 gulimall.vip 就已经对【所有子域】生效（RFC 6265），不需要点
        domain: ${SESSION_COOKIE_DOMAIN}

        # 禁止 JS 通过 document.cookie 读取 —— 防 XSS 偷会话
        http-only: true

        # 生产走 HTTPS 时打开：只允许通过 HTTPS 传输
        # secure: true

        # 防 CSRF 的 cookie 属性：同站请求才带上
        # same-site: lax
```

---

## 5. Java 配置类（序列化器）

```java
package com.atguigu.gulimall.<模块>.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

@Configuration
public class SessionConfig {

    /**
     * ⚠️ Bean 名【必须】是 springSessionDefaultRedisSerializer
     *    —— Spring Session 是按这个名字去容器里找的，
     *       改成别的名字完全不生效，而且【不会报错】，很难排查
     */
    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {

        // 为什么不用默认的 JDK 序列化？
        //   1. 存进 Redis 是一坨二进制，redis-cli 里完全看不懂
        //   2. 被存的类一旦加/删字段，旧数据反序列化就可能失败
        // 换成 JSON：明文可读，排查登录态问题方便得多
        return new GenericJackson2JsonRedisSerializer();
    }
}
```

> **⚠️ 最容易踩的坑：多个模块的序列化器必须一致。**
> auth-server 用 JSON 写进去、product 却用 JDK 去读 → 直接反序列化异常，首页 500。

---

## 6. 使用（业务代码不用改）

```java
@GetMapping("/xxx")
public String xxx(HttpSession session) {

    // 写：这一行就会落到 Redis
    session.setAttribute("loginUser", member);

    // 读
    Object loginUser = session.getAttribute("loginUser");

    // 退出：删除 Redis 里那条会话记录，并下发过期 cookie
    session.invalidate();

    return "index";
}
```

用的是标准 `jakarta.servlet.http.HttpSession`，代码里**看不到任何 Redis 痕迹**。

### 存进 session 的对象有讲究

| 序列化器 | 对对象的要求 |
|---|---|
| JDK（默认） | **必须 `implements Serializable`** |
| JSON（推荐，见第 5 节） | 不强制，但建议存 **DTO 而不是 Entity** —— 别把 password 这类字段带进去 |

本项目用的是 `MemberResponseVo`（在 `gulimall-api` 模块，各服务都能访问），
而不是 `MemberEntity`（在 member 模块里，其它模块根本拿不到）。

---

## 7. 验证

```bash
# 1. 看 key 有没有生成（登录后应该出现）
redis-cli -h <your-vm-host-ip> -a <密码> keys 'gulimall:session*'

# 2. 看内容（JSON 序列化的话是明文可读的）
redis-cli -h <your-vm-host-ip> -a <密码> hgetall 'gulimall:session:sessions:<sessionId>'

# 3. 浏览器里看 cookie：应该有 GULIMALLSESSION，Domain=gulimall.vip
```

登录后 key 出现、退出后 key 消失，就说明通了。

---

## 8. 常见坑速查

| 现象 | 原因 |
|---|---|
| 启动报 `Invalid cookie domain: .gulimall.vip` | cookie domain **带了前导点**（去掉点即可） |
| 在 A 模块登录了，B 模块还是未登录 | `namespace` / cookie `name` / cookie `domain` **三者有一个不一致** |
| 读会话时抛反序列化异常、页面 500 | 两个模块的**序列化器不一致**（一个 JSON 一个 JDK） |
| 配了 `SessionConfig` 但没生效 | Bean 名不是 `springSessionDefaultRedisSerializer` |
| 网关（gateway）也加了 session | **别加** —— WebFlux 应用有自己的 `WebSession`，Servlet 那套不适用 |
| 生产环境 cookie 被浏览器丢弃 | 走 HTTPS 时没开 `secure: true`，或跨站场景没配 `same-site` |

---

## 9. 本项目现状

### 统一配置源（`config/common.yaml`）

三个值**集中定义**，避免各模块各写各的、改一处漏一处：

```yaml
# ⚠️ 三个值必须【所有需要共享登录态的模块完全一致】
SESSION_REDIS_NAMESPACE: gulimall:session
SESSION_COOKIE_DOMAIN:  gulimall.vip
SESSION_COOKIE_NAME:    GULIMALLSESSION
```

### 已接入的模块

| 模块 | 角色 | 关键位置 |
|---|---|---|
| `gulimall-auth-server` | **写**会话 | `OAuth2Controller.success()`（GitHub 登录）<br>`LoginController.login()`（账号密码登录）<br>`LoginController.logout()`（退出） |
| `gulimall-product` | **读**会话 | `IndexController.indexPage()` 读 `LOGIN_USER` → 传给 `index.html` |

### 会话里的 key

```java
AuthServerConstant.LOGIN_USER   // = "loginUser"
```

定义在 `gulimall-common`，读写双方引用同一个常量，避免拼错字符串。

---

## 10. 给新模块接入的检查清单

比如以后 `gulimall-order` 也要读登录态：

- [ ] **1** `pom.xml` 加 `spring-session-data-redis`（`spring-boot-starter-data-redis` 应该已有）
- [ ] **2** `application.yaml` 加 `spring.session.*`，namespace 引用 `${SESSION_REDIS_NAMESPACE}`
- [ ] **3** `application.yaml` 加 `server.servlet.session.cookie.*`，domain / name 引用 `${SESSION_*}`
- [ ] **4** 复制一份 `SessionConfig`（⚠️ 序列化器必须和其它模块一致）
- [ ] **5** Controller 参数里加 `HttpSession session`，读 `AuthServerConstant.LOGIN_USER`
- [ ] **6** 三项一致性自检：`namespace` ✅ `cookie.name` ✅ `cookie.domain` ✅
