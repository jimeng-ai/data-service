package com.jimeng.dataserver.ai.rag.config;

import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ElasticsearchConfig {

    private final RagProperties ragProperties;

    @Bean(destroyMethod = "close")
    public RestClient esRestClient() {
        RagProperties.Elasticsearch cfg = ragProperties.getElasticsearch();
        if (StrUtil.isBlank(cfg.getHosts())) {
            throw new IllegalStateException("rag.elasticsearch.hosts 未配置");
        }
        List<HttpHost> httpHosts = new ArrayList<>();
        for (String host : cfg.getHosts().split(",")) {
            URI uri = URI.create(host.trim());
            httpHosts.add(new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme()));
        }
        RestClientBuilder builder = RestClient.builder(httpHosts.toArray(new HttpHost[0]));

        if (StrUtil.isNotBlank(cfg.getUsername())) {
            BasicCredentialsProvider creds = new BasicCredentialsProvider();
            creds.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(cfg.getUsername(), cfg.getPassword()));
            builder.setHttpClientConfigCallback(h -> h.setDefaultCredentialsProvider(creds));
        }

        builder.setRequestConfigCallback(rc -> rc
                .setConnectTimeout((int) cfg.getConnectionTimeout().toMillis())
                .setSocketTimeout((int) cfg.getSocketTimeout().toMillis()));

        log.info("Elasticsearch RestClient 初始化: hosts={}", cfg.getHosts());
        return builder.build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient esRestClient) {
        ObjectMapper mapper = new ObjectMapper()
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        ElasticsearchTransport transport = new RestClientTransport(esRestClient, new JacksonJsonpMapper(mapper));
        return new ElasticsearchClient(transport);
    }
}
