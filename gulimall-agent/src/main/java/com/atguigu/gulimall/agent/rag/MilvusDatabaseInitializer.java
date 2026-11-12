package com.atguigu.gulimall.agent.rag;

import com.atguigu.gulimall.agent.config.AiProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.ListDatabasesResponse;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateDatabaseParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 确保 Milvus 数据库存在。
 *
 * <p>MilvusEmbeddingStore 只会自动建 collection，不会建 database，
 * 所以第一次启动前必须先用原生 SDK 把 gulimall_ai 库建出来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusDatabaseInitializer {

    private final AiProperties properties;

    /**
     * 确保配置的 Milvus 数据库存在，不存在时创建，已存在则直接返回。
     */
    public void ensureDatabase() {
        AiProperties.Rag rag = properties.getRag();
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(rag.getHost())
                .withPort(rag.getPort());
        // 只有用户名和密码都配置了才开鉴权；Milvus 默认未开鉴权时不能带空密码
        if (StringUtils.hasText(rag.getUsername()) && StringUtils.hasText(rag.getPassword())) {
            builder.withAuthorization(rag.getUsername(), rag.getPassword());
        }

        MilvusServiceClient client = null;
        try {
            // 1. 连 Milvus 默认库，先查数据库是否已存在
            client = new MilvusServiceClient(builder.build());
            R<ListDatabasesResponse> databases = client.listDatabases();
            if (databases.getStatus() != null && databases.getStatus() == 0
                    && databases.getData() != null
                    && databases.getData().getDbNamesList().contains(rag.getDatabase())) {
                return;
            }

            // 2. 不存在才创建，避免重复启动时 Milvus SDK 打 ERROR 日志
            R<RpcStatus> result = client.createDatabase(CreateDatabaseParam.newBuilder()
                    .withDatabaseName(rag.getDatabase())
                    .build());

            // 3. status=0 表示创建成功；已存在时忽略（并发启动的兜底）
            if (result.getStatus() == null || result.getStatus() != 0) {
                String message = result.getMessage() == null ? "" : result.getMessage();
                if (!message.toLowerCase().contains("already exist")) {
                    log.warn("创建 Milvus 数据库返回非成功状态：{}", message);
                }
            }
        } catch (Exception ex) {
            // 3. 交给上层决定是否降级；不要在启动时把整个应用拖崩
            throw new IllegalStateException("Milvus 数据库初始化失败：" + ex.getMessage(), ex);
        } finally {
            if (client != null) {
                try {
                    client.close(0);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
