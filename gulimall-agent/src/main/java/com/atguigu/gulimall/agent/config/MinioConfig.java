package com.atguigu.gulimall.agent.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端，复用项目已有的对象存储。
 *
 * <p>上传图片和生成图片都走这里，返回 /oss/gulimall/... 的根相对地址，
 * 由 Nginx 的 img-proxy.inc 转发到 MinIO。
 */
@Configuration
public class MinioConfig {

    /**
     * 创建连接项目对象存储的 MinIO 客户端。
     *
     * @param endpoint  MinIO 服务地址
     * @param accessKey MinIO 访问密钥
     * @param secretKey MinIO 私密密钥
     * @return 可上传和下载图片的 MinIO 客户端
     */
    @Bean
    public MinioClient minioClient(@Value("${MINIO_ENDPOINT}") String endpoint,
                                   @Value("${MINIO_ACCESS_KEY}") String accessKey,
                                   @Value("${MINIO_SECRET_KEY}") String secretKey) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
