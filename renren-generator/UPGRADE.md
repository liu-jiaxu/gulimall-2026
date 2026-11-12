# renren-generator 版本升级说明

> 升级时间：2026-08-28
> 升级目标：从 **Spring Boot 2.2.6 + Java 8** 升级到 **Spring Boot 3.5.16 + Java 21**，与 gulimall 主项目技术栈对齐。
> 触发原因：IDEA 用 JDK 21 编译后 class 变 major 65，Spring 5.2.5 的 ASM 只支持 Java 13 及以下，启动报 `Unsupported class file major version 65`。
> 验证结果：编译 ✅ / 启动 ✅（Tomcat 80）/ 表列表接口 ✅ / 代码生成 ✅

---

## 一、技术栈版本变化

| 项 | 旧版本 | 新版本 |
|---|---|---|
| JDK | 8 | **21** |
| Spring Boot | 2.2.6.RELEASE | **3.5.16** |
| MyBatis | mybatis-plus-boot-starter 3.3.1 | **mybatis-spring-boot-starter**（生成器自身不用 mybatis-plus，只需 @Mapper/XML） |
| mybatis-spring | 2.x（传递） | **3.0.6**（显式覆盖） |
| 分页 | pagehelper-spring-boot-starter 1.2.5 | **2.1.1** |
| MySQL 驱动 | `mysql-connector-java` 8.0.17 | **`com.mysql:mysql-connector-j`**（Boot BOM 管理） |
| 模板引擎 | `org.apache.velocity:velocity` 1.7 | **`org.apache.velocity:velocity-engine-core`** 2.4.1 |
| Druid | 1.1.13 | **1.2.23** |
| fastjson | 1.2.60 | **1.2.83** |
| 新增 | — | `commons-collections:3.2.2`（velocity 2.x 不再传递，MongoDefinition 用到） |
| Mongo | mongo-java-driver 3.11.0 | 保留（默认禁用，不升级 API） |

---

## 二、pom.xml 依赖调整

### 移除/替换
- `mybatis-plus-boot-starter:3.3.1` → **`mybatis-spring-boot-starter`**（由 pagehelper 传递）
- `org.apache.velocity:velocity:1.7` → `org.apache.velocity:velocity-engine-core:2.4.1`（API 不变）
- `mysql:mysql-connector-java` → `com.mysql:mysql-connector-j`
- `ojdbc6` / `sqljdbc4` → 删除（厂商 jar 不在公共仓库）

### 新增（覆盖传递依赖）
- `org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.5`（覆盖 pagehelper 传递的 2.3.2）
- `org.mybatis:mybatis-spring:3.0.6`（兼容 Spring 6.2）
- `commons-collections:3.2.2`

---

## 三、代码改动清单

### 1. javax → jakarta（3 个文件 4 处 import）
- `SysGeneratorController`：`javax.servlet.http.HttpServletResponse` → jakarta
- `RRExceptionHandler`：`javax.servlet.http.HttpServletRequest/Response` → jakarta
- `MongoDBCollectionFactory`：`javax.annotation.PostConstruct` → jakarta

### 2. 测试类 JUnit 4 → 5（1 个）
- `RenrenApplicationTests`：`org.junit.Test`→`org.junit.jupiter.api.Test`，删 `@RunWith(SpringRunner.class)`

### 3. 配置迁移（application.yml 2 处）
- `mybatis-plus.mapperLocations` → **`mybatis.mapper-locations`**（换 mybatis starter 后配置项改名，否则 `Invalid bound statement`）
- `spring.resources.static-locations` → **`spring.web.resources.static-locations`**（Boot 3 改名）

### 4. Mongo 功能（默认禁用，未改动代码）
- 保留 `mongo-java-driver:3.11.0`（仅作编译依赖），mongo 相关类靠 `@Conditional(MongoCondition)` + `application.yml` 注释配置默认不启用
- 未升级 mongo API（驱动 4.x 适配是独立大工程，当前 MySQL 生成路径不受影响）

---

## 四、关键坑（升级必读）

1. **pagehelper 版本线**：`pagehelper-spring-boot-starter` **4.x 是给 Spring Boot 4 的**，会传递 `mybatis-spring-boot-starter 4.0.1` 并把 Boot 4 的 `spring-boot-jdbc/sql/transaction/persistence` 模块混入 Boot 3.5，启动报 `hikariPoolDataSourceMetadataProvider` bean 重复。Boot 3 必须用 **2.1.1**。
2. **pagehelper 2.1.1 传递 mybatis-spring-boot-starter 2.3.2**（Boot 2 时代）→ 显式覆盖为 **3.0.5** + mybatis-spring **3.0.6**。
3. **mybatis-plus 配置项改名**：换 mybatis starter 后 `mybatis-plus.mapperLocations` 失效。
4. **velocity 2.x 不传递 commons-collections**：`MongoDefinition` 用 `CollectionUtils` 需显式补 `commons-collections`。
5. **IDEA 构建**：升级后需 Reload Maven，且确认模块 SDK 为 JDK 21（Boot 3.5.16 要求）。

---

## 五、生成模板验证

模板（`template/*.vm`）**无需修改**——生成的代码 import 与主项目 gulimall 技术栈完全一致：

| 生成文件 | 引用 | 适配 |
|---|---|---|
| Entity | `com.baomidou.mybatisplus.annotation.{TableId,TableName}` | ✅ 3.5.16 包名不变 |
| Controller | `org.apache.shiro.authz.annotation.RequiresPermissions`、`com.atguigu.common.utils` | ✅ 与主项目一致 |
| ServiceImpl | `mybatisplus.extension.service.impl.ServiceImpl`、`QueryWrapper`、`IPage` | ✅ 3.5.16 包名不变 |
| Service | `mybatisplus.extension.service.IService` | ✅ |
| Dao | `BaseMapper`、`@Mapper` | ✅ |

已用 `wms_purchase` 表实测生成代码，产物无 javax 残留，可直接放入 gulimall 模块编译。

---

## 六、验证结果

| 验证项 | 结果 |
|---|---|
| `mvn clean compile` | ✅ BUILD SUCCESS |
| 启动 | ✅ `Started RenrenApplication in 2.5s`，不再报 major version 65 |
| `/sys/generator/list` | ✅ 返回 gulimall_wms 表列表 |
| `/`（前端页面） | ✅ HTTP 200 |
| 生成代码（`/sys/generator/code?tables=wms_purchase`） | ✅ zip 下载，产物 import 与主项目一致 |
