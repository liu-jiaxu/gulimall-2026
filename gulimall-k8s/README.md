# gulimall-k8s

独立 AI 对话服务，用于练习 Kubernetes + Jenkins CI/CD。

本模块的特点：

- 父模块是 `spring-boot-starter-parent`，不是 gulimall；
- 不在根 `pom.xml` 的 `<modules>` 里；
- 不依赖 `gulimall-common`、`gulimall-api` 或任何其它业务模块；
- 不注册 Nacos，不调用 OpenFeign；
- 只依赖 MySQL、Redis 和 OpenAI 兼容模型服务。

## 技术栈

| 组件 | 版本/说明 |
| --- | --- |
| Java | 21 |
| Spring Boot | 3.5.16 |
| LangChain4J | 1.0.1 |
| MySQL | 8.x |
| Redis | 7.x |
| 前端 | 原生 HTML/CSS/JS |

## 功能

- 注册、登录、退出；
- 登录和注册都必须输入邀请码，邀请码来自 `INVITE_CODE` 环境变量；
- 登录态最长 12 小时，绝对过期，到点页面自动回登录页；
- 忘记密码直接改密（无短信/邮箱验证，练习用）；
- 单页聊天界面；
- 普通对话和 SSE 流式对话；
- 会话列表、历史消息；
- Redis 会话/消息/模型记忆滑动过期 3 天；
- 模型下拉框，默认模型来自数据库 `ai_default_config`；
- 没有工具调用、没有 RAG、没有图片生成，只做纯文本问答。

## 数据库初始化

```bash
mysql -uroot -p < gulimall-k8s/sql/init.sql
```

数据库名：`gulimall_k8s_ai`。

两张表：

| 表 | 用途 |
| --- | --- |
| `ai_user` | 用户信息、BCrypt 密码哈希 |
| `ai_default_config` | 默认模型、系统提示词 |

默认管理员由应用首次启动时创建：

- 用户名：`admin`
- 密码：`<your-password>`
- 默认邀请码：`<your-invite-code>`

账号、密码、邀请码在 `application.yaml` 的 `gulimall.k8s.auth` 下修改。

## 配置

配置文件：`src/main/resources/application.yaml`。

常用环境变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MYSQL_HOST` | `<your-k8s-master-ip>` | MySQL 地址 |
| `MYSQL_PORT` | `3306` | MySQL 端口 |
| `MYSQL_DATABASE` | `gulimall_k8s_ai` | 数据库名 |
| `MYSQL_USERNAME` | `root` | MySQL 用户名 |
| `MYSQL_PASSWORD` | `<your-password>` | MySQL 密码 |
| `REDIS_HOST` | `<your-k8s-master-ip>` | Redis 地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `REDIS_PASSWORD` | `<your-password>` | Redis 密码 |
| `INVITE_CODE` | `<your-invite-code>` | 登录/注册邀请码 |
| `AI_BASE_URL` | `https://api.openai-proxy.org/v1` | OpenAI 兼容模型地址 |
| `AI_API_KEY` | 配置文件里的默认值 | 模型密钥；部署时务必用环境变量覆盖 |

## 本地启动

```bash
cd gulimall-k8s
mvn clean package
java -jar target/gulimall-k8s-1.0.0.jar
```

或者：

```bash
cd gulimall-k8s
mvn spring-boot:run
```

浏览器打开：

```text
http://localhost:18080
```

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/register` | 注册，必须带邀请码；用户名已存在返回 409 |
| POST | `/api/auth/login` | 登录，必须带邀请码 |
| POST | `/api/auth/logout` | 退出 |
| GET | `/api/auth/me` | 当前用户和登录时间 |
| POST | `/api/auth/reset-password` | 忘记密码，按用户名直接改密 |
| GET | `/api/models` | 模型列表和默认模型 |
| GET | `/api/conversations` | 会话列表 |
| POST | `/api/conversations` | 新建会话 |
| GET | `/api/conversations/{id}/messages` | 会话消息 |
| DELETE | `/api/conversations/{id}` | 删除会话 |
| POST | `/api/chat` | 普通对话 |
| POST | `/api/chat/stream` | SSE 流式对话 |

注册请求体：

```json
{
  "username": "用户名",
  "password": "密码",
  "displayName": "可选展示名",
  "inviteCode": "登录/注册邀请码"
}
```

登录请求体：

```json
{
  "username": "用户名",
  "password": "密码",
  "inviteCode": "登录/注册邀请码"
}
```

聊天请求体：

```json
{
  "conversationId": "可选，为空时新建会话",
  "modelId": "可选，为空时使用默认模型",
  "message": "用户消息"
}
```

SSE 事件：

```text
event:meta   data:{"conversationId":"...","messageId":"...","modelId":"..."}
event:delta  data:{"content":"一段 token"}
event:done   data:{"conversationId":"...","messageId":"...","modelId":"...","inputTokens":1,"outputTokens":1}
event:error  data:{"code":500,"message":"..."}
```

## Redis Key

| Key | 类型 | 说明 |
| --- | --- | --- |
| `gulimall:k8s:ai:conv:index:{userId}` | ZSET | 用户会话索引，score 为最后活跃时间 |
| `gulimall:k8s:ai:conv:meta:{convId}` | HASH | 会话元数据 |
| `gulimall:k8s:ai:conv:msg:{convId}` | LIST | 完整消息 |
| `gulimall:k8s:ai:memory:{convId}` | STRING | 模型记忆窗口 |
| `gulimall:k8s:ai:lock:{convId}` | STRING | 会话处理锁，5 分钟自动过期 |

以上 key 每次写入都会续期 3 天。

## 注意

- 登录态用的是服务端 `HttpSession` + `JSESSIONID`，没有 JWT，也没有自定义 token；
- 登录态是绝对 12 小时：从登录成功开始计时，即使一直有请求，超过 12 小时也会强制重新登录；
- 登录和注册每次都必须输入邀请码；邀请码不会写进 Session，前端也不会保存；
- 改密接口没有验证码，知道用户名就能改密码；
- 模型密钥默认写在 `application.yaml`，部署时请用 `AI_API_KEY` 环境变量覆盖；
- 本模块只用于学习和 CI/CD 练习，不要直接当生产系统使用。
