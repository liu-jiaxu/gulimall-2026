# 临时访问 MySQL 与 Redis：NodePort 方式

## 一、适用场景

MySQL 和 Redis 默认通过 ClusterIP Service 只在集群内部访问，Windows 上的 Navicat、DBeaver、RedisInsight 无法直接连接。

NodePort 会在节点 `<your-k8s-master-ip>` 上开放一个临时端口，把请求转发到 Pod。适合调试、导数据、看表结构，不适合长期暴露。

> **命令说明：** 以下命令分别给 MySQL Pod 和 Redis Pod 创建 NodePort Service。Service 通过 Pod 的标签选择后端，Windows 访问节点 IP 和分配的 NodePort 即可到达 Pod。

```bash
kubectl -n gulimall-k8s expose pod mysql-0 --name=mysql-nodeport --type=NodePort --port=3306 --target-port=3306
kubectl -n gulimall-k8s expose pod redis-0 --name=redis-nodeport --type=NodePort --port=6379 --target-port=6379
```

> **命令说明：** 这条命令读取两个 Service 的实际状态，重点看 `PORT(S)` 列里的 `3306:xxxxx/TCP` 和 `6379:xxxxx/TCP`，冒号后面的就是随机分配的 NodePort。

```bash
kubectl -n gulimall-k8s get svc mysql-nodeport redis-nodeport
```

输出示例：

> **命令说明：** 这是 Service 的预期输出，重点看 PORT(S) 列中冒号后的 NodePort；实际端口以当前 `kubectl get svc` 的结果为准。

```text
NAME             TYPE       CLUSTER-IP     EXTERNAL-IP   PORT(S)          AGE
mysql-nodeport   NodePort   10.96.x.x      <none>        3306:31234/TCP   10s
redis-nodeport   NodePort   10.96.x.x      <none>        6379:31567/TCP   10s
```

## 二、Windows 连接信息

| 服务 | 地址 | 账号 |
| --- | --- | --- |
| MySQL | `<your-k8s-master-ip>:<mysql NodePort>` | `root` / `<your-password>`，库 `gulimall_k8s_ai` |
| Redis | `<your-k8s-master-ip>:<redis NodePort>` | 密码 `<your-password>`，库 `0` |

NodePort 是随机分配的，每次删除重建都可能变化，连接前先执行 `kubectl get svc` 确认。

## 三、GUI 工具

MySQL：

- DBeaver Community：免费，跨平台；
- Navicat：商业软件；
- DataGrip：商业软件；
- HeidiSQL：Windows 免费工具。

Redis：

- RedisInsight：Redis 官方图形工具；
- Another Redis Desktop Manager：开源跨平台；
- RESP.app：商业软件。

## 四、连接前确认 Pod 状态

> **命令说明：** 这条命令确认 MySQL 和 Redis 的 Pod 都是 Running 且 Ready。Pod 没有启动时，NodePort 连接会失败。

```bash
kubectl -n gulimall-k8s get pod mysql-0 redis-0
```

正常结果：

> **命令说明：** 这是 Pod 的预期输出，READY 必须是 1/1，STATUS 必须是 Running；否则 NodePort 连接会失败。

```text
NAME      READY   STATUS    RESTARTS   AGE
mysql-0   1/1     Running   0          10m
redis-0   1/1     Running   0          10m
```

## 五、MySQL 远程账号

MySQL 官方镜像默认只创建 `root@localhost`。应用 Pod、NodePort 或 Windows 客户端从其他地址连接时，需要 `root@'%'`。

> **命令说明：** 这条 SQL 在 MySQL Pod 内执行，创建允许任意来源连接的 root 账号，并授予全部权限。只在实验环境使用，生产环境应创建最小权限的业务账号。

```bash
kubectl -n gulimall-k8s exec -it mysql-0 -- mysql -uroot -p<your-password> -e \
  "CREATE USER IF NOT EXISTS 'root'@'%' IDENTIFIED BY '<your-password>'; GRANT ALL PRIVILEGES ON *.* TO 'root'@'%' WITH GRANT OPTION; FLUSH PRIVILEGES;"
```

如果连接报：

> **命令说明：** 这是 MySQL 未授权远程访问时的典型错误，说明 `root@'%'` 还没有创建，需要执行上一节的 SQL。

```text
Access denied for user 'root'@'<your-k8s-master-ip>'
```

说明 `root@'%'` 还没有创建，执行上面的 SQL 后再试。

## 六、命令行验证连接

> **命令说明：** 这条命令从 Windows 直接连接 MySQL，执行一条查询确认网络、账号和数据库都正常。`-P` 后面的端口换成 `kubectl get svc` 里看到的 NodePort。

```cmd
mysql -h<your-k8s-master-ip> -P31234 -uroot -p<your-password> -e "SHOW DATABASES;"
```

> **命令说明：** 这条命令从 Windows 连接 Redis，`PING` 返回 `PONG` 说明端口、密码都正确。`-p` 后面的端口换成实际 NodePort。

```cmd
"D:\SoftwareInstallation\Redis-x64-5.0.14.1\redis-cli.exe" -h <your-k8s-master-ip> -p 31567 -a <your-password> PING
```

## 七、不使用时删除

> **命令说明：** 这两条 Service 只用于临时调试。调试完成后删除，避免数据库端口长期暴露在局域网。

```bash
kubectl -n gulimall-k8s delete svc mysql-nodeport redis-nodeport
```

## 八、常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| Connection refused | Pod 未 Running，或 NodePort 未创建 | `kubectl get pod`、`kubectl get svc` |
| 连接超时 | 虚拟机 firewalld 拦截 NodePort | 按部署文档关闭 firewalld，或放行对应 NodePort |
| MySQL 拒绝访问 | 没有 `root@'%'` | 执行第五节 SQL |
| MySQL 密码错误 | Secret 或手动账号密码不一致 | 用 `kubectl exec` 进入 MySQL 核对 |
| Redis 认证失败 | 密码不是 `<your-password>` | 查看 Secret `gulimall-k8s-secrets` 的 `redis-password` |
| NodePort 连接不到 | Windows 和虚拟机不在同一网段 | 检查 `<your-k8s-master-ip>` 的 ens36 网卡和路由 |

## 九、生产环境说明

NodePort 直接暴露数据库只适合实验环境。生产环境通常采用：

1. 数据库不暴露公网，只允许堡垒机或应用集群访问；
2. 开发人员通过堡垒机、VPN、云 DMS 连接，使用只读账号；
3. 大查询走只读副本、数据仓库或 BI 平台；
4. 所有操作有审批、审计和会话记录；
5. 配置文件通过参数组、Nacos、ConfigMap、Secret 或 GitOps 管理，不登录服务器手工修改。
