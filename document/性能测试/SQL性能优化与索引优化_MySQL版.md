# SQL性能优化与索引优化

> 本指南涵盖：慢查询定位、EXPLAIN详解、索引失效场景、关联查询优化、子查询优化、排序优化、覆盖索引、索引下推(ICP)、EXISTS/IN选择、COUNT优化、分页优化、主键设计等全部知识点。

------

## 目录

1. SQL优化核心思路
2. 第一步：定位慢SQL（慢查询日志）
3. 第二步：EXPLAIN分析执行计划
4. 第三步：SHOW PROFILE分析详细耗时
5. 第四步：索引失效的11种场景
6. 关联查询优化（JOIN优化）
7. 子查询优化
8. 排序优化（ORDER BY）
9. 覆盖索引
10. 索引下推（ICP）
11. EXISTS vs IN 选择
12. COUNT查询优化
13. 分页查询优化
14. 主键设计
15. 优化决策优先级
16. 生产环境自查清单

------

## 一、SQL优化核心思路

```
定位慢SQL → 分析执行计划 → 针对性优化 → 验证效果
    ↓            ↓            ↓            ↓
 慢查询日志   EXPLAIN     加索引/改SQL   SHOW PROFILING
```

### 数据库调优维度

| 维度                     | 解决方案   |
| :----------------------- | :--------- |
| 索引失效，未充分利用索引 | 索引建立   |
| 关联查询太多JOIN         | SQL优化    |
| 服务器调优及参数设置     | 调整my.cnf |
| 数据过多                 | 分库分表   |

### 物理查询优化 vs 逻辑查询优化

| 类型             | 内容                                         |
| :--------------- | :------------------------------------------- |
| **物理查询优化** | 索引优化、表连接优化                         |
| **逻辑查询优化** | SQL等价交换（改写SQL不改变结果，但提升效率） |

------

## 二、第一步：定位慢SQL（慢查询日志）

### 2.1 查看和开启慢查询日志

```
-- 查看慢查询日志是否开启
SHOW VARIABLES LIKE 'slow_query_log';

-- 临时开启（重启失效）
SET GLOBAL slow_query_log = ON;

-- 查看慢查询阈值（默认10秒）
SHOW VARIABLES LIKE 'long_query_time';

-- 设置阈值为3秒
SET GLOBAL long_query_time = 3;
```

### 2.2 查看慢查询次数

```
-- 查看当前慢查询总数
SHOW STATUS LIKE 'Slow_queries';
```

### 2.3 生产环境配置（永久生效）

在 `my.cnf` 或 `my.ini` 中添加：

```
[mysqld]
slow_query_log = ON
slow_query_log_file = /var/log/mysql/slow.log
long_query_time = 2
log_queries_not_using_indexes = ON   # 记录未走索引的查询
```

### 2.4 生产环境阈值设置建议

| 场景类型             | 推荐阈值      | 说明                 |
| :------------------- | :------------ | :------------------- |
| 高并发、核心业务系统 | 0.1秒 ~ 0.5秒 | 交易、订单等核心链路 |
| 大多数常规生产系统   | 1秒 ~ 2秒     | 通用推荐值           |
| 非核心或离线任务     | 5秒 ~ 10秒    | 后台报表、定时任务   |
| 测试/开发环境        | 0.1秒 ~ 0.5秒 | 更严格，提前发现问题 |

------

## 三、第二步：EXPLAIN分析执行计划

### 3.1 EXPLAIN 语法

```
-- 基本用法
EXPLAIN SELECT * FROM student WHERE age = 30;

-- 分析DELETE（不会真正删除）
EXPLAIN DELETE FROM student WHERE age < 18;

-- 分析UPDATE
EXPLAIN UPDATE student SET status = 1 WHERE age > 60;

-- 分析INSERT（MySQL 5.6+）
EXPLAIN INSERT INTO user_log(user_id, action) VALUES (1, 'login');

-- 查看JSON格式（更详细）
EXPLAIN FORMAT=JSON SELECT * FROM student WHERE age > 18;
```

### 3.2 EXPLAIN 结果各列详解

| 列名          | 描述                             |
| :------------ | :------------------------------- |
| id            | 每个SELECT关键字对应的唯一id     |
| select_type   | SELECT对应的查询类型             |
| table         | 表名                             |
| partitions    | 匹配的分区信息                   |
| type          | 针对单表的访问方法（**最重要**） |
| possible_keys | 可能用到的索引                   |
| key           | 实际使用的索引                   |
| key_len       | 实际使用到的索引长度（字节数）   |
| ref           | 与索引列进行等值匹配的对象信息   |
| rows          | 预估需要读取的记录条数           |
| filtered      | 过滤后剩余记录条数的百分比       |
| Extra         | 额外信息                         |

### 3.3 select_type 详解

| select_type            | 含义                                    |
| :--------------------- | :-------------------------------------- |
| **SIMPLE**             | 不包含UNION或子查询的简单查询           |
| **PRIMARY**            | 包含UNION或子查询的大查询中最左边的查询 |
| **UNION**              | UNION中除最左边外的其他小查询           |
| **UNION RESULT**       | UNION去重的临时表查询                   |
| **SUBQUERY**           | 不相关子查询的第一个SELECT              |
| **DEPENDENT SUBQUERY** | 相关子查询（可能会执行多次）            |
| **DEPENDENT UNION**    | UNION中各小查询依赖外层查询             |
| **DERIVED**            | 派生表（FROM子句中的子查询）            |
| **MATERIALIZED**       | 被物化的子查询                          |

### 3.4 type 级别速查（从优到差）

```
system     		→ 表中只有一行数据（极少见）
const      		→ 主键或唯一索引等值查询 ✅ 最优
eq_ref     		→ 连接查询中被驱动表用主键/唯一索引匹配 ✅ 优
ref        		→ 普通索引等值匹配 ✅ 良好
fulltext   		→ 全文索引
ref_or_null		→ 对普通二级索引进行等值匹配，值可为NULL
index_merge		→ 使用索引合并（Intersection/Union/Sort-Union）
unique_subquery → IN子查询使用主键等值匹配
index_subquery  → IN子查询使用非唯一索引
range      		→ 范围查询（BETWEEN、>、<、IN） ✅ 可接受
index     		→ 扫描整个索引树 ⚠️ 比全表快但仍有开销
ALL       		→ 全表扫描 ❌ 最差，必须优化
```

### 3.5 rows 字段：MySQL优化器预估需要扫描的记录行数。

| rows 数量级      | 评级   | 建议                |
| :--------------- | :----- | :------------------ |
| 1 ~ 100          | ✅ 优秀 | 无需优化            |
| 100 ~ 1,000      | ✅ 良好 | 可接受              |
| 1,000 ~ 10,000   | ⚠️ 一般 | 考虑加索引或优化SQL |
| 10,000 ~ 100,000 | ❌ 较差 | 必须加索引或改写SQL |
| 100,000+         | ❌ 极差 | 紧急优化            |

**实际例子：**

```
-- 假设 user 表有 100 万条数据

-- 场景1：有索引 → rows = 1 ✅
EXPLAIN SELECT * FROM user WHERE id = 100;

-- 场景2：有索引但条件不精确 → rows = 500000 ⚠️
EXPLAIN SELECT * FROM user WHERE age > 18;

-- 场景3：没有索引 → rows = 1000000 ❌
EXPLAIN SELECT * FROM user WHERE phone = '13800138000';
```

### 3.6 Extra 字段详解（最重要）

| Extra 值                | 含义                             | 处理建议               |
| :---------------------- | :------------------------------- | :--------------------- |
| `Using index`           | **覆盖索引**，不需要回表         | ✅ 理想状态             |
| `Using where`           | 需要回表后过滤                   | 尽量把过滤条件放进索引 |
| `Using filesort`        | 需要额外的排序操作               | 建索引优化ORDER BY     |
| `Using temporary`       | 需要临时表（GROUP BY/DISTINCT）  | 建索引优化GROUP BY     |
| `Using index condition` | **索引下推(ICP)**                | ✅ 好现象               |
| `Using join buffer`     | 用了BNLJ（块嵌套循环连接）       | 给被驱动表加索引       |
| `Not exists`            | 左外连接中被驱动表某列不允许NULL | 正常现象               |
| `Using intersect(...)`  | 使用Intersect索引合并            | 合理使用               |
| `Using union(...)`      | 使用Union索引合并                | 合理使用               |
| `Using sort_union(...)` | 使用Sort-Union索引合并           | 合理使用               |
| `No tables used`        | 没有FROM子句                     | 如 `SELECT 1`          |
| `Impossible WHERE`      | WHERE条件永远为FALSE             | 检查业务逻辑           |

### 3.7 key_len 计算规则

**key_len表示实际使用到的索引长度（字节数），值越大说明联合索引利用越充分。**

**计算公式：**

```
VARCHAR(10) 变长字段 且 允许NULL  = 10 × 字符集字节数 + 2(变长) + 1(NULL)
VARCHAR(10) 变长字段 且 不允许NULL = 10 × 字符集字节数 + 2(变长)
CHAR(10)    固定字段 且 允许NULL  = 10 × 字符集字节数 + 1(NULL)
CHAR(10)    固定字段 且 不允许NULL = 10 × 字符集字节数
```

**字符集对应字节数：**

- utf8 = 3字节
- gbk = 2字节
- latin1 = 1字节
- utf8mb4 = 4字节

**示例：**

```
-- 联合索引 idx_key_part(key_part1, key_part2, key_part3)
-- 都是 VARCHAR(100) 允许NULL，字符集utf8
-- key_len = 100 × 3 + 2(变长) + 1(NULL) = 303

EXPLAIN SELECT * FROM s1 WHERE key_part1 = 'a' AND key_part2 = 'b';
-- key_len = 303 × 2 = 606 ✅ 用了两列

EXPLAIN SELECT * FROM s1 WHERE key_part1 = 'a' AND key_part3 = 'c';
-- key_len = 303 ✅ 只用了一列（key_part3用不上）
```

------

## 四、第三步：SHOW PROFILE分析详细耗时

```
-- 开启profile
SET profiling = ON;

-- 执行要分析的SQL
SELECT * FROM user WHERE age > 18;

-- 查看所有查询
SHOW PROFILES;

-- 查看具体查询各阶段耗时
SHOW PROFILE FOR QUERY 1;

-- 查看更详细信息
SHOW PROFILE CPU, BLOCK IO FOR QUERY 1;
```

**重点关注耗时大的阶段：**

- `Sending data`：发送数据（网络传输）
- `Creating sort index`：排序（ORDER BY）
- `Creating tmp table`：创建临时表（GROUP BY/DISTINCT/UNION）
- `Sorting result`：排序操作

------

## 五、第四步：索引失效的11种场景

### 场景1：全值匹配（不失效）

```
-- ✅ 全值匹配，索引有效
EXPLAIN SELECT * FROM student WHERE age=30 AND classId=4 AND name='abcd';
-- 建立联合索引 (age, classId, name) 全部使用
```

### 场景2：最佳左前缀法则

```
-- 联合索引 (age, classId, name)

-- ✅ 使用全部索引
WHERE age = 30 AND classId = 4 AND name = 'abc'

-- ✅ 使用 age 和 classId
WHERE age = 30 AND classId = 4

-- ✅ 使用 age
WHERE age = 30

-- ✅ 使用 age 和 classId（优化器会调整顺序）
WHERE classId = 4 AND age = 30

-- ❌ 索引失效（跳过第一列）
WHERE classId = 4 AND name = 'abc'

-- ⚠️ 只用 age，name用不上（跳过classId）
WHERE age = 30 AND name = 'abc'
```

### 场景3：主键插入顺序

```
-- ✅ 推荐：主键单调递增（自增主键）
INSERT INTO user(id, name) VALUES (1, 'a'), (2, 'b'), (3, 'c');

-- ❌ 不推荐：主键随机（如UUID）
-- B+树频繁页分裂，性能差
```

### 场景4：计算、函数导致索引失效

```
-- ❌ 索引失效（使用了函数）
EXPLAIN SELECT * FROM student WHERE LEFT(name, 3) = 'abc';

-- ✅ 可以走索引
EXPLAIN SELECT * FROM student WHERE name LIKE 'abc%';

-- ❌ 索引失效（使用了计算）
EXPLAIN SELECT * FROM student WHERE stuno + 1 = 900001;

-- ✅ 可以走索引
EXPLAIN SELECT * FROM student WHERE stuno = 900000;
```

### 场景5：隐式类型转换导致索引失效

```
-- name 是 VARCHAR 类型

-- ❌ 索引失效（传入数字，发生类型转换）
EXPLAIN SELECT * FROM student WHERE name = 123;

-- ✅ 可以走索引（传入字符串）
EXPLAIN SELECT * FROM student WHERE name = '123';
```

### 场景6：范围条件右边的列索引失效

```
-- 联合索引 (age, classId, name)

-- ⚠️ classId使用范围查询，name用不上索引
EXPLAIN SELECT * FROM student 
WHERE age = 30 AND classId > 20 AND name = 'abc';
-- 只用到 (age, classId)，name失效

-- 当 classId > 20 是一个范围时，所有符合条件的记录（classId为21、22、23...）在叶子节点中是连续排列的。
-- 一旦我们锁定了这个范围，这个范围内的记录，其 name 字段是乱序的（因为在classId相同的情况下才按name排序，而现在classId是多个不同的值）。

-- ✅ 将范围查询的列放在联合索引最后
-- 改为索引 (age, name, classId)
EXPLAIN SELECT * FROM student 
WHERE age = 30 AND name = 'abc' AND classId > 20;
-- 用到了 (age, name, classId) 全部

-- 注意：优化器会自动调整WHERE条件顺序，但索引列顺序是固定的
```

### 场景7：不等于（!= 或 <>）导致索引失效

```
-- ❌ 索引失效
EXPLAIN SELECT * FROM student WHERE name != 'abc';
EXPLAIN SELECT * FROM student WHERE name <> 'abc';
```

### 场景8：IS NULL 可用索引，IS NOT NULL 失效

```
-- ✅ IS NULL 可以使用索引
EXPLAIN SELECT * FROM student WHERE age IS NULL;

-- ❌ IS NOT NULL 无法使用索引
EXPLAIN SELECT * FROM student WHERE age IS NOT NULL;
```

### 场景9：LIKE 以通配符 % 开头导致索引失效

```
-- ✅ 可以使用索引
EXPLAIN SELECT * FROM student WHERE name LIKE 'ab%';

-- ❌ 索引失效（%开头，B+树无法查找）
EXPLAIN SELECT * FROM student WHERE name LIKE '%ab%';

-- 如果必须要用 %开头，可以用覆盖索引
-- ✅ 覆盖索引，不需要回表
EXPLAIN SELECT id, name FROM student WHERE name LIKE '%ab%';
-- 覆盖索引查询：SELECT id, name 只查 id 和 name。这两个字段刚好都在 idx_name 这个二级索引的叶子节点上。MySQL 可以强制或者选择遍历这个索引树，所有需要的数据都在这个索引里，不需要回表去查完整数据行。
```

### 场景10：OR 前后存在非索引列，索引失效

```
-- ❌ classId没有索引，整个查询不走索引
EXPLAIN SELECT * FROM student WHERE age = 10 OR classId = 100;

-- ✅ 两边都建索引
CREATE INDEX idx_age ON student(age);
CREATE INDEX idx_classId ON student(classId);
-- 现在 OR 可以使用索引
EXPLAIN SELECT * FROM student WHERE age = 10 OR classId = 100;
```

### 场景11：字符集不统一

```
-- 数据库和表的字符集统一使用 utf8mb4
-- 一旦发生字符集转换，相当于使用了函数，索引失效
```

------

## 六、关联查询优化（JOIN优化）

### 6.1 准备测试数据

```
-- 分类表（type）
CREATE TABLE `type` (
    `id` INT(10) UNSIGNED NOT NULL AUTO_INCREMENT,
    `card` INT(10) UNSIGNED NOT NULL,
    PRIMARY KEY (`id`)
);

-- 图书表（book）
CREATE TABLE `book` (
    `bookid` INT(10) UNSIGNED NOT NULL AUTO_INCREMENT,
    `card` INT(10) UNSIGNED NOT NULL,
    PRIMARY KEY (`bookid`)
);

-- 各插入20条数据（card值1~20随机）
INSERT INTO `type`(card) VALUES(FLOOR(1 + (RAND() * 20)));
-- ... 重复20次

INSERT INTO book(card) VALUES(FLOOR(1 + (RAND() * 20)));
-- ... 重复20次
```

### 6.2 左外连接优化

```
-- 原始SQL（type左连接book）
EXPLAIN SELECT * FROM `type` LEFT JOIN book ON type.card = book.card;
-- type=ALL, rows=20, Extra=Using where; Using join buffer

-- 优化：给被驱动表（book）的连接字段加索引
CREATE INDEX Y ON book(card);
EXPLAIN SELECT * FROM `type` LEFT JOIN book ON type.card = book.card;
-- type=ALL(驱动表), ref(被驱动表) ✅

-- 进一步优化：也给驱动表建索引
CREATE INDEX X ON `type`(card);
-- 两个表都有索引，效率最高
```

**左外连接结论：**

- 左表一定是驱动表，右表是被驱动表
- **给被驱动表（右表）的连接字段加索引**

### 6.3 内连接优化

```
-- 原始SQL（无索引）
EXPLAIN SELECT * FROM `type` INNER JOIN book ON type.card = book.card;
-- type=ALL, 驱动表=book(小表), 被驱动表=type(大表)

-- 给book加索引
CREATE INDEX Y ON book(card);
EXPLAIN SELECT * FROM `type` INNER JOIN book ON type.card = book.card;
-- type=ALL(驱动表), ref(被驱动表) ✅

-- 给type也加索引
CREATE INDEX X ON `type`(card);
EXPLAIN SELECT * FROM `type` INNER JOIN book ON type.card = book.card;
-- 两个表都有索引，优化器选择小表作为驱动表
```

**内连接核心结论：**

| 情况                   | 驱动表     | 被驱动表         |
| :--------------------- | :--------- | :--------------- |
| 两个表连接条件都无索引 | 小表       | 大表（全表扫描） |
| 只有一个表有索引       | 无索引的表 | **有索引的表**   |
| 两个表都有索引         | 小表       | 大表             |

### 6.4 JOIN底层原理

#### (1) 简单嵌套循环连接（Simple Nested-Loop Join, SNLJ）

从驱动表A取出1条数据，遍历被驱动表B全部数据，匹配后放入结果集。

| 开销统计     | SNLJ            |
| :----------- | :-------------- |
| 外表扫描次数 | 1               |
| 内表扫描次数 | A（驱动表行数） |
| 读取记录数   | A + B × A       |
| JOIN比较次数 | B × A           |

**结论：让驱动表A数据量更少！即小表驱动大表。**

#### (2) 索引嵌套循环连接（Index Nested-Loop Join, INLJ）

在被驱动表上建立索引，减少内层表数据匹配次数。

| 开销统计     | SNLJ    | INLJ            |
| :----------- | :------ | :-------------- |
| 外表扫描次数 | 1       | 1               |
| 内表扫描次数 | A       | 0               |
| 读取记录数   | A + B×A | A + B(MATCH)    |
| JOIN比较次数 | B×A     | A×Index(Height) |
| 回表次数     | 0       | B(MATCH)        |

**结论：被驱动表加索引，效率大幅提升！**

#### (3) 块嵌套循环连接（Block Nested-Loop Join, BNLJ）

一次性将驱动表A中多条数据（取决于join_buffer_size）和被驱动表B进行遍历匹配。

| 开销统计     | SNLJ  | INLJ            | BNLJ                                        |
| :----------- | :---- | :-------------- | :------------------------------------------ |
| 外表扫描次数 | 1     | 1               | 1                                           |
| 内表扫描次数 | A     | 0               | A × used_column_size / join_buffer_size + 1 |
| 读取记录数   | A+B×A | A+B(match)      | A+B×(A×used_column_size/join_buffer_size)   |
| JOIN比较次数 | B×A   | A×Index(Height) | B×A                                         |
| 回表次数     | 0     | B(match)        | 0                                           |

```
-- 查看join_buffer大小
SHOW VARIABLES LIKE 'join_buffer%';

-- 设置join_buffer大小
SET join_buffer_size = 262144;  -- 256KB
```

#### (4) Hash JOIN（MySQL 8.0.20+）

MySQL 8.0.20之后，Hash Join取代了BNLJ成为默认的等值连接算法。

**特点：**

- 只能用于等值连接（=`）
- 非等值连接仍然使用BNLJ
- 效率高于BNLJ

### 6.5 JOIN优化总结

| 优化策略                 | 说明                                |
| :----------------------- | :---------------------------------- |
| **小表驱动大表**         | 减少外层循环的数据量                |
| **被驱动表加索引**       | 减少内层表的匹配次数                |
| **增大join_buffer_size** | 一次缓存的数据越多，扫表次数越少    |
| **减少驱动表字段**       | 字段越少，join buffer存储的数据越多 |
| **整体效率**             | INLJ > BNLJ > SNLJ                  |

------

## 七、子查询优化

### 7.1 IN子查询优化

```
-- ❌ 子查询（效率较低）
EXPLAIN SELECT * FROM student stu1
WHERE stu1.stuno IN (
    SELECT monitor FROM class c WHERE monitor IS NOT NULL
);
-- 耗时：0.020s

-- ✅ 改为多表连接（效率更高）
EXPLAIN SELECT stu1.* FROM student stu1 
JOIN class c ON stu1.stuno = c.monitor
-- 耗时：0.002s
```

### 7.2 NOT IN优化

```
-- ❌ NOT IN（效率低，且结果可能为空）
EXPLAIN SELECT * FROM student a 
WHERE a.stuno NOT IN (
    SELECT monitor FROM class b WHERE monitor IS NOT NULL
);

-- ✅ 改为 LEFT JOIN ... IS NULL
EXPLAIN SELECT a.*
FROM student a 
LEFT JOIN class b ON a.stuno = b.monitor
WHERE b.monitor IS NULL;
```

**子查询优化原则：**

- 子查询可以转换为连接查询时，优先使用连接查询
- NOT IN → NOT EXISTS 或 LEFT JOIN ... IS NULL
- IN 子查询结果集很大时，考虑使用 EXISTS

------

## 八、排序优化（ORDER BY）

### 8.1 索引排序 vs filesort

| 排序方式     | 条件               | 效率 |
| :----------- | :----------------- | :--- |
| **索引排序** | ORDER BY列在索引中 | ✅ 快 |
| **filesort** | 无法使用索引排序   | ❌ 慢 |

**filesort的两种算法：**

| 算法         | 原理                                    | 适用场景                  |
| :----------- | :-------------------------------------- | :------------------------ |
| **双路排序** | 先取排序字段+主键，排序后再回表取完整行 | 内存小、数据量大          |
| **单路排序** | 一次取出所有查询列，在内存中排序        | 内存大（sort_buffer足够） |

### 8.2 ORDER BY优化案例

#### 案例1：不加LIMIT，索引失效

```
CREATE INDEX idx_age_classid_name ON student(age, classId, name);

-- ❌ 不走索引（优化器认为回表成本太高）
EXPLAIN SELECT * FROM student ORDER BY age, classId;

-- ✅ 加了LIMIT，走索引
EXPLAIN SELECT * FROM student ORDER BY age, classId LIMIT 10;

-- ✅ 覆盖索引，不走回表，走索引
EXPLAIN SELECT age, classId FROM student ORDER BY age, classId;
```

#### 案例2：ORDER BY顺序错误，索引失效

```
-- 联合索引 (age, classId, stuno)

-- ❌ 不走索引（跳过age）
EXPLAIN SELECT * FROM student ORDER BY classId LIMIT 10;

-- ❌ 不走索引（classId在联合索引第二位）
EXPLAIN SELECT * FROM student ORDER BY classId, name LIMIT 10;

-- ✅ 走索引（完全匹配）
EXPLAIN SELECT * FROM student ORDER BY age, classId, stuno LIMIT 10;

-- ✅ 走索引（匹配前缀）
EXPLAIN SELECT * FROM student ORDER BY age, classId LIMIT 10;

-- ✅ 走索引（匹配前缀）
EXPLAIN SELECT * FROM student ORDER BY age LIMIT 10;
```

#### 案例3：ORDER BY方向不一致，索引失效

```
-- 联合索引 (age, classId, name)

-- ❌ 方向不一致（age降序，classId升序）
EXPLAIN SELECT * FROM student ORDER BY age DESC, classId ASC LIMIT 10;

-- ❌ 方向不一致
EXPLAIN SELECT * FROM student ORDER BY classId DESC, name DESC LIMIT 10;

-- ❌ 方向不一致
EXPLAIN SELECT * FROM student ORDER BY age ASC, classId DESC LIMIT 10;

-- ✅ 方向一致（全部降序）
EXPLAIN SELECT * FROM student ORDER BY age DESC, classId DESC LIMIT 10;
```

#### 案例4：无过滤条件，索引失效

```
-- 只有WHERE过滤后数据量小，才会走索引排序

-- ✅ 有WHERE过滤，走索引
EXPLAIN SELECT * FROM student WHERE age = 45 ORDER BY classId;

-- ❌ 无WHERE过滤导致初始数据量太大，不走索引
EXPLAIN SELECT * FROM student ORDER BY age, classId;

-- 但如果过滤后数据量很大，优化器也可能不走索引
-- 优化器会评估：走索引回表的成本 vs 全表扫描+filesort的成本
```

### 8.3 实战案例

```
-- 需求：查询 age=30 且 stuno<101000 的用户，按 name 排序

-- 原始SQL（有filesort）
EXPLAIN SELECT * FROM student WHERE age = 30 AND stuno < 101000 ORDER BY name;
-- Extra: Using filesort ❌

-- 方案一：建索引 (age, name)
CREATE INDEX idx_age_name ON student(age, name);
-- 现在ORDER BY name可以用索引 ✅

-- 方案二：建索引 (age, stuno, name)
CREATE INDEX idx_age_stuno_name ON student(age, stuno, name);
-- 但范围查询（stuno <）会导致 name 索引失效 ⚠️

-- 方案三：建索引 (age, stuno)
CREATE INDEX idx_age_stuno ON student(age, stuno);
-- 利用索引过滤，但排序仍用filesort ⚠️

-- 最佳方案取决于数据分布，需要实际测试
```

### 8.4 排序优化建议

```
-- 增大 sort_buffer_size（临时提高）
SET sort_buffer_size = 2 * 1024 * 1024;  -- 2MB

-- 增大 max_length_for_sort_data（决定用单路还是双路）
SET max_length_for_sort_data = 1024;

-- 生产环境配置（my.cnf）
[mysqld]
sort_buffer_size = 2M
max_length_for_sort_data = 1024
```

------

## 九、覆盖索引

### 9.1 什么是覆盖索引

**覆盖索引：** 查询的字段全部在二级索引中，不需要回表查询原始数据。

```
-- 二级索引包含 (age, name)

-- ❌ 需要回表（SELECT * 包含不在索引中的字段）
EXPLAIN SELECT * FROM student WHERE age = 20;
-- Extra: (无Using index)

-- ✅ 覆盖索引（age和name都在索引中）
EXPLAIN SELECT age, name FROM student WHERE age = 20;
-- Extra: Using index
```

### 9.2 覆盖索引解决索引失效问题

```
-- ❌ 本来索引失效（使用 !=）
EXPLAIN SELECT * FROM student WHERE age != 20;
-- type=ALL

-- ✅ 使用覆盖索引，不走回表，可以使用索引
EXPLAIN SELECT age, name FROM student WHERE age != 20;
-- type=range, Extra: Using where; Using index

-- ❌ LIKE '%abc' 索引失效
EXPLAIN SELECT * FROM student WHERE name LIKE '%abc';
-- type=ALL

-- ✅ 覆盖索引，可以使用索引
EXPLAIN SELECT id, name FROM student WHERE name LIKE '%abc';
-- Extra: Using index
```

### 9.3 覆盖索引的优势

| 优势           | 说明                               |
| :------------- | :--------------------------------- |
| **减少回表**   | 直接从索引返回数据                 |
| **减少I/O**    | 不需要读取数据页                   |
| **索引列较小** | 二级索引通常比数据行小             |
| **统计信息**   | 某些引擎可以只查索引就返回统计结果 |

------

## 十、索引下推（ICP）

### 10.1 什么是索引下推

**索引下推（Index Condition Pushdown, ICP）：** MySQL 5.6引入，在存储引擎层直接过滤WHERE条件，减少回表次数。

### 10.2 无ICP vs 有ICP

**没有ICP时：**

```
存储引擎 → 找到索引记录 → 回表读取完整行 → Server层判断WHERE条件
```

（即使WHERE条件能在索引中过滤，也要先回表）

**有ICP时（MySQL 5.6+）：**

```
存储引擎 → 找到索引记录 → 直接在索引上判断WHERE条件 → 满足条件的才回表
```

（减少了回表次数）

### 10.3 案例演示

```
CREATE TABLE `people` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `zipcode` VARCHAR(20) COLLATE utf8_bin DEFAULT NULL,
    `firstname` VARCHAR(20) COLLATE utf8_bin DEFAULT NULL,
    `lastname` VARCHAR(20) COLLATE utf8_bin DEFAULT NULL,
    `address` VARCHAR(50) COLLATE utf8_bin DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `zip_last_first` (`zipcode`, `lastname`, `firstname`)
);

-- 案例1：LIKE %开头（索引失效，但ICP可以过滤）
EXPLAIN SELECT * FROM people
WHERE zipcode = '000001'
AND lastname LIKE '%张%'      -- 索引失效
AND address LIKE '%北京市%';
-- Extra: Using index condition (ICP生效)
-- ICP把这个遍历和过滤的成本，从昂贵的数据页（回表）转移到了廉价的索引页。

-- 案例2：完全匹配
EXPLAIN SELECT * FROM people
WHERE zipcode = '000001'
AND lastname LIKE '张%'       -- 可以用索引
AND firstname LIKE '三%';
-- 正常使用索引
```

### 10.4 ICP开关

```
-- 查看ICP状态
SHOW VARIABLES LIKE 'optimizer_switch';
-- 找到 index_condition_pushdown=on

-- 开启ICP
SET optimizer_switch = 'index_condition_pushdown=on';

-- 关闭ICP
SET optimizer_switch = 'index_condition_pushdown=off';
```

------

## 十一、EXISTS vs IN 选择

### 11.1 选择原则

```
当 A 表数据量 > B 表数据量 → 用 IN
  SELECT * FROM A WHERE id IN (SELECT id FROM B)

当 A 表数据量 < B 表数据量 → 用 EXISTS
  SELECT * FROM A WHERE EXISTS (SELECT 1 FROM B WHERE B.id = A.id)
```

### 11.2 原理说明

| 写法       | 执行逻辑                                | 适用场景       |
| :--------- | :-------------------------------------- | :------------- |
| **IN**     | 先执行子查询（B表），再与A表匹配        | 子查询结果集小 |
| **EXISTS** | 先执行外层查询（A表），再逐行判断子查询 | 外层结果集小   |

### 11.3 示例

```
-- A表（student）数据量大，B表（class）数据量小
-- 用 IN（子查询结果集小）
SELECT * FROM student WHERE classId IN (SELECT id FROM class);

-- A表（student）数据量小，B表（class）数据量大
-- 用 EXISTS（外层结果集小）
SELECT * FROM student 
WHERE EXISTS (SELECT 1 FROM class WHERE class.id = student.classId);
```

------

## 十二、COUNT查询优化

### 12.1 COUNT性能对比

| 写法                 | InnoDB效率 | 说明                                                         |
| :------------------- | :--------- | :----------------------------------------------------------- |
| `COUNT(*)`           | 快         | 优化器自动选最小的二级索引扫描。只会检查结果集的行数，不会读取任何具体的列值 |
| `COUNT(1)`           | 快         | 和 `COUNT(*)` 几乎一样                                       |
| `COUNT(主键)`        | 较快       | 扫描主键索引。因为主键索引存储整个数据表，因此一次扫描比字段索引慢一些 |
| `COUNT(字段)`        | 最慢       | 扫描该字段的二级索引（如果有），否则全表                     |
| `COUNT(字段)` 有索引 | 快         | 走二级索引，且不取NULL值                                     |

### 12.2 性能对比

```
-- ✅ 推荐：直接用 COUNT(*)
SELECT COUNT(*) FROM orders;  -- 优化器自动选最小索引

-- ✅ 如果只要非NULL的数量，且该字段有索引
SELECT COUNT(indexed_column) FROM orders;

-- ❌ 避免：没有索引的字段
SELECT COUNT(non_indexed_column) FROM orders;

-- ⚠️ MyISAM引擎中 COUNT(*) 是O(1)（有meta统计）
-- InnoDB中 COUNT(*) 是O(n)（需要扫描）
```

------

## 十三、分页查询优化

### 13.1 问题：深分页

```
-- ❌ 越往后翻越慢（OFFSET 100000 需要跳过前10万条）
SELECT * FROM orders ORDER BY id LIMIT 100000, 20;
```

**原因：** MySQL需要先找到前100000条，然后丢弃，只返回20条。OFFSET越大，性能越差。

### 13.2 优化方案1：延迟关联

```
-- ✅ 先查主键，再查数据
SELECT o.* 
FROM orders o
INNER JOIN (
    SELECT id FROM orders ORDER BY id LIMIT 100000, 20
) tmp ON o.id = tmp.id;
```

### 13.3 优化方案2：记录上一页最后一条ID（推荐）

```
-- 上一页最后一条 id = 100000
-- ✅ 用主键过滤，直接定位
SELECT * FROM orders WHERE id > 100000 ORDER BY id LIMIT 20;

-- 多列排序时，记录上一页最后一条的多个列值
-- 上一页最后一条 (id=100000, create_time='2024-01-01 10:00:00')
SELECT * FROM orders 
WHERE create_time > '2024-01-01 10:00:00' 
   OR (create_time = '2024-01-01 10:00:00' AND id > 100000)
ORDER BY create_time, id LIMIT 20;
```

### 13.4 优化方案3：使用子查询

```
-- ✅ 用子查询先过滤主键
SELECT * FROM orders 
WHERE id >= (
    SELECT id FROM orders ORDER BY id LIMIT 100000, 1
) LIMIT 20;
```

------

## 十四、主键设计

### 14.1 推荐方案：自增主键 / 雪花算法

```
-- ✅ 自增主键（简单业务）
CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- ...
);

-- ✅ 雪花算法（分布式环境）
-- 64位ID：41位时间戳 + 10位机器ID + 12位序列号
-- Java/Snowflake实现
```

### 14.2 为什么不建议用UUID做主键？

| 问题             | 说明                                         |
| :--------------- | :------------------------------------------- |
| **无序插入**     | UUID无序，B+树频繁页分裂，性能差             |
| **占用空间大**   | UUID 16字节 vs 自增ID 4-8字节                |
| **影响二级索引** | 二级索引叶子节点存主键值，主键越大，索引越大 |
| **查询性能差**   | 字符串比较比整数比较慢                       |

### 14.3 如果必须用UUID，使用有序UUID

```
-- MySQL 8.0 原生支持
SELECT UUID_TO_BIN(UUID(), TRUE);  -- TRUE表示按时间排序

-- 示例
SET @uuid = UUID();
SELECT @uuid, UUID_TO_BIN(@uuid), UUID_TO_BIN(@uuid, TRUE);
```

### 14.4 淘宝主键设计思路

```
主键 = 时间 + 去重字段 + 用户ID后6位
-- 例如：订单号 = 20240801120000 + 机器ID + 序列号
```

### 14.5 主键设计总结

| 方案                  | 适用场景                   | 推荐度 |
| :-------------------- | :------------------------- | :----- |
| 自增ID（BIGINT）      | 单库单表                   | ⭐⭐⭐⭐⭐  |
| 雪花算法（Snowflake） | 分布式、分库分表           | ⭐⭐⭐⭐⭐  |
| 有序UUID              | 需要UUID但不希望性能差     | ⭐⭐⭐    |
| 普通UUID              | 无性能要求的场景           | ⭐⭐     |
| 业务字段做主键        | **不推荐**（业务可能变更） | ⭐      |

------

## 十五、优化决策优先级

```
1. SQL改写（成本最低，效果最好）← 优先做
   ├── 避免索引列上使用函数
   ├── 避免隐式类型转换
   ├── OR改为UNION或IN
   ├── 分页优化（延迟关联）
   └── NOT IN改为NOT EXISTS或LEFT JOIN

2. 加索引（成本中等，效果显著）
   ├── 根据WHERE条件建索引
   ├── 联合索引注意顺序（最左匹配）
   ├── 覆盖索引减少回表
   └── 把范围查询列放在联合索引最后

3. 表结构优化（成本较高）
   ├── 合理分区（PARTITION）
   ├── 分库分表
   └── 冷热数据分离

4. 系统配置优化（谨慎操作）
   ├── 调整buffer pool大小
   ├── 调整sort_buffer_size
   └── 调整join_buffer_size

5. 硬件升级（最后的选择）
```

------

## 十六、生产环境自查清单

### 🔍 EXPLAIN 检查清单

- □ 

  `type` 是否为 `ALL`？→ 加索引

- □ 

  `type` 是否为 `index`？→ 检查是否可优化为range/ref

- □ 

  `key` 是否为 `NULL`？→ 优化索引使用

- □ 

  `rows` 是否 > 10000？→ 加索引或改写SQL

- □ 

  `Extra` 是否有 `Using filesort`？→ 为ORDER BY列加索引

- □ 

  `Extra` 是否有 `Using temporary`？→ 为GROUP BY列加索引

- □ 

  `Extra` 是否有 `Using index`？→ ✅ 覆盖索引，完美！

- □ 

  `Extra` 是否有 `Using where`？→ 考虑把过滤条件放进索引

- □ 

  `key_len` 是否充分利用了联合索引？→ 检查最左匹配

### 🔧 索引设计检查清单

- □ 

  是否遵循**最左前缀法则**？

- □ 

  WHERE条件中的列是否都建了索引？

- □ 

  ORDER BY中的列是否在索引中？

- □ 

  GROUP BY中的列是否在索引中？

- □ 

  是否使用了**覆盖索引**减少回表？

- □ 

  联合索引中**范围查询**列是否放在最后？

- □ 

  是否避免在索引列上用函数/计算/类型转换？

- □ 

  索引列是否设置为 NOT NULL？（IS NULL可以用，但一般不推荐）

### 📊 SQL写法检查清单

- □ 

  是否用 `SELECT *`？→ 改为只查需要的字段

- □ 

  是否用 `LIKE '%abc'`？→ 改为 `LIKE 'abc%'` 或用覆盖索引

- □ 

  是否用 `OR`？→ 考虑改为 `UNION`

- □ 

  是否用 `IN` 但子查询结果很大？→ 改为 `EXISTS`

- □ 

  是否用 `NOT IN`？→ 改为 `NOT EXISTS` 或 `LEFT JOIN ... IS NULL`

- □ 

  分页是否用了深翻页（OFFSET大）？→ 用延迟关联或记录上一页ID

- □ 

  关联查询是否遵循**小表驱动大表**？

- □ 

  是否在索引列上使用了计算或函数？

### ⚙️ 配置检查清单

- □ 

  慢查询日志是否开启？

- □ 

  `long_query_time` 是否设置合理（1-2秒）？

- □ 

  `log_queries_not_using_indexes` 是否开启？

- □ 

  `join_buffer_size` 是否足够？

- □ 

  `sort_buffer_size` 是否足够？

- □ 

  字符集是否统一为 `utf8mb4`？

------

## 附录：常用SQL优化命令速查

sql

```
-- 1. 查看系统性能参数
SHOW STATUS LIKE 'Connections';      -- 连接次数
SHOW STATUS LIKE 'Uptime';           -- 上线时间
SHOW STATUS LIKE 'Slow_queries';     -- 慢查询次数
SHOW STATUS LIKE 'Innodb_rows_read'; -- SELECT返回行数
SHOW STATUS LIKE 'Com_select';       -- SELECT操作次数
SHOW STATUS LIKE 'Com_insert';       -- INSERT操作次数
SHOW STATUS LIKE 'Com_update';       -- UPDATE操作次数
SHOW STATUS LIKE 'Com_delete';       -- DELETE操作次数

-- 2. 查看最后一条SQL的成本
SELECT * FROM table;
SHOW STATUS LIKE 'last_query_cost';

-- 3. 查看索引信息
SHOW INDEX FROM table_name;

-- 4. 删除索引（存储过程批量删除）
CALL proc_drop_index('dbname', 'tablename');

-- 5. 查看优化器开关
SHOW VARIABLES LIKE '%optimizer_switch%';

-- 6. 查看缓冲区大小
SHOW VARIABLES LIKE '%join_buffer%';
SHOW VARIABLES LIKE '%sort_buffer%';

-- 7. 删除表上所有非主键索引（存储过程示例）
DELIMITER //
CREATE PROCEDURE proc_drop_index(dbname VARCHAR(200), tablename VARCHAR(200))
BEGIN
    DECLARE done INT DEFAULT 0;
    DECLARE _index VARCHAR(200) DEFAULT '';
    DECLARE _cur CURSOR FOR 
        SELECT index_name FROM information_schema.STATISTICS 
        WHERE table_schema = dbname 
          AND table_name = tablename 
          AND seq_in_index = 1 
          AND index_name <> 'PRIMARY';
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 2;
    OPEN _cur;
    FETCH _cur INTO _index;
    WHILE _index <> '' DO
        SET @str = CONCAT("DROP INDEX ", _index, " ON ", tablename);
        PREPARE sql_str FROM @str;
        EXECUTE sql_str;
        DEALLOCATE PREPARE sql_str;
        SET _index = '';
        FETCH _cur INTO _index;
    END WHILE;
    CLOSE _cur;
END //
DELIMITER ;
```