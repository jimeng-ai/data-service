package com.jimeng.dataserver.ai.provider.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多 provider 配置树。yml 形如：
 * <pre>
 * providers:
 *   openrouter:
 *     base-url: ...
 *     api-key: ...
 *     chat:           { protocol: anthropic, model: ..., max-tokens: 8192 }
 *     embedding:      { protocol: openai, model: ..., dims: 1536 }
 *     rerank:         { protocol: cohere, model: ..., endpoint-path: /rerank }
 *     contextualization: { text-model: ..., image-model: ..., max-output-tokens: 200, use-prompt-cache: true }
 *   302ai: { ... 同上 }
 * </pre>
 *
 * <p>新增 provider 在 yml 加一段即可；如果协议是 anthropic/openai 二选一且 rerank 是 cohere/qwen3，
 * 还要在 ProviderBeansConfig 加 @Bean 注册一组 bean。
 */
@Data
@Component
@ConfigurationProperties  // 空 prefix（绑定根）+ 字段名 providers → 匹配 yml 顶层 providers.<name>.*
public class AiProviderProperties {

    /** key = provider 名（如 "openrouter" / "302ai"），与 @Bean 名一致。 */
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    @Data
    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey;
        private Duration timeout = Duration.ofSeconds(30);
        private Chat chat = new Chat();
        private Embedding embedding = new Embedding();
        private Rerank rerank = new Rerank();
        private Contextualization contextualization = new Contextualization();
    }

    @Data
    public static class Chat {
        /** "anthropic" 或 "openai"。决定走 /v1/messages 还是 /v1/chat/completions。 */
        private String protocol;
        private String model;
        private int maxTokens = 8192;
        /** 选填覆盖；为空时按 protocol 推导。 */
        private String endpointPath;
    }

    @Data
    public static class Embedding {
        /** 当前只支持 openai 兼容协议。 */
        private String protocol = "openai";
        private String model;
        /** 必须 > 0，且与实际返回向量维度一致（ES 索引依赖此值）。 */
        private int dims;
        private String endpointPath = "/embeddings";
        private int batchSize = 100;
    }

    @Data
    public static class Rerank {
        /** "cohere" / "qwen3" / 其他自定义。 */
        private String protocol;
        private String model;
        private String endpointPath = "/rerank";
        /** Qwen3 的 instruct 等额外字段。 */
        private Map<String, Object> extraParams = new LinkedHashMap<>();
    }

    @Data
    public static class Contextualization {
        private String textModel;
        private String imageModel;
        private int maxOutputTokens = 200;
        /** 仅当 chat.protocol=anthropic 时生效；否则降级为无 cache 普通调用。 */
        private boolean usePromptCache = true;
    }
}
