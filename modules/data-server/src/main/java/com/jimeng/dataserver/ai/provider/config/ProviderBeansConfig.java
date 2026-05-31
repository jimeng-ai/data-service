package com.jimeng.dataserver.ai.provider.config;

import com.jimeng.common.core.service.RequestService;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.ai.claude.service.AiModelCallRecordService;
import com.jimeng.dataserver.ai.conversation.AiConversationLoop;
import com.jimeng.dataserver.ai.protocol.ClaudeProtocolAdapter;
import com.jimeng.dataserver.ai.protocol.OpenAiProtocolAdapter;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties.ProviderConfig;
import com.jimeng.dataserver.ai.provider.impl.CohereRerankClient;
import com.jimeng.dataserver.ai.provider.impl.DefaultContextualizationClient;
import com.jimeng.dataserver.ai.provider.impl.GenericChatClient;
import com.jimeng.dataserver.ai.provider.impl.GenericOpenAiEmbeddingClient;
import com.jimeng.dataserver.ai.provider.impl.Qwen3RerankClient;
import com.jimeng.dataserver.ai.provider.spi.ChatClient;
import com.jimeng.dataserver.ai.provider.spi.ContextualizationClient;
import com.jimeng.dataserver.ai.provider.spi.EmbeddingClient;
import com.jimeng.dataserver.ai.provider.spi.RerankClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册各 provider 的 client bean。
 *
 * <p>bean 命名规则：<code>&lt;provider&gt;-&lt;capability&gt;</code>（如 "openrouter-chat"），
 * 由 ProviderRegistry 按每个 client 自身的 {@code providerName()} 重新索引为
 * provider-name → client 的 Map，再按 {@code ai.provider} 选定激活实例。
 *
 * <p>新增 provider 步骤：
 * <ol>
 *   <li>在 yml 加 <code>providers.&lt;name&gt;</code> 配置块；</li>
 *   <li>在本类加四个 @Bean 方法（chat/embedding/rerank/contextualization），调用对应工厂；
 *       rerank 按响应结构选 cohere 还是 qwen3；</li>
 *   <li><code>ai.provider</code> 切到新名字 → 重启生效。</li>
 * </ol>
 */
@Configuration
public class ProviderBeansConfig {

    // ============================================================ openrouter

    @Bean(name = "openrouter-chat")
    public ChatClient openrouterChatClient(AiProviderProperties props,
                                           AiSelectionProperties selection,
                                           AiConversationLoop loop,
                                           ClaudeProtocolAdapter anthropic,
                                           OpenAiProtocolAdapter openai,
                                           SseServiceUtil sse) {
        return newChat("openrouter", props, selection, loop, anthropic, openai, sse);
    }

    @Bean(name = "openrouter-embedding")
    public EmbeddingClient openrouterEmbeddingClient(AiProviderProperties props,
                                                     RequestService requestService,
                                                     AiModelCallRecordService recordService) {
        return newEmbedding("openrouter", props, requestService, recordService);
    }

    @Bean(name = "openrouter-rerank")
    public RerankClient openrouterRerankClient(AiProviderProperties props,
                                               RequestService requestService) {
        return newRerankByProtocol("openrouter", props, requestService);
    }

    @Bean(name = "openrouter-contextualization")
    public ContextualizationClient openrouterContextualizationClient(AiProviderProperties props,
                                                                     RequestService requestService,
                                                                     AiModelCallRecordService recordService) {
        return newContextualization("openrouter", props, requestService, recordService);
    }

    // ============================================================ 302ai

    @Bean(name = "302ai-chat")
    public ChatClient ai302ChatClient(AiProviderProperties props,
                                      AiSelectionProperties selection,
                                      AiConversationLoop loop,
                                      ClaudeProtocolAdapter anthropic,
                                      OpenAiProtocolAdapter openai,
                                      SseServiceUtil sse) {
        return newChat("302ai", props, selection, loop, anthropic, openai, sse);
    }

    @Bean(name = "302ai-embedding")
    public EmbeddingClient ai302EmbeddingClient(AiProviderProperties props,
                                                RequestService requestService,
                                                AiModelCallRecordService recordService) {
        return newEmbedding("302ai", props, requestService, recordService);
    }

    @Bean(name = "302ai-rerank")
    public RerankClient ai302RerankClient(AiProviderProperties props,
                                          RequestService requestService) {
        return newRerankByProtocol("302ai", props, requestService);
    }

    @Bean(name = "302ai-contextualization")
    public ContextualizationClient ai302ContextualizationClient(AiProviderProperties props,
                                                                RequestService requestService,
                                                                AiModelCallRecordService recordService) {
        return newContextualization("302ai", props, requestService, recordService);
    }

    // ============================================================ factories

    private static ProviderConfig configOf(String providerName, AiProviderProperties props) {
        ProviderConfig cfg = props.getProviders().get(providerName);
        if (cfg == null) {
            throw new IllegalStateException("providers." + providerName
                    + " 未在 yml 中配置，无法创建该 provider 的 client bean");
        }
        return cfg;
    }

    private static ChatClient newChat(String providerName,
                                      AiProviderProperties props,
                                      AiSelectionProperties selection,
                                      AiConversationLoop loop,
                                      ClaudeProtocolAdapter anthropic,
                                      OpenAiProtocolAdapter openai,
                                      SseServiceUtil sse) {
        return new GenericChatClient(providerName, configOf(providerName, props),
                selection, loop, anthropic, openai, sse);
    }

    private static EmbeddingClient newEmbedding(String providerName,
                                                AiProviderProperties props,
                                                RequestService requestService,
                                                AiModelCallRecordService recordService) {
        return new GenericOpenAiEmbeddingClient(providerName, configOf(providerName, props),
                requestService, recordService);
    }

    private static RerankClient newRerankByProtocol(String providerName,
                                                    AiProviderProperties props,
                                                    RequestService requestService) {
        ProviderConfig cfg = configOf(providerName, props);
        String protocol = cfg.getRerank().getProtocol();
        if (protocol == null) {
            throw new IllegalStateException("providers." + providerName
                    + ".rerank.protocol 未配置（cohere / qwen3 / ...）");
        }
        return switch (protocol.toLowerCase()) {
            case "cohere" -> new CohereRerankClient(providerName, cfg, requestService);
            case "qwen3" -> new Qwen3RerankClient(providerName, cfg, requestService);
            default -> throw new IllegalStateException("不支持的 rerank.protocol: " + protocol
                    + "（provider=" + providerName + "）。"
                    + "已知支持: cohere, qwen3。新增需实现 RerankClient 并在此分支添加。");
        };
    }

    private static ContextualizationClient newContextualization(String providerName,
                                                                AiProviderProperties props,
                                                                RequestService requestService,
                                                                AiModelCallRecordService recordService) {
        return new DefaultContextualizationClient(providerName, configOf(providerName, props),
                requestService, recordService);
    }
}
