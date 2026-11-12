package com.atguigu.gulimall.agent.rag;

import com.atguigu.gulimall.agent.config.AiProperties;
import com.atguigu.gulimall.agent.exception.AgentErrorCode;
import com.atguigu.gulimall.agent.exception.AgentException;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 延迟创建 MilvusEmbeddingStore。
 *
 * <p>不把它做成启动期 Bean：Milvus 是新增的中间件，
 * 没启动时聊天/工具仍然要能用，只有 RAG 检索降级。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusStoreProvider {

    private final AiProperties properties;
    private final MilvusDatabaseInitializer databaseInitializer;

    private volatile MilvusEmbeddingStore store;
    /**
     * Milvus 连接失败后的冷却时间，避免每个聊天请求都等 10 秒超时
     */
    private volatile long nextRetryAt = 0;

    /**
     * 获取 Milvus 向量库，延迟创建并缓存；创建失败后进入冷却期，冷却期内快速失败。
     *
     * @return 可用的 Milvus 向量库对象
     */
    public MilvusEmbeddingStore get() {
        // 1. 已创建直接返回
        if (store != null) {
            return store;
        }

        // 2. 刚失败过就快速失败，等冷却时间到了再重试
        if (System.currentTimeMillis() < nextRetryAt) {
            throw new AgentException(AgentErrorCode.RAG_UNAVAILABLE, "Milvus 暂不可用，稍后重试");
        }

        synchronized (this) {
            // 2. 双重检查，避免并发重复建连接
            if (store != null) {
                return store;
            }
            AiProperties.Rag rag = properties.getRag();
            if (!rag.isEnabled()) {
                throw new AgentException(AgentErrorCode.RAG_UNAVAILABLE, "RAG 已关闭");
            }

            try {
                // 3. 建库 + 建 store（collection 由 store 自动创建）
                databaseInitializer.ensureDatabase();
                MilvusEmbeddingStore.Builder builder = MilvusEmbeddingStore.builder()
                        .host(rag.getHost())
                        .port(rag.getPort())
                        .databaseName(rag.getDatabase())
                        .collectionName(rag.getCollection())
                        .dimension(rag.getDimension())
                        .autoFlushOnInsert(true);
                // 只有用户名和密码都配置了才带鉴权信息
                if (StringUtils.hasText(rag.getUsername()) && StringUtils.hasText(rag.getPassword())) {
                    builder.username(rag.getUsername()).password(rag.getPassword());
                }
                store = builder.build();
                nextRetryAt = 0;
                log.info("Milvus 向量库已连接：{}:{}/{}", rag.getHost(), rag.getPort(), rag.getDatabase());
                return store;
            } catch (AgentException ex) {
                nextRetryAt = System.currentTimeMillis() + 60_000;
                throw ex;
            } catch (Exception ex) {
                nextRetryAt = System.currentTimeMillis() + 60_000;
                throw new AgentException(AgentErrorCode.RAG_UNAVAILABLE,
                        "Milvus 不可用：" + ex.getMessage(), ex);
            }
        }
    }
}
