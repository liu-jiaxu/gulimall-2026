package com.atguigu.gulimall.agent.flow;

import com.atguigu.gulimall.agent.rag.MilvusDatabaseInitializer;
import com.atguigu.gulimall.agent.rag.MilvusStoreProvider;
import com.atguigu.gulimall.agent.rag.RagDocumentLoader;
import com.atguigu.gulimall.agent.rag.RagIndexService;
import com.atguigu.gulimall.agent.rag.RoleAwareContentRetriever;
import com.atguigu.gulimall.agent.support.AgentIntegrationTestSupport;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 3.8：RAG 知识库。
 */
@Tag("integration")
class Flow38RagTest extends AgentIntegrationTestSupport {

    @Autowired
    private RagDocumentLoader documentLoader;

    @Autowired
    private RagIndexService ragIndexService;

    @Autowired
    private RoleAwareContentRetriever roleAwareContentRetriever;

    @Autowired
    private MilvusStoreProvider storeProvider;

    @Autowired
    private MilvusDatabaseInitializer databaseInitializer;

    /**
     * 测试 RAG 文档加载、PUBLIC/ADMIN 可见范围元数据和内容指纹稳定性。
     */
    @Test
    void documentsLoadAndHaveAudienceMetadata() {
        // 1. 扫描 classpath:rag/**/*.md
        List<Document> documents = documentLoader.load();
        assertFalse(documents.isEmpty());

        // 2. 至少有一份 PUBLIC 文档和一份 ADMIN 文档
        boolean hasPublic = documents.stream().anyMatch(doc ->
                "PUBLIC".equals(String.valueOf(doc.metadata().toMap().get("audience"))));
        boolean hasAdmin = documents.stream().anyMatch(doc ->
                "ADMIN".equals(String.valueOf(doc.metadata().toMap().get("audience"))));
        assertTrue(hasPublic);
        assertTrue(hasAdmin);

        // 3. 指纹稳定且非空
        String first = documentLoader.fingerprint();
        String second = documentLoader.fingerprint();
        assertFalse(first.isBlank());
        assertTrue(first.equals(second));
    }

    /**
     * 测试 Milvus 连接、RAG 全量重建、幂等入库，以及用户端/管理端按角色检索。
     */
    @Test
    void rebuildIngestAndRetrieveByRole() {
        // 1. Milvus 数据库和 collection 可用
        databaseInitializer.ensureDatabase();
        assertNotNull(storeProvider.get());

        // 2. 全量重建索引
        int count = ragIndexService.rebuild();
        assertTrue(count > 0, "RAG 没有入库任何片段");
        assertTrue(ragIndexService.isIndexed());

        // 3. 再执行一次幂等入库，不应报错
        ragIndexService.ingestIfNeeded();

        // 4. 找一段 PUBLIC 文档作为检索样本
        String publicSample = documentLoader.load().stream()
                .filter(doc -> "PUBLIC".equals(
                        String.valueOf(doc.metadata().toMap().get("audience"))))
                .map(Document::text)
                .filter(text -> text != null && text.length() > 80)
                .map(text -> text.substring(0, 80))
                .findFirst()
                .orElse("谷粒商城");

        // 5. 用户端只能检索 PUBLIC
        ContentRetriever userRetriever = roleAwareContentRetriever.forPrincipal(TEST_USER);
        assertNotNull(userRetriever);
        List<Content> userContents = userRetriever.retrieve(Query.from(publicSample));
        assertNotNull(userContents);
        assertFalse(userContents.isEmpty(), "用户端没有检索到 PUBLIC 文档");

        // 6. 管理端可以检索 PUBLIC + ADMIN
        ContentRetriever adminRetriever = roleAwareContentRetriever.forPrincipal(TEST_ADMIN);
        assertNotNull(adminRetriever);
        List<Content> adminContents = adminRetriever.retrieve(Query.from(publicSample));
        assertNotNull(adminContents);
        assertFalse(adminContents.isEmpty(), "管理端没有检索到文档");
    }
}
