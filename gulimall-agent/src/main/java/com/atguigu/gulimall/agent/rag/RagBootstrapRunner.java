package com.atguigu.gulimall.agent.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时尝试入库 RAG 文档。
 *
 * <p>Milvus 没启动、没配 key 都只记日志，不影响 AI 聊天主链路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagBootstrapRunner implements ApplicationRunner {

    private final RagIndexService ragIndexService;

    @Value("${gulimall.ai.rag.ingest-on-startup:true}")
    private boolean ingestOnStartup;

    /**
     * 应用启动后按需执行一次 RAG 文档入库，失败只记录日志不影响主链路。
     *
     * @param args 应用启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        // 1. 配置关闭时不执行
        if (!ingestOnStartup) {
            return;
        }

        // 2. 后台性质地跑一次，失败降级
        try {
            ragIndexService.ingestIfNeeded();
        } catch (Exception ex) {
            log.warn("RAG 启动入库失败（不影响其它功能）：{}", ex.getMessage());
        }
    }
}
