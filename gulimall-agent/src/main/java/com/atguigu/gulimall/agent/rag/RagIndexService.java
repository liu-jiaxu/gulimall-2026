package com.atguigu.gulimall.agent.rag;

import com.atguigu.gulimall.agent.config.AiProperties;
import com.atguigu.gulimall.agent.exception.AgentErrorCode;
import com.atguigu.gulimall.agent.exception.AgentException;
import com.atguigu.gulimall.agent.model.ModelFactory;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * RAG 文档入库与重建。
 *
 * <p>幂等策略：文档内容 hash 作为 marker，内容没变就不重复调 embedding；
 * 重建时先清空 collection，确保本地删除的文档也从 Milvus 移除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagIndexService {

    private static final String MARKER = "gulimall:ai:rag:indexed";
    private static final String LOCK = "gulimall:ai:rag:lock";

    private final AiProperties properties;
    private final RagDocumentLoader documentLoader;
    private final MilvusStoreProvider storeProvider;
    private final ModelFactory modelFactory;
    private final StringRedisTemplate redisTemplate;

    /**
     * 在文档内容变化时执行入库，使用 Redis 标记和分布式锁保证幂等与多实例互斥。
     */
    public void ingestIfNeeded() {
        // 1. 关闭 RAG 时直接跳过
        if (!properties.getRag().isEnabled()) {
            return;
        }

        // 2. 文档没变化就跳过
        String fingerprint = documentLoader.fingerprint();
        String marker = redisTemplate.opsForValue().get(MARKER);
        if (fingerprint.equals(marker)) {
            return;
        }

        // 3. 多实例部署时只让一个实例执行
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(LOCK, "1", Duration.ofMinutes(10));
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        try {
            int count = ingest();
            if (count > 0) {
                redisTemplate.opsForValue().set(MARKER, fingerprint);
            }
        } finally {
            redisTemplate.delete(LOCK);
        }
    }

    /**
     * 管理员手动重建知识库索引，抢到锁后全量重新入库。
     *
     * @return 本次入库的文档片段数
     */
    public int rebuild() {
        // 1. 管理员手动重建：先抢锁
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(LOCK, "1", Duration.ofMinutes(10));
        if (!Boolean.TRUE.equals(locked)) {
            throw new AgentException(AgentErrorCode.RAG_UNAVAILABLE, "RAG 正在重建中，请稍后再试");
        }
        try {
            int count = ingest();
            if (count > 0) {
                redisTemplate.opsForValue().set(MARKER, documentLoader.fingerprint());
            }
            return count;
        } finally {
            redisTemplate.delete(LOCK);
        }
    }

    /**
     * 判断知识库是否已完成过入库。
     *
     * @return 存在入库标记时返回 true
     */
    public boolean isIndexed() {
        return redisTemplate.hasKey(MARKER);
    }

    /**
     * 加载并切分文档，清空旧数据后重新向量化写入 Milvus。
     *
     * @return 本次入库的文档片段数
     */
    private int ingest() {
        // 1. 加载文档
        List<Document> documents = documentLoader.load();
        if (documents.isEmpty()) {
            log.warn("RAG 没有找到可入库的文档（classpath:{}/**/*.md）",
                    properties.getRag().getDocLocation());
            return 0;
        }

        // 2. 按字符切分；片段大小和重叠字符数来自配置
        List<TextSegment> segments = DocumentSplitters
                .recursive(properties.getRag().getChunkMaxChars(),
                        properties.getRag().getChunkOverlapChars())
                .splitAll(documents);
        if (segments.isEmpty()) {
            return 0;
        }

        EmbeddingStore<TextSegment> store = storeProvider.get();

        // 3. 全量重建：先清空 collection。
        //    不能只按“当前文档来源”删除，否则本地删掉某份 md 后，
        //    它在 Milvus 里的旧片段会一直残留，用户端仍可能检索到。
        store.removeAll();

        // 4. 向量化并写入 Milvus
        EmbeddingModel embeddingModel = modelFactory.embedding();
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        store.addAll(embeddings, segments);
        log.info("RAG 入库完成：文档 {} 个，片段 {} 个", documents.size(), segments.size());
        return segments.size();
    }
}
