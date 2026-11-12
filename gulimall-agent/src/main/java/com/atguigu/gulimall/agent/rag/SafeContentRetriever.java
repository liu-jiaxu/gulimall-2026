package com.atguigu.gulimall.agent.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * RAG 检索的兜底包装。
 *
 * <p>Milvus 短暂抖动、collection 被删、过滤条件不支持时，
 * 不能因为“知识库查不到”就让整个聊天失败；这里捕获异常并返回空结果，
 * 模型仍会基于工具和自身知识回答。
 */
@Slf4j
public class SafeContentRetriever implements ContentRetriever {

    private final ContentRetriever delegate;

    /**
     * 包装真实的检索器，在检索异常时返回空结果。
     *
     * @param delegate 实际执行检索的检索器
     */
    public SafeContentRetriever(ContentRetriever delegate) {
        this.delegate = delegate;
    }

    /**
     * 执行检索，捕获异常后返回空结果，避免知识库故障导致聊天失败。
     *
     * @param query 用户检索查询
     * @return 检索到的内容列表，异常时为空列表
     */
    @Override
    public List<Content> retrieve(Query query) {
        try {
            return delegate.retrieve(query);
        } catch (Exception ex) {
            log.warn("RAG 检索失败，本次按无知识库结果处理：{}", ex.getMessage());
            return List.of();
        }
    }
}
