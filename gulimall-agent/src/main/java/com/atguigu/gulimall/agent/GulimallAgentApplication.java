package com.atguigu.gulimall.agent;

import com.atguigu.gulimall.agent.config.AiProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * gulimall-agent 启动类
 *
 * <p>AI 助手独立成一个微服务，原因：
 * 1. LangChain4J / Milvus / MinIO 等依赖体积大，不能塞进 gulimall-common；
 * 2. AI 请求耗时远高于普通接口，独立部署便于单独限流、扩容；
 * 3. 用户端和管理端共用同一套模型、会话、工具和 RAG。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.atguigu.gulimall.agent.feign")
@MapperScan("com.atguigu.gulimall.agent.settings.mapper")
@EnableConfigurationProperties(AiProperties.class)
public class GulimallAgentApplication {

    /**
     * 启动 gulimall-agent 微服务。
     *
     * @param args 命令行启动参数，原样交给 Spring Boot 处理
     */
    public static void main(String[] args) {
        SpringApplication.run(GulimallAgentApplication.class, args);
    }
}
