package org.example.searchservice.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;

@Configuration
public class ElasticsearchConfig {

    @Bean
    public ElasticsearchTemplate elasticsearchTemplate() {
        // 1. 创建 RestClient（底层 HTTP 客户端）
        RestClient restClient = RestClient.builder(
                new HttpHost("localhost", 9200, "http")
        ).build();

        // 2. 创建 Transport（负责序列化/反序列化）
        RestClientTransport transport = new RestClientTransport(
                restClient,
                new JacksonJsonpMapper()
        );

        // 3. 创建 ElasticsearchClient（ES 官方客户端）
        ElasticsearchClient elasticsearchClient = new ElasticsearchClient(transport);

        // 4. 创建 Spring Data 的 ElasticsearchRestTemplate
        return new ElasticsearchTemplate(elasticsearchClient);
    }
}