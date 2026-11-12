# renren-fast 版本升级说明

> 升级时间：2026-08-28
> 升级目标：从 **Spring Boot 2.6.6 + Java 8** 升级到 **Spring Boot 3.5.16 + Java 21**，各依赖版本与 gulimall 主项目技术栈对齐。
> 验证结果：编译 ✅ / 启动 ✅（Tomcat 8080，context-path `/renren-fast`）/ springdoc 文档 ✅ / shiro 拦截 ✅ / 验证码 ✅

---

## 一、技术栈版本变化

| 项 | 旧版本 | 新版本 |
|---|---|---|
| JDK | 8 | **21** |
| Spring Boot | 2.6.6 | **3.5.16** |
| javax → jakarta | javax.* | **jakarta.***（servlet / validation / annotation） |
| MyBatis-Plus | 3.3.1 | **3.5.16** |
| mybatis-spring | 2.x（传递） | **3.0.6**（显式覆盖） |
| MySQL 驱动 | `mysql-connector-java` 8.0.28 | **`com.mysql:mysql-connector-j`**（Boot BOM 管理） |
| Druid | `druid-spring-boot-starter` 1.1.13 | **`druid-spring-boot-3-starter`** 1.2.28 |
| Quartz | 2.3.0 | **2.3.2** |
| Shiro | 1.9.0 | **2.2.1**（jakarta classifier） |
| JWT (jjwt) | 0.7.0 | **0.12.6**（拆分为 api / impl / jackson 三模块） |
| Swagger | springfox 2.7.0 | **springdoc-openapi 2.8.17** |
| Hutool | 4.1.1 | **5.8.47** |
| Lombok | 1.18.30 | Boot BOM 管理 |
| 验证码 kaptcha | 0.0.9 | 0.0.9（保留） |

> 注意：springdoc 用 **2.8.x**（不是 3.x）——3.x 是给 Spring Boot 4 的；pagehelper 同理（4.x 是给 Boot 4）。本项目 Boot 3.5.16 一律用 2.x 线。

---

## 二、pom.xml 依赖调整

### 移除/替换
- `springfox-swagger2` / `springfox-swagger-ui` → 删除，换 `springdoc-openapi-starter-webmvc-ui`
- `druid-spring-boot-starter` → `druid-spring-boot-3-starter`（Boot 3 专用）
- `io.jsonwebtoken:jjwt` → `jjwt-api` + `jjwt-impl` + `jjwt-jackson`
- `mysql:mysql-connector-java` → `com.mysql:mysql-connector-j`
- `org.apache.shiro:shiro-core/shiro-spring` → 加 `shiro-web`，三者均用 `2.2.1` + `classifier=jakarta`
  - ⚠️ `shiro-spring` 的 jakarta 变体会传递 **javax 版** `shiro-core`/`shiro-web`，必须 `<exclusion>` 排除，并显式引入 jakarta 版

### 新增
- `com.baomidou:mybatis-plus-jsqlparser:3.5.16` —— **3.5.4+ 分页插件拆分到该模块**，不加则 `PaginationInnerInterceptor` 找不到
- `org.mybatis:mybatis-spring:3.0.6` —— 覆盖 mybatis-plus 传递的 2.x，否则 Spring 6.2 启动报 `factoryBeanObjectType`

### 保留
- `ojdbc6`、`sqljdbc4`（注释状态，厂商 jar 不在公共仓库，用 Oracle/SQLServer 需手动安装）

---

## 三、代码改动清单

### 1. javax → jakarta（20 个文件，纯 import 替换）
- `javax.servlet.*` → `jakarta.servlet.*`（11 个文件）
- `javax.validation.*` → `jakarta.validation.*`（8 个文件）
- `javax.annotation.*` → `jakarta.annotation.*`（`ScheduleJobServiceImpl`）
- ⚠️ 不替换：`javax.sql.DataSource`、`javax.imageio.ImageIO`（JDK 自带包）

### 2. springfox → springdoc（6 个文件）
- `SwaggerConfig.java`：整体重写为 `OpenAPI` Bean + `SecurityScheme`（token）
- `AppLoginController` / `AppRegisterController` / `AppTestController`：`@Api`→`@Tag`、`@ApiOperation`→`@Operation`
- `LoginForm` / `RegisterForm`：`@ApiModel`/`@ApiModelProperty`→`@Schema`
- `ShiroConfig` 白名单：`/v2/api-docs`→`/v3/api-docs/**`、`/swagger-ui/**`

### 3. Spring 6 破坏性改动（2 处）
- `AuthorizationInterceptor`：`extends HandlerInterceptorAdapter`（Spring 6 已删除）→ `implements HandlerInterceptor`
- `application.yml`：删除 `spring.mvc.pathmatch.matching-strategy: ANT_PATH_MATCHER`（Boot 3 移除）

### 4. MyBatis-Plus 分页（1 处）
- `MybatisPlusConfig`：`PaginationInterceptor`（3.5.x 移除）→ `MybatisPlusInterceptor` + `PaginationInnerInterceptor(DbType.MYSQL)`

### 5. jjwt 0.12 API（1 处）
- `JwtUtils`：生成 `Jwts.builder().header().add("typ","JWT").and().subject(...).signWith(key)`；解析 `Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload()`；key 用 `Keys.hmacShaKeyFor(secret.getBytes(UTF_8))`

### 6. 配置迁移
- `application-dev/prod/test.yml`：`spring.redis.*` → `spring.data.redis.*`（Boot 3 改名）

### 7. 测试类 JUnit 4 → 5（3 个）
- `RedisTest` / `DynamicDataSourceTest` / `JwtTest`：`org.junit.Test`→`org.junit.jupiter.api.Test`，删 `@RunWith(SpringRunner.class)`

---

## 四、关键坑（升级必读）

1. **springdoc / pagehelper 版本线**：本项目 Spring Boot 3.5.16，凡"给 Boot 4 的新版"（springdoc 3.x、pagehelper 4.x）都不能用，用 2.x 线。
2. **shiro jakarta 传递 javax 版**：`shiro-spring` jakarta 变体传递的 `shiro-core`/`shiro-web` 仍是 javax 版，必须排除 + 显式加 jakarta，否则 `JWTFilter` 报"无法转换为 jakarta.servlet.Filter"。
3. **mybatis-plus 3.5.4+ 分页拆分**：`PaginationInnerInterceptor` 在 `mybatis-plus-jsqlparser` 模块，需显式加依赖。
4. **mybatis-spring 2.x 兼容**：Spring 6.2 下报 `factoryBeanObjectType`，必须覆盖到 3.0.6。
5. **spring.redis 改名**：Boot 3 中为 `spring.data.redis`。

---

## 五、验证结果

| 验证项 | 结果 |
|---|---|
| `mvn clean compile` | ✅ BUILD SUCCESS（144 个文件） |
| 启动 | ✅ `Started RenrenApplication in 7.3s` |
| `/renren-fast/v3/api-docs`（springdoc） | ✅ HTTP 200 |
| `/renren-fast/swagger-ui.html` | ✅ 重定向到 UI |
| shiro 拦截（带假 token） | ✅ body `{"code":401,"msg":"token失效"}` |
| `/renren-fast/captcha.jpg?uuid=xxx` | ✅ HTTP 200 |

> 注：renren-fast 的接口认证失败是通过 **JSON body 的 `code` 字段**表达（如 401），HTTP 状态码恒为 200，这是框架原有设计，非本次升级引入。
