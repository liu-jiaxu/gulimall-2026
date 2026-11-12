package com.atguigu.gulimall.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @ClassName: GulimallElasticSearchConfig
 * @Package: com.atguigu.gulimall.search.config
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/14 - 16:50
 * @Version: v1.0
 */
@Configuration
public class GulimallElasticSearchConfig {

    @Value("${spring.elasticsearch.uris}")
    private String uris;

    @Value("${spring.elasticsearch.username}")
    private String username;

    @Value("${spring.elasticsearch.password}")
    private String password;


    @Bean
    public ElasticsearchClient elasticsearchClient() {
        // 1、HTTP Basic 认证（服务端开了 xpack.security，必须带账号密码）
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(username, password));

        // 2、底层 REST 传输层
        RestClient restClient = RestClient.builder(HttpHost.create(uris))
                .setHttpClientConfigCallback(b -> b.setDefaultCredentialsProvider(credentialsProvider))
                .build();

        // 3、JSON 映射器 + 传输层 → 最终客户端
        ElasticsearchTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper());

        return new ElasticsearchClient(transport);
    }

}
