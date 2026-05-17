package com.jimeng.dataserver.ai.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private Elasticsearch elasticsearch = new Elasticsearch();
    private Chunk chunk = new Chunk();
    private Contextualization contextualization = new Contextualization();
    private Retrieval retrieval = new Retrieval();
    private Ingestion ingestion = new Ingestion();

    @Data
    public static class Elasticsearch {
        private String hosts = "http://localhost:9200";
        private String username;
        private String password;
        private String indexName = "kb_chunks";
        private Duration connectionTimeout = Duration.ofSeconds(5);
        private Duration socketTimeout = Duration.ofSeconds(60);
    }

    @Data
    public static class Chunk {
        private int targetSizeTokens = 600;
        private int maxSizeTokens = 800;
        private int overlapTokens = 80;
        private String sentenceSplitter = "(?<=[。！？.!?])\\s+";
    }

    /**
     * 上下文化业务开关。模型 ID 等已迁移到 providers.&lt;name&gt;.contextualization.*；
     * 这里仅保留与具体 provider 无关的策略字段。
     */
    @Data
    public static class Contextualization {
        private boolean enabled = true;
        private int promptCacheTtlSeconds = 300;
    }

    @Data
    public static class Retrieval {
        private int bm25TopK = 50;
        private int vectorTopK = 50;
        private int rrfRankConstant = 60;
        private int rerankTopK = 10;
    }

    @Data
    public static class Ingestion {
        private String queue = "rag.ingestion";
        private int concurrency = 2;
        private int maxRetries = 3;
        private String minioBucket = "rag-documents";
    }
}
