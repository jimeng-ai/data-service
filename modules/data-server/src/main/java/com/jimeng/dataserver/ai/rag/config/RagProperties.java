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
    private OpenRouter openrouter = new OpenRouter();
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
    public static class OpenRouter {
        private String baseUrl = "https://openrouter.ai/api/v1";
        private String apiKey;
        private String embeddingModel = "openai/text-embedding-ada-002";
        private int embeddingDims = 1536;
        private String rerankModel = "cohere/rerank-4-pro";
        private Duration timeout = Duration.ofSeconds(30);
    }

    @Data
    public static class Chunk {
        private int targetSizeTokens = 600;
        private int maxSizeTokens = 800;
        private int overlapTokens = 80;
        private String sentenceSplitter = "(?<=[。！？.!?])\\s+";
    }

    @Data
    public static class Contextualization {
        private boolean enabled = true;
        private String textModel = "claude-haiku-4-5";
        private String imageModel = "claude-sonnet-4-6";
        private int maxOutputTokens = 200;
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
