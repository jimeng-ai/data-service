package com.jimeng.dataserver.ai.rag.service.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorIndexOptions;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorProperty;
import co.elastic.clients.elasticsearch._types.mapping.IntegerNumberProperty;
import co.elastic.clients.elasticsearch._types.mapping.KeywordProperty;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TextProperty;
import co.elastic.clients.elasticsearch.cat.PluginsResponse;
import co.elastic.clients.elasticsearch.cat.plugins.PluginsRecord;
import com.jimeng.dataserver.ai.provider.ProviderRegistry;
import com.jimeng.dataserver.ai.rag.config.RagProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动期 ES 检查与索引初始化：
 * 1. ES 连通；
 * 2. IK 分词插件存在（中文召回质量的硬前提）；
 * 3. kb_chunks 索引存在，缺则创建。
 * 任一失败抛异常阻止启动 —— 静默回落到 standard 分词会让中文检索效果严重下降。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsIndexInitializer {

    private static final String IK_PLUGIN_NAME = "analysis-ik";

    private final ElasticsearchClient esClient;
    private final RagProperties ragProperties;
    private final ProviderRegistry providerRegistry;

    @PostConstruct
    public void init() throws IOException {
        verifyConnectivity();
        verifyIkPlugin();
        ensureKbChunkIndex();
    }

    private void verifyConnectivity() throws IOException {
        var info = esClient.info();
        log.info("Elasticsearch 连通: cluster={}, version={}",
                info.clusterName(), info.version().number());
    }

    private void verifyIkPlugin() throws IOException {
        PluginsResponse resp = esClient.cat().plugins();
        List<PluginsRecord> records = resp.valueBody();
        boolean hasIk = records.stream()
                .anyMatch(r -> IK_PLUGIN_NAME.equals(r.component()));
        if (!hasIk) {
            throw new IllegalStateException(
                    "Elasticsearch 缺少 " + IK_PLUGIN_NAME + " 中文分词插件，"
                            + "请按 docker/docker-compose.es.yml 装好后再启动 data-server。");
        }
        log.info("Elasticsearch {} 插件检测通过", IK_PLUGIN_NAME);
    }

    private void ensureKbChunkIndex() throws IOException {
        String indexName = ragProperties.getElasticsearch().getIndexName();
        boolean exists = esClient.indices().exists(e -> e.index(indexName)).value();
        if (exists) {
            log.info("Elasticsearch 索引 [{}] 已存在", indexName);
            return;
        }
        int dims = providerRegistry.embedding().dims();
        Map<String, Property> properties = buildMapping(dims);
        esClient.indices().create(c -> c
                .index(indexName)
                .mappings(m -> m.properties(properties))
        );
        log.info("Elasticsearch 索引 [{}] 创建完成 (embedding dims={})", indexName, dims);
    }

    private Map<String, Property> buildMapping(int dims) {
        Map<String, Property> props = new HashMap<>();
        props.put("chunk_id", Property.of(p -> p.keyword(KeywordProperty.of(k -> k))));
        props.put("kb_id", Property.of(p -> p.keyword(KeywordProperty.of(k -> k))));
        props.put("doc_id", Property.of(p -> p.keyword(KeywordProperty.of(k -> k))));
        props.put("chunk_index", Property.of(p -> p.integer(IntegerNumberProperty.of(i -> i))));
        props.put("chunk_type", Property.of(p -> p.keyword(KeywordProperty.of(k -> k))));
        props.put("heading_path", Property.of(p -> p.keyword(KeywordProperty.of(k -> k))));
        props.put("page_num", Property.of(p -> p.integer(IntegerNumberProperty.of(i -> i))));
        props.put("image_url", Property.of(p -> p.keyword(KeywordProperty.of(k -> k))));
        // 中文 BM25：索引用 ik_max_word，查询用 ik_smart
        props.put("content", Property.of(p -> p.text(TextProperty.of(t -> t
                .analyzer("ik_max_word").searchAnalyzer("ik_smart")))));
        props.put("contextualized_content", Property.of(p -> p.text(TextProperty.of(t -> t
                .analyzer("ik_max_word").searchAnalyzer("ik_smart")))));
        // 稠密向量（HNSW，cosine）
        props.put("embedding", Property.of(p -> p.denseVector(DenseVectorProperty.of(dv -> dv
                .dims(dims)
                .index(true)
                .similarity("cosine")
                .indexOptions(DenseVectorIndexOptions.of(io -> io
                        .type("hnsw")
                        .m(16)
                        .efConstruction(100)))
        ))));
        return props;
    }
}
