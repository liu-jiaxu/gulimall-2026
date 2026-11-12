package com.atguigu.gulimall.thirdparty.config;

import com.atguigu.gulimall.thirdparty.property.MinioProperties;
import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @ClassName: MinioConfiguration
 * @Package: com.atguigu.gulimall.thirdparty.config
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/2 - 21:56
 * @Version: v1.0
 */
@EnableConfigurationProperties(value = MinioProperties.class) //开启配置属性绑定功能
@Configuration
public class MinioConfiguration {

    /**
     * MinIO 客户端（全局复用）
     */
    @Bean
    public MinioClient minioClient(MinioProperties minioProperties) {
        return MinioClient.builder()
                .endpoint(minioProperties.getEndpointUrl())
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecreKey())
                .build();
    }

}
