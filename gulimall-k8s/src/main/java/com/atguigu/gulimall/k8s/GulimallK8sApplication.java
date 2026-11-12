package com.atguigu.gulimall.k8s;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 独立 AI 对话服务启动类。
 *
 * <p>本服务不注册 Nacos、不调用任何其它 gulimall 模块，
 * 只依赖 MySQL、Redis 和 OpenAI 兼容模型服务。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GulimallK8sApplication {

    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(GulimallK8sApplication.class, args);
    }
}
