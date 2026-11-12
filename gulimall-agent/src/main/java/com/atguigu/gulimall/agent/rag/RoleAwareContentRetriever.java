package com.atguigu.gulimall.agent.rag;

import com.atguigu.gulimall.agent.auth.Principal;
import com.atguigu.gulimall.agent.config.AiProperties;
import com.atguigu.gulimall.agent.exception.AgentException;
import com.atguigu.gulimall.agent.model.ModelFactory;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按角色返回 RAG 检索器：
 * 用户只能检索 PUBLIC 文档，管理员可以检索 PUBLIC + ADMIN。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoleAwareContentRetriever {

    private final AiProperties properties;
    private final MilvusStoreProvider storeProvider;
    private final ModelFactory modelFactory;

    private volatile ContentRetriever userRetriever;
    private volatile ContentRetriever adminRetriever;

    /**
     * 按当前身份返回对应的检索器，RAG 关闭或 Milvus 不可用时返回 null 以便降级为纯对话。
     *
     * @param principal 当前请求身份，用于判断可检索的文档范围
     * @return 对应身份的检索器，不可用时返回 null
     */
    public ContentRetriever forPrincipal(Principal principal) {
        // 1. RAG 关闭或 Milvus 不可用时返回 null，AiServices 不挂检索器
        if (!properties.getRag().isEnabled()) {
            return null;
        }
        try {
            return principal.isAdmin() ? adminRetriever() : userRetriever();
        } catch (AgentException ex) {
            log.warn("RAG 检索器不可用，降级为纯对话：{}", ex.getMessage());
            return null;
        } catch (Exception ex) {
            log.warn("RAG 检索器初始化失败，降级为纯对话：{}", ex.getMessage());
            return null;
        }
    }

    /**
     * 获取用户端检索器，仅能检索 PUBLIC 文档，并做懒加载缓存。
     *
     * @return 用户端可用的检索器
     */
    private ContentRetriever userRetriever() {
        // 1. 用户端只查 PUBLIC
        if (userRetriever != null) {
            return userRetriever;
        }
        synchronized (this) {
            if (userRetriever == null) {
                userRetriever = build(List.of("PUBLIC"));
            }
            return userRetriever;
        }
    }

    /**
     * 获取管理端检索器，可检索 PUBLIC 和 ADMIN 文档，并做懒加载缓存。
     *
     * @return 管理端可用的检索器
     */
    private ContentRetriever adminRetriever() {
        // 1. 管理端可查 PUBLIC + ADMIN
        if (adminRetriever != null) {
            return adminRetriever;
        }
        synchronized (this) {
            if (adminRetriever == null) {
                adminRetriever = build(List.of("PUBLIC", "ADMIN"));
            }
            return adminRetriever;
        }
    }

    /**
     * 按可见范围构建 Milvus 检索器，并包装为异常安全的检索器。
     *
     * @param audiences 允许检索的文档可见范围集合
     * @return 带可见范围过滤的检索器
     */
    private ContentRetriever build(List<String> audiences) {
        AiProperties.Rag rag = properties.getRag();
        ContentRetriever delegate = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(storeProvider.get())
                .embeddingModel(modelFactory.embedding())
                .maxResults(rag.getMaxResults())
                .minScore(rag.getMinScore())
                .filter(MetadataFilterBuilder.metadataKey("audience").isIn(audiences))
                .build();

        // 检索异常时返回空结果，不让整个聊天失败
        return new SafeContentRetriever(delegate);
    }
}
