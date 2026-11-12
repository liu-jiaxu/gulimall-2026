package com.atguigu.gulimall.agent.flow;

import com.atguigu.gulimall.agent.auth.PrincipalType;
import com.atguigu.gulimall.agent.auth.jwt.AgentJwtProperties;
import com.atguigu.gulimall.agent.auth.jwt.AgentJwtService;
import com.atguigu.gulimall.agent.config.AiProperties;
import com.atguigu.gulimall.agent.config.FeignInternalTokenConfig;
import com.atguigu.gulimall.agent.config.WebMvcConfig;
import com.atguigu.gulimall.agent.model.AiModelDefinition;
import com.atguigu.gulimall.agent.model.ModelCatalog;
import com.atguigu.gulimall.agent.model.ModelFactory;
import com.atguigu.gulimall.agent.rag.RagBootstrapRunner;
import com.atguigu.gulimall.agent.rag.RagDocumentLoader;
import com.atguigu.gulimall.agent.rag.RagIndexService;
import com.atguigu.gulimall.agent.support.AgentIntegrationTestSupport;
import dev.langchain4j.data.document.Document;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程 3.1：启动与配置加载。
 */
@Tag("integration")
class Flow31StartupConfigTest extends AgentIntegrationTestSupport {

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private ModelCatalog modelCatalog;

    @Autowired
    private ModelFactory modelFactory;

    @Autowired
    private RagBootstrapRunner ragBootstrapRunner;

    @Autowired
    private RagDocumentLoader ragDocumentLoader;

    @Autowired
    private RagIndexService ragIndexService;

    @Autowired
    private AgentJwtService agentJwtService;

    @Autowired
    private AgentJwtProperties agentJwtProperties;

    @Autowired
    private WebMvcConfig webMvcConfig;

    @Autowired
    private FeignInternalTokenConfig feignInternalTokenConfig;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    @Qualifier("imageTaskExecutor")
    private ThreadPoolTaskExecutor imageTaskExecutor;

    /**
     * 测试 Spring 上下文启动后，配置绑定、模型目录、模型工厂、Web/Feign 配置、图片线程池和 MinIO bucket 均已就绪。
     */
    @Test
    void contextAndCoreBeansAreReady() throws Exception {
        // 1. 配置绑定成功，关键项不是空值
        assertNotNull(aiProperties.getDefaults());
        assertNotNull(aiProperties.getDefaultModel());
        assertFalse(aiProperties.getModels().isEmpty());
        assertNotNull(aiProperties.getImage());
        assertNotNull(aiProperties.getEmbedding());
        assertNotNull(aiProperties.getRag());
        assertNotNull(aiProperties.getConversation());
        assertNotNull(aiProperties.getSecurity());

        // 2. 模型目录已经完成初始化，默认模型可找到
        List<AiModelDefinition> models = modelCatalog.list(TEST_USER);
        assertFalse(models.isEmpty());
        AiModelDefinition defaultModel = modelCatalog.defaultModel(TEST_USER);
        assertEquals(aiProperties.getDefaultModel(), defaultModel.getId());
        assertNotNull(modelCatalog.visionModel(TEST_USER));

        // 3. 模型工厂可以按需创建模型对象，不发起真实请求
        assertNotNull(modelFactory.chat(defaultModel));
        assertNotNull(modelFactory.streaming(defaultModel));
        assertNotNull(modelFactory.embedding());
        assertNotNull(modelFactory.image());
        assertNotNull(modelFactory.image("1024x1024", "low"));

        // 4. Web 与 Feign 配置已加载
        assertNotNull(webMvcConfig);
        assertNotNull(feignInternalTokenConfig);

        // 5. 图片线程池已创建
        assertNotNull(imageTaskExecutor);
        assertTrue(imageTaskExecutor.getCorePoolSize() > 0);

        // 6. MinIO bucket 可用
        assertTrue(minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(minioBucket).build()));
    }

    /**
     * 测试启动阶段加载 user/admin 公钥并能验签真实 JWT，同时验证 RAG 文档、内容指纹、启动 runner 和索引状态查询。
     */
    @Test
    void jwtKeysAndRagDocumentsAreLoaded() {
        // 1. 两把公钥都已加载，真实 token 可以验签
        assertNotNull(agentJwtProperties.getUserKeyId());
        assertNotNull(agentJwtProperties.getAdminKeyId());
        assertEquals(PrincipalType.USER, agentJwtService.verify(userToken()).type());
        assertEquals(PrincipalType.ADMIN, agentJwtService.verify(adminToken()).type());

        // 2. RAG 文档可以从 classpath 加载，指纹不为空
        List<Document> documents = ragDocumentLoader.load();
        assertFalse(documents.isEmpty());
        assertFalse(ragDocumentLoader.fingerprint().isBlank());

        // 3. 启动 runner 在 ingest-on-startup=false 时直接返回，不抛异常
        ragBootstrapRunner.run(null);

        // 4. 索引状态查询可以正常执行
        ragIndexService.isIndexed();
    }
}
