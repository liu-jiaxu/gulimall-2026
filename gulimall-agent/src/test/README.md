# gulimall-agent 集成测试说明

测试目录按 README 第三节的流程编号组织，一个流程一个测试类。测试连接真实 MySQL、Redis、Nacos、MinIO、Milvus、外部模型和天气接口，不使用 Mock 替换业务 Bean。

## 运行前提

1. 启动虚拟机上的 MySQL、Redis、Nacos、MinIO、Milvus。
2. 启动测试会调用的业务微服务：`gulimall-product`、`gulimall-search`、`gulimall-order`、`gulimall-member`、`gulimall-ware`、`gulimall-seckill`、`gulimall-third-party`。
3. 确认 `config/common.yaml` 中的 MySQL、Redis、MinIO、Milvus、AI Key、天气 AppCode 可用。
4. 外部模型服务 `https://api.openai-proxy.org/v1` 可访问。

## 测试配置

`src/test/resources/application-test.yaml`：

- 连接真实中间件。
- 关闭 Nacos 注册，避免测试实例污染正在运行的 agent。
- 关闭启动时自动 RAG 入库，由 3.8 测试显式调用。
- `AGENT_JWT_COOKIE_SECURE: false`，只作用于测试进程，不修改 `config/common.yaml`。
- JWT 公钥路径由 `AgentIntegrationTestSupport` 用绝对路径注入。

## 执行命令

默认 `mvn test` 不跑集成测试（Surefire 排除了 `integration` 标签）。

运行全部流程测试：

```text
mvn -o -pl gulimall-agent -am test -Dsurefire.excludedGroups= -Dtest="Flow*Test" -Dsurefire.failIfNoSpecifiedTests=false
```

只运行某个流程：

```text
mvn -o -pl gulimall-agent -am test -Dsurefire.excludedGroups= -Dtest=Flow33ChatTest -Dsurefire.failIfNoSpecifiedTests=false
```

只运行某个方法：

```text
mvn -o -pl gulimall-agent -am test -Dsurefire.excludedGroups= -Dtest="Flow311WeatherAndToolsTest#weatherToolFallsBackToWttrAndHandlesBadInput" -Dsurefire.failIfNoSpecifiedTests=false
```

## 测试启动项

### Spring 配置类

测试通过 `@SpringBootTest` 启动完整 agent 上下文，以下配置类和组件会自动加载，不需要在测试前手动启动：

- `GulimallAgentApplication`
- `AiProperties`、`AgentJwtProperties`
- `ModelCatalog`、`ModelFactory`
- `WebMvcConfig`、`PrincipalArgumentResolver`、`PrincipalResolver`
- `FeignInternalTokenConfig`
- `MinioConfig`、`ImageTaskExecutorConfig`
- `RagBootstrapRunner`（测试配置关闭了启动时自动入库）
- `ConversationService`、`SettingsService`、`AssistantFactory`、`ChatOrchestrator`
- `ToolBundleFactory` 及各 Tool 类

### 各测试类需要启动的外部服务

所有测试类都通过 `@SpringBootTest` 加载完整 agent 上下文，因此 MySQL、Redis、Nacos、MinIO、Milvus 必须全部可用。下表只列各流程额外依赖的模型或业务服务。

| 测试类 | 额外依赖 | 说明 |
|---|---|---|
| `Flow31StartupConfigTest` | 无 | 测试本身会读取 MinIO 和 Milvus |
| `Flow32AgentJwtAuthTest` | 无 | 只需要仓库中的 JWT 密钥文件 |
| `Flow33ChatTest` | AI | 提示词不触发业务工具 |
| `Flow34StreamStopTest` | AI | 提示词不触发业务工具 |
| `Flow35ConversationMemorySearchTest` | 无 | 只使用 Redis |
| `Flow36ImageUploadVisionTest` | 视觉模型（AI） | 测试本身会使用 MinIO |
| `Flow37ImageTaskTest` | 图片模型（AI） | 测试本身会使用 MinIO |
| `Flow38RagTest` | embedding 模型（AI） | 测试本身会使用 Milvus |
| `Flow39SettingsModelTest` | AI | 测试本身会使用 MySQL |
| `Flow311WeatherAndToolsTest` | AI、天气接口；按方法启动对应业务服务 | 见下表 |

`Flow311WeatherAndToolsTest` 按测试方法拆分：

| 测试方法 | 需要启动的业务服务 |
|---|---|
| 天气直调、天气工具反射调用、模型调用天气 | 无 |
| 购物车工具 | 无，直接读 Redis |
| 管理端仓库、商品、SKU、分类、品牌、库存 | `gulimall-product`、`gulimall-ware` |
| 管理端订单检索、订单统计 | `gulimall-order` |
| 管理端会员概要 | `gulimall-member` |
| 用户端会员资料、地址、优惠券、积分 | `gulimall-member` |
| 用户端本人订单 | `gulimall-order` |
| ES 搜索 | `gulimall-search` |
| 秒杀首页、场次、场次商品 | `gulimall-seckill` |
| 百度地图正逆地理编码 | `gulimall-third-party` |

测试不需要启动：

- `gulimall-auth-server`：测试基类直接用真实私钥签发用户 JWT
- `renren-fast`：测试基类直接用真实私钥签发管理端 JWT
- `gulimall-gateway`：测试直接访问 agent 的随机端口，不走网关
- `gulimall-cart`：购物车工具直接读 Redis
- `gulimall-coupon`：agent 不直接调用 coupon 服务

## 测试类

| 测试类 | 对应流程 | 覆盖内容 | 额外依赖 |
|---|---|---|---|
| `Flow31StartupConfigTest` | 3.1 启动与配置加载 | 配置绑定、模型目录、模型工厂、RAG 文档加载、JWT 公钥、MinIO、线程池 | 无 |
| `Flow32AgentJwtAuthTest` | 3.2 JWT 鉴权 | 用户/管理员验签、Cookie 选择、15001/15012、角色/签发方校验 | 无 |
| `Flow33ChatTest` | 3.3 普通对话 | prepare、锁、消息落库、模型调用、AI 消息保存、空消息、忙会话 | AI |
| `Flow34StreamStopTest` | 3.4 流式与停止 | SSE meta/delta/done/error、抢锁失败、停止标记、停止后保存部分内容 | AI |
| `Flow35ConversationMemorySearchTest` | 3.5 会话与记忆 | 新建、列表、分页、会话内搜索、全局搜索、删除、归属校验、Redis 记忆、上限淘汰 | Redis |
| `Flow36ImageUploadVisionTest` | 3.6 图片上传与看图 | PNG 上传、元数据、超大图缩放、非法文件、视觉识别、跨身份使用 | MinIO、视觉模型 |
| `Flow37ImageTaskTest` | 3.7 图片异步生成 | 提示词拦截、任务提交、状态轮询、任务列表、会话消息回写、任务归属 | 图片模型、MinIO |
| `Flow38RagTest` | 3.8 RAG | 文档加载、audience、指纹、Milvus 连接、入库、重建、状态、按角色检索 | Milvus、embedding |
| `Flow39SettingsModelTest` | 3.9 设置与模型 | 设置读写、非法主题色、自定义模型 CRUD、AES-GCM、URL 安全校验、模型探测 | MySQL、AI |
| `Flow311WeatherAndToolsTest` | 3.11 天气与工具 | 天气主接口回退、空参数、工具体反射调用、模型触发天气工具、管理端仓库/商品/库存、订单/会员、搜索/秒杀/物流、购物车 | AI、天气、相关微服务 |

## 测试数据

- 测试身份固定为 `USER:990001` 和 `ADMIN:990002`。
- 每个测试结束由 `AgentIntegrationTestSupport.cleanupTestData` 清理：
  - Redis：测试会话的 `meta/msg/memory/lock/stream/stop`、图片元数据、图片任务、会话索引。
  - MySQL：`gulimall_ai.ai_user_setting`、`gulimall_ai.ai_custom_model` 中测试身份的行。
  - MinIO：`ai/upload/USER/990001/`、`ai/upload/ADMIN/990002/`、`ai/generated/USER/990001/`、`ai/generated/ADMIN/990002/` 下的对象。
- 工具只读测试使用数据库中已有的真实 SKU、SPU、会员和订单，不修改业务数据。

## 注意事项

- `Flow38RagTest` 会清空并重建 Milvus collection，只在允许重建知识库时执行。
- `Flow37ImageTaskTest` 会真实调用图片模型，单次通常需要数十秒到数分钟，并产生模型费用。
- `Flow311WeatherAndToolsTest` 中依赖业务微服务的方法，在微服务未启动时会失败；天气、购物车方法不依赖业务微服务。
