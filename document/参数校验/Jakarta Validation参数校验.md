# Jakarta Validation 参数校验

> 技术栈：Spring Boot 3.5.16（自带 `jakarta.validation` + `hibernate-validator`）
> 包名：所有校验注解都在 `jakarta.validation.*` 下（不再是 `javax.validation.*`）
> 本文覆盖：`@NotNull @NotEmpty @NotBlank @Size @Min @Max @Email @Pattern @Valid @Validated`

---

## 目录

1. [依赖与触发机制](#1-依赖与触发机制)
2. [10 个注解详解](#2-10-个注解详解)
3. [@Valid 与 @Validated 的区别](#3-valid-与-validated-的区别)
4. [Controller 完整使用示例](#4-controller-完整使用示例)
5. [全局异常处理](#5-全局异常处理)

---

## 1. 依赖与触发机制

### 1.1 引入依赖

`spring-boot-starter-validation` 是 Boot 官方 starter，内部自动引入：

- `jakarta.validation-api`（Bean Validation 规范 3.x）
- `hibernate-validator`（规范实现）
- `tomcat-embed-el`（表达式语言，`message` 里写 `{...}` 占位符需要它）

```xml
<!-- Spring Boot 3 下版本由 BOM 管理，无需写 version -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

### 1.2 什么时候触发校验

| 触发位置 | 注解 | 说明 |
|---|---|---|
| Controller 方法 **`@RequestBody` 对象参数** | `@Valid` 或 `@Validated` | 反序列化后对对象内部字段校验 |
| Controller 方法 **`@RequestParam` / `@PathVariable` 单参数** | 类上加 `@Validated` | 方法参数上的约束注解才生效 |
| **Service/任意方法**的参数 | 类加 `@Validated` | Spring AOP 拦截方法参数校验 |
| 对象里的**嵌套对象**（对象含对象） | 外层加 `@Valid` | 级联进入内层对象校验 |

> `@RequestBody` 时 `@Valid` 与 `@Validated` 效果一样（都能触发字段校验）；
> 需要**分组校验**时才用 `@Validated(分组.class)`。`@Validated` 是 Spring 的，`@Valid` 是 Jakarta 规范的。

---

## 2. 10 个注解详解

> 所有注解都继承自 `jakarta.validation.Constraint`，都有三个**通用属性**：
> - `message`：校验失败时的提示信息（支持 `{key}` 从 ValidationMessages.properties 取，也支持 EL 表达式）
> - `groups`：分组校验（数组，配合 `@Validated(Group)` 用），默认走 Default 组
> - `payload`：负载，很少用，默认空数组

### 2.1 @NotNull —— 不能为 null

**用途**：校验属性值**不是 null**。任何引用类型都适用（对象、字符串、集合、数字包装类…）。
**注意**：`""` 空字符串和空集合**都能通过**（它只查 null），要拦空串/空集合用 @NotEmpty/@NotBlank。

```java
import jakarta.validation.constraints.NotNull;

public class UserDTO {

    /**
     * 用户ID：必须传，不能为 null
     * message：自定义提示
     * groups：只在 UpdateGroup 分组校验时生效（save 分组不校验这个字段）
     * payload：负载（一般不用，这里演示占位）
     */
    @NotNull(
        message = "用户ID不能为空",          // 校验失败提示（默认是 "must not be null"）
        groups = {UpdateGroup.class},       // 仅在 UpdateGroup 组触发
        payload = {Severity.Error.class}    // 校验严重级别负载（可自定义）
    )
    private Long userId;

    /** 姓名：任何情况都不能为 null，且提示信息支持占位符 {} */
    @NotNull(message = "姓名不能为空")
    private String name;

    /** groups 不写 = 默认 Default 组，所有场景都校验 */
    @NotNull
    private String phone;
}
```

| 属性 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `message` | String | `{jakarta.validation.constraints.NotNull.message}` | 失败提示，可用 ValidationMessages.properties 占位 |
| `groups` | Class<?>[] | `{}` | 分组；空=Default 组 |
| `payload` | Class<? extends Payload>[] | `{}` | 负载，一般不用 |

**适用类型**：`Object` 及其子类（除基本类型——基本类型不可能为 null）

---

### 2.2 @NotEmpty —— 非 null 且非空

**用途**：校验集合/Map/数组/字符串**既不是 null 也不是空**（`""`、空集合、空数组都失败）。
**适用**：`CharSequence`（String）、`Collection`、`Map`、数组。
**和 @NotBlank 区别**：@NotEmpty 允许 `"  "`（纯空格）通过，@NotBlank 会拦掉。

```java
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

public class OrderDTO {

    /** 商品 ID 列表：必须至少有一个元素 */
    @NotEmpty(message = "商品列表不能为空")
    private List<Long> productIds;

    /** 标签数组：长度 ≥ 1 */
    @NotEmpty
    private String[] tags;

    /** 附加参数 Map：至少一个键值对 */
    @NotEmpty(message = "附加参数不能为空")
    private Map<String, String> extra;

    /** 备注字符串：不能是 null 或 ""（但可以是 "  "） */
    @NotEmpty(message = "备注不能为空")
    private String remark;
}
```

| 属性 | 同 @NotNull（message/groups/payload） |
|---|---|

**适用类型**：`CharSequence`、`Collection`、`Map`、任意数组

---

### 2.3 @NotBlank —— 非 null 且去空白后非空（字符串专用）

**用途**：只适用于**字符串**，校验 `trim()` 之后**不能是空串**。`null`、`""`、`"   "` 都失败。
**最常用**：用户名、密码、名称这类"不能传空白"的字段。

```java
import jakarta.validation.constraints.NotBlank;

public class LoginDTO {

    /** 用户名：null、""、"   " 都会被拦截 */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 密码：去空白后至少 1 个字符 */
    @NotBlank(message = "密码不能为空")
    private String password;

    /** 备注：允许为空就**不要**加 @NotBlank；想拦 null 但不拦空格用 @NotNull */
    private String remark;
}
```

| 属性 | 同 @NotNull（message/groups/payload） |
|---|---|

**适用类型**：仅 `CharSequence`（String/StringBuilder 等）

| 对比 | @NotNull | @NotEmpty | @NotBlank |
|---|---|---|---|
| `null` | ❌ | ❌ | ❌ |
| `""` | ✅ | ❌ | ❌ |
| `"  "`（空格） | ✅ | ✅ | ❌ |
| `"abc"` | ✅ | ✅ | ✅ |
| 适用 | 任意对象 | 集合/Map/数组/串 | 仅字符串 |

---

### 2.4 @Size —— 长度/大小范围

**用途**：校验字符串长度、集合元素个数、Map 大小、数组长度在 `[min, max]` 区间内。
**min/max 都是含边界**。适用于 CharSequence / Collection / Map / 数组。对**集合元素**校验用 @Size；对**单元素内容**（如数字范围）用 @Min/@Max。

```java
import jakarta.validation.constraints.Size;
import java.util.List;

public class ProductDTO {

    /** 商品名：长度 2~50（含边界），提示里可用 {min}/{max} 占位符输出边界值 */
    @Size(min = 2, max = 50, message = "商品名长度必须在{min}~{max}之间")
    private String productName;

    /** 副标题：不写 min 默认 0，不写 max 默认 Integer.MAX_VALUE，即只限制 ≤200 */
    @Size(max = 200, message = "副标题最长200个字符")
    private String subTitle;

    /** 图片地址列表：1~10 张 */
    @Size(min = 1, max = 10, message = "图片数量必须在{min}~{max}张之间")
    private List<String> images;

    /** 详情分段字符串数组：长度 0~20（可为空数组但不能超过 20） */
    @Size(max = 20)
    private String[] sections;

    /** 分组：只在 AddGroup 时限制 2~10 */
    @Size(min = 2, max = 10, groups = {AddGroup.class})
    private List<String> specList;
}
```

| 属性 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `min` | int | `0` | 最小长度/个数（含） |
| `max` | int | `Integer.MAX_VALUE` | 最大长度/个数（含） |
| `message` | String | — | 提示（支持 `{min}`/`{max}` 占位符） |
| `groups` / `payload` | — | `{}` | 通用 |

**适用类型**：`CharSequence`、`Collection`、`Map`、数组

---

### 2.5 @Min —— 数值 ≥ value

**用途**：校验**数值**不小于 `value`（含边界，即 ≥）。
**适用**：`BigDecimal`、`BigInteger`、`byte/short/int/long` 及其包装类。注意**不支持 double/float**（精度问题）。

```java
import jakarta.validation.constraints.Min;

public class PriceDTO {

    /** 价格（分）：最小 0，即 ≥0 */
    @Min(value = 0, message = "价格不能小于0")
    private Long priceInCent;

    /** 库存：≥ 1 */
    @Min(value = 1, message = "库存至少为{value}")
    private Integer stock;

    /** 满减门槛 BigDecimal：≥ 100.00 */
    @Min(value = 100, message = "门槛最低100元")
    private java.math.BigDecimal threshold;
}
```

| 属性 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `value` | long | **必填（无默认）** | 最小值（含），提示里可用 `{value}` 输出 |
| `message` / `groups` / `payload` | — | — | 通用 |

**注意**：`@Min` 校验的是**数值本身**，不是字符串长度。想校验"字符串数字 ≥ X"需先转类型或自定义。

---

### 2.6 @Max —— 数值 ≤ value

**用途**：校验数值**不大于** `value`（含边界，即 ≤）。适用类型同 @Min。

```java
import jakarta.validation.constraints.Max;

public class CouponDTO {

    /** 每人限领数量：≤ 5 */
    @Max(value = 5, message = "每人最多限领{value}张")
    private Integer perLimit;

    /** 折扣率（0~1 的小数用 BigDecimal）：≤ 1 */
    @Max(value = 1, message = "折扣率不能超过{value}")
    private java.math.BigDecimal discountRate;

    /** 组合使用：库存 1~9999 */
    @Min(value = 1, message = "库存至少为1")
    @Max(value = 9999, message = "库存不能超过9999")
    private Integer stock;
}
```

| 属性 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `value` | long | **必填（无默认）** | 最大值（含），提示可用 `{value}` |
| `message` / `groups` / `payload` | — | — | 通用 |

---

### 2.7 @Email —— 邮箱格式

**用途**：校验字符串是合法的 email。默认校验规则较宽松（不强制顶级域名等），可自定义 `regexp`。
**说明**：`null` 视为**通过**（想拦 null 需叠加 @NotNull）——Bean Validation 所有约束对 null 默认都通过，空串是否拦截取决于实现（Hibernate 对空串按通过处理）。

```java
import jakarta.validation.constraints.Email;
import java.util.regex.Pattern;

public class UserDTO {

    /** 邮箱：默认宽松校验（不校验域名是否真实存在） */
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 邮箱 + 不能为空：叠加 @NotNull 拦 null，@Email 拦格式 */
    @NotNull(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String contactEmail;

    /**
     * 更严格的自定义正则：只允许 域名含任意字符 的邮箱
     * regexp：自定义正则（默认 ".*" 即不限制，靠内部算法判断）
     * flags：正则标志，如 CASE_INSENSITIVE 忽略大小写
     */
    @Email(
        regexp = "^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\\.[a-zA-Z0-9-.]+$",
        flags = {Pattern.Flag.CASE_INSENSITIVE},
        message = "邮箱格式必须为 xx@xx.xx"
    )
    private String strictEmail;
}
```

| 属性 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `regexp` | String | `".*"` | 自定义正则（默认不限制，走内部 email 判断） |
| `flags` | `Pattern.Flag[]` | `{}` | 正则标志（CASE_INSENSITIVE / MULTILINE 等） |
| `message` / `groups` / `payload` | — | — | 通用 |

**适用类型**：`CharSequence`

> `flags` 里的枚举来自 `java.util.regex.Pattern.Flag`（不是注解内定义的枚举）。

---

### 2.8 @Pattern —— 正则匹配

**用途**：用指定正则校验字符串**完全匹配**（`matches`，不是"包含"）。
**regexp 必填**。最灵活，手机号/身份证/自定义格式都用它。

```java
import jakarta.validation.constraints.Pattern;
import java.util.regex.Pattern;

public class AccountDTO {

    /**
     * 手机号：中国 11 位手机号
     * regexp：正则（**必填**）
     * flags：CASE_INSENSITIVE + MULTILINE 组合
     */
    @Pattern(
        regexp = "^1[3-9]\\d{9}$",
        flags = {Pattern.Flag.CASE_INSENSITIVE, Pattern.Flag.MULTILINE},
        message = "手机号格式不正确"
    )
    private String mobile;

    /** 身份证：18 位，最后一位可能是 X */
    @Pattern(regexp = "^\\d{17}[\\dXx]$", message = "身份证号格式不正确")
    private String idCard;

    /** 密码：8~20 位，含字母和数字 */
    @Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{8,20}$",
        message = "密码需8-20位且包含字母和数字"
    )
    private String password;

    /** 只校验格式 + 不传 null：叠加 @NotNull（@Pattern 对 null 放行） */
    @NotNull(message = "邀请码不能为空")
    @Pattern(regexp = "^[A-Z0-9]{6}$", message = "邀请码为6位大写字母或数字")
    private String inviteCode;
}
```

| 属性 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `regexp` | String | **必填（无默认）** | 正则表达式 |
| `flags` | `Pattern.Flag[]` | `{}` | 正则标志 |
| `message` / `groups` / `payload` | — | — | 通用 |

**适用类型**：`CharSequence`
**常用正则**：手机号 `^1[3-9]\d{9}$`、邮箱、6 位数字 `^\d{6}$`、纯数字 `^\d+$`、纯字母 `^[A-Za-z]+$`

---

### 2.9 @Valid —— 级联校验（嵌套对象）

**用途**：当对象的某个字段**本身是个对象**（或对象集合）时，加 `@Valid` 让校验器**递归进入该字段内部**校验其字段上的约束。不加 @Valid，内层对象的约束不会触发。

```java
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 下单请求 */
public class OrderRequest {

    @NotNull(message = "用户不能为空")
    private Long userId;

    /** 收货地址：嵌套对象，必须 @Valid 才会校验 AddressDTO 内部的 @NotBlank 等 */
    @Valid
    @NotNull(message = "收货地址不能为空")
    private AddressDTO address;

    /** 商品明细列表：集合元素是对象，用 @Valid 对每个元素内部校验 */
    @Valid
    @NotNull(message = "商品不能为空")
    private List<OrderItemDTO> items;
}

/** 嵌套的子对象——校验注解写在字段上，父级用 @Valid 触发 */
class AddressDTO {

    @NotBlank(message = "收货人不能为空")
    private String receiver;

    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号不正确")
    private String mobile;

    @NotBlank(message = "详细地址不能为空")
    private String detail;
}

class OrderItemDTO {

    @NotNull(message = "商品ID不能为空")
    private Long productId;

    @Min(value = 1, message = "购买数量至少为1")
    private Integer count;
}
```

| 属性 | 说明 |
|---|---|
| （无自有属性） | 它本身没有 message/groups，级联校验使用内层字段上的注解及其 message |

**用法**：
- 字段对象 → `@Valid`（可配 `@NotNull` 一起）
- 集合/数组元素是对象 → `@Valid`（对每个元素级联）
- `Map<String, XxxDTO>` 的 value → `@Valid`

---

### 2.10 @Validated —— Spring 触发校验（方法/类/分组）

**用途**：Spring 提供，标在**类**上启用对该类方法参数的校验；也支持**分组**（`@Validated(AddGroup.class)`）。
和 @Valid 区别：@Validated 能让**非 Controller 的普通方法**也校验参数，且支持分组；但它不能级联嵌套对象（级联仍靠 @Valid）。

```java
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import org.springframework.stereotype.Service;

/** 分组接口：新增组 */
public interface AddGroup {}

/** 分组接口：修改组 */
public interface UpdateGroup {}

/**
 * 类上 @Validated：启用本类方法参数/返回值校验
 * （不加分组 = 只校验 Default 组注解；要按分组触发在方法参数上加 @Validated(组)）
 */
@Service
@Validated
public class UserService {

    /**
     * 按 ID 查用户：参数用约束注解
     * 由于类已 @Validated，这里的 @NotNull 会触发（Controller 没 @Validated 的方法参数注解不会生效）
     */
    public UserDTO getUserById(@NotNull(message = "用户ID不能为空") Long userId) {
        // ...
        return null;
    }

    /**
     * 新增：用 AddGroup 分组——只有标了 groups = AddGroup 的字段才校验
     * 比如 UserDTO.userId 若标 @NotNull(groups=UpdateGroup)，这里新增时不校验它
     */
    public void addUser(@Validated(AddGroup.class) UserDTO dto) {
        // ...
    }

    /** 更新：走 UpdateGroup 分组 */
    public void updateUser(@Validated(UpdateGroup.class) UserDTO dto) {
        // ...
    }
}
```

| 关注点 | @Valid | @Validated |
|---|---|---|
| 来源 | `jakarta.validation.Valid`（规范） | `org.springframework.validation.annotation.Validated`（Spring） |
| 级联嵌套对象 | ✅ | ❌（需配合 @Valid） |
| 方法参数校验（非 Controller） | ❌ 不触发 | ✅（类上加 @Validated） |
| 分组校验 | ❌ 不支持 | ✅ `@Validated(组.class)` |
| 位置 | 字段/方法参数/方法返回值 | 类/方法参数/方法（可放多个） |

**结合分组使用的实体示例**：
```java
public class UserDTO {

    /** 新增时必填，修改时可空（如系统生成主键） */
    @Null(groups = AddGroup.class, message = "新增时不能传ID")
    @NotNull(groups = UpdateGroup.class, message = "修改时必须传ID")
    private Long userId;

    /** 仅新增组校验非空 */
    @NotBlank(groups = AddGroup.class, message = "用户名不能为空")
    private String username;

    /** 两个组都校验格式 */
    @Pattern(regexp = "^1[3-9]\\d{9}$", groups = {AddGroup.class, UpdateGroup.class}, message = "手机号不正确")
    private String mobile;
}
```
> 分组后，**Default 组的注解仍会校验**（除非在 Controller 明确传组，Spring 默认校验 Default；用 `@Validated(AddGroup.class)` 时只校验 AddGroup 及其继承的 Default……实际行为：若指定了组，只校验该组的注解，除非该组 interface extends Default）。所以字段分组标注时要规划好"哪些组都校验"。

---

## 3. @Valid 与 @Validated 的区别

| 维度 | @Valid | @Validated |
|---|---|---|
| 归属 | Jakarta Bean Validation 规范 | Spring Framework |
| 级联校验（对象含对象） | ✅ | ❌（单用不行） |
| 分组校验 | ❌ | ✅ |
| 标在普通类方法参数上触发 | ❌ | ✅（需类上 @Validated） |
| 使用位置 | 字段 / 方法参数 / 返回 | 类 / 方法 / 方法参数 |

**经验法则**：
- Controller `@RequestBody` → 用 `@Valid` 即可；要分组换 `@Validated(Group)`
- Service 层方法参数校验 → 类上加 `@Validated`
- 嵌套对象级联 → 字段上加 `@Valid`

---

## 4. Controller 完整使用示例

```java
package com.atguigu.gulimall.product.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 类上 @Validated：让 @RequestParam/@PathVariable 上的约束注解生效
 */
@Validated
@RestController
@RequestMapping("/user")
public class UserController {

    /**
     * 表单对象校验：@Valid 触发 UserDTO 内部字段所有约束 + 嵌套对象
     */
    @PostMapping("/add")
    public R add(@Valid @RequestBody UserDTO dto) {
        // dto 已通过校验
        return R.ok();
    }

    /**
     * 分组新增：只校验 AddGroup 组的注解
     */
    @PostMapping("/add2")
    public R addByGroup(@Validated(AddGroup.class) @RequestBody UserDTO dto) {
        return R.ok();
    }

    /**
     * 单参数校验（依赖类上 @Validated）：
     * id 不能为 null 且 ≥1；page 默认 1，必须 ≥1
     */
    @GetMapping("/detail")
    public R detail(@NotNull(message = "ID不能为空") @Min(value = 1, message = "ID必须≥1") @RequestParam Long id) {
        return R.ok();
    }

    /**
     * 路径参数校验
     */
    @GetMapping("/info/{pageNo}")
    public R info(@Min(value = 1, message = "页码≥1") @PathVariable Integer pageNo) {
        return R.ok();
    }
}
```

---

## 5. 全局异常处理

校验失败会抛不同类型异常，用 `@RestControllerAdvice` 统一捕获并返回友好信息。

| 异常 | 场景 | 包含信息 |
|---|---|---|
| `MethodArgumentNotValidException` | `@RequestBody` + `@Valid/@Validated` 校验失败 | 字段级错误（FieldError） |
| `ConstraintViolationException` | 单参数（@RequestParam/@PathVariable）校验失败 | 参数路径 + message |
| `BindException` | 表单绑定失败 | 字段级错误 |

```java
package com.atguigu.gulimall.product.exception;

import com.atguigu.common.utils.R;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局参数校验异常处理
 */
@RestControllerAdvice
public class ValidationExceptionHandler {

    /** 处理 @RequestBody 对象校验失败（最常用） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return R.error(400, msg);
    }

    /** 处理单参数（@RequestParam/@PathVariable）校验失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public R handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        return R.error(400, msg);
    }

    /** 处理表单绑定失败（非 JSON，如普通 POST form） */
    @ExceptionHandler(BindException.class)
    public R handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return R.error(400, msg);
    }
}
```

### 附：message 支持占位符与国际化

```properties
# 文件：classpath:ValidationMessages.properties（默认语言）
# 也支持 ValidationMessages_zh_CN.properties 按 locale 切换
jakarta.validation.constraints.NotBlank.message = 不能为空
# 自定义 key 引用：message = "{user.name.notblank}"
user.name.notblank = 用户名不能为空
```

```java
// message 里写 {key} 会从 ValidationMessages.properties 取值；{min}/{max} 是注解内置占位
@NotBlank(message = "{user.name.notblank}")
@Size(min = 2, max = 10, message = "长度需在{min}~{max}之间")  // {min} {max} 自动替换为 2、10
private String name;
```

---

## 快速速查表

| 注解 | 校验内容 | 适用类型 | 特有属性 |
|---|---|---|---|
| `@NotNull` | 非 null | 任意对象 | 无（仅通用三件套） |
| `@NotEmpty` | 非 null 且非空 | 集合/Map/数组/CharSequence | 无 |
| `@NotBlank` | 非 null 且去空白非空 | 仅 CharSequence | 无 |
| `@Size` | 长度/个数 ∈ [min,max] | 集合/Map/数组/CharSequence | `min`(0), `max`(MAX) |
| `@Min` | 数值 ≥ value | byte/short/int/long 及包装/BigDecimal/BigInteger | `value`(必填) |
| `@Max` | 数值 ≤ value | 同上 | `value`(必填) |
| `@Email` | email 格式 | CharSequence | `regexp`(".*"), `flags`({}) |
| `@Pattern` | 正则完全匹配 | CharSequence | `regexp`(必填), `flags`({}) |
| `@Valid` | 级联嵌套对象 | 字段/对象/集合 | 无 |
| `@Validated` | 触发方法校验 / 分组 | 类/方法/参数 | （Spring 注解） |

> 通用三件套 = `message` + `groups` + `payload`，所有约束注解都有。
> Bean Validation 约定：**null 值默认视为通过**（除非注解不支持 null，如 @NotNull/@NotEmpty/@NotBlank 本身就拦 null）。所以"必填"通常要 @NotBlank/@NotNull 显式声明。
