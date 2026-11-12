package com.atguigu.gulimall.agent.rag;

import com.atguigu.gulimall.agent.config.AiProperties;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从 classpath 的 rag 目录加载知识库文档（支持 public/admin 子目录）。
 *
 * <p>audience 按目录区分：
 * rag/public 下的文档为 PUBLIC，rag/admin 下的文档为 ADMIN；
 * 其它目录不加载，避免管理端文档被误标为 PUBLIC。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagDocumentLoader {

    private final AiProperties properties;

    /**
     * 从 classpath 的 rag 目录加载所有 Markdown 文档，并按 public/admin 子目录标注可见范围。
     *
     * @return 加载成功且带元数据的文档列表
     */
    public List<Document> load() {
        // 1. 扫描 classpath 下的 Markdown
        List<Document> documents = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            String docLocation = properties.getRag().getDocLocation();
            String location = "classpath*:" + docLocation + "/**/*.md";
            Resource[] resources = resolver.getResources(location);
            for (Resource resource : resources) {
                String filename = resource.getFilename() == null ? "unknown.md" : resource.getFilename();
                String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (text.isBlank()) {
                    continue;
                }

                // 2. 按 public/admin 目录判断可见范围
                String audience = audienceOf(resource, docLocation);
                if (audience == null) {
                    log.warn("RAG 文档不在 public/admin 目录下，已跳过：{}", resource.getDescription());
                    continue;
                }
                String source = audience.toLowerCase(Locale.ROOT) + "/" + filename;
                documents.add(Document.from(text, Metadata.from(Map.of(
                        "source", source,
                        "audience", audience,
                        "version", shortHash(text)
                ))));
                log.info("RAG 加载文档：{} audience={} chars={}", source, audience, text.length());
            }
        } catch (Exception ex) {
            log.warn("RAG 文档加载失败：{}", ex.getMessage());
        }
        return documents;
    }

    /**
     * 计算当前文档集合的指纹，用于判断内容是否变化而决定是否重新入库。
     *
     * @return 由来源路径和内容哈希组成的文档指纹
     */
    public String fingerprint() {
        // 1. 用“来源路径 + 内容 hash”判断文档是否变化，决定是否重新入库
        StringBuilder raw = new StringBuilder();
        for (Document document : load()) {
            raw.append(document.metadata().getString("source"))
                    .append(':')
                    .append(document.metadata().getString("version"))
                    .append(';');
        }
        return shortHash(raw.toString());
    }

    /**
     * 根据文档所在目录判断其可见范围。
     *
     * @param resource    要判断的文档资源
     * @param docLocation 知识库文档的根目录配置
     * @return 可见范围 ADMIN 或 PUBLIC，不在允许目录下时返回 null
     */
    private String audienceOf(Resource resource, String docLocation) {
        String path;
        try {
            path = resource.getURL().getPath();
        } catch (Exception ex) {
            path = resource.getDescription();
        }
        path = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String prefix = "/" + docLocation.replace('\\', '/')
                .replaceAll("^/+|/+$", "")
                .toLowerCase(Locale.ROOT) + "/";
        if (path.contains(prefix + "admin/")) {
            return "ADMIN";
        }
        if (path.contains(prefix + "public/")) {
            return "PUBLIC";
        }
        return null;
    }

    /**
     * 计算文本的短哈希值，取 SHA-256 结果的前 8 个字节以十六进制表示。
     *
     * @param text 要计算哈希的文本
     * @return 短哈希字符串，算法不可用时退化为字符串的哈希码
     */
    private String shortHash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception ex) {
            return String.valueOf(text.hashCode());
        }
    }
}
