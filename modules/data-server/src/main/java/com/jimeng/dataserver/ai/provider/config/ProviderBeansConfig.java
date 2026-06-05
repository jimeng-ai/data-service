package com.jimeng.dataserver.ai.provider.config;

import com.jimeng.common.core.service.RequestService;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.conversation.AiConversationLoop;
import com.jimeng.dataserver.ai.protocol.ClaudeProtocolAdapter;
import com.jimeng.dataserver.ai.protocol.OpenAiProtocolAdapter;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties.ProviderConfig;
import com.jimeng.dataserver.ai.provider.impl.DefaultContextualizationClient;
import com.jimeng.dataserver.ai.provider.impl.GenericChatClient;
import com.jimeng.dataserver.ai.provider.impl.GenericOpenAiEmbeddingClient;
import com.jimeng.dataserver.ai.provider.spi.ChatClient;
import com.jimeng.dataserver.ai.provider.spi.ContextualizationClient;
import com.jimeng.dataserver.ai.provider.spi.EmbeddingClient;
import com.jimeng.dataserver.ai.provider.spi.RerankClient;
import com.jimeng.dataserver.ai.provider.spi.RerankClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

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
 *       rerank 由 {@link RerankClientFactory} 按 protocol 自动分发，新增 rerank 协议加一个 factory bean 即可、不必改本类；</li>
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
                                               RequestService requestService,
                                               List<RerankClientFactory> rerankFactories) {
        return newRerankByProtocol("openrouter", props, requestService, rerankFactories);
    }

    @Bean(name = "openrouter-contextualization")
    public ContextualizationClient openrouterContextualizationClient(AiProviderProperties props,
                                                                     RequestService requestService,
                                                                     AiModelCallRecordService recordService,
                                                                     ClaudeProtocolAdapter anthropic,
                                                                     OpenAiProtocolAdapter openai) {
        return newContextualization("openrouter", props, requestService, recordService, anthropic, openai);
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
                                          RequestService requestService,
                                          List<RerankClientFactory> rerankFactories) {
        return newRerankByProtocol("302ai", props, requestService, rerankFactories);
    }

    @Bean(name = "302ai-contextualization")
    public ContextualizationClient ai302ContextualizationClient(AiProviderProperties props,
                                                                RequestService requestService,
                                                                AiModelCallRecordService recordService,
                                                                ClaudeProtocolAdapter anthropic,
                                                                OpenAiProtocolAdapter openai) {
        return newContextualization("302ai", props, requestService, recordService, anthropic, openai);
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
                                                    RequestService requestService,
                                                    List<RerankClientFactory> factories) {
        ProviderConfig cfg = configOf(providerName, props);
        String protocol = cfg.getRerank().getProtocol();
        if (protocol == null) {
            throw new IllegalStateException("providers." + providerName
                    + ".rerank.protocol 未配置（cohere / qwen3 / ...）");
        }
        for (RerankClientFactory f : factories) {
            if (f.protocol().equalsIgnoreCase(protocol)) {
                return f.create(providerName, cfg, requestService);
            }
        }
        throw new IllegalStateException("不支持的 rerank.protocol: " + protocol
                + "（provider=" + providerName + "）。已知: "
                + factories.stream().map(RerankClientFactory::protocol).toList()
                + "。新增请实现 RerankClientFactory 并注册为 bean。");
    }

    private static ContextualizationClient newContextualization(String providerName,
                                                                AiProviderProperties props,
                                                                RequestService requestService,
                                                                AiModelCallRecordService recordService,
                                                                ClaudeProtocolAdapter anthropic,
                                                                OpenAiProtocolAdapter openai) {
        return new DefaultContextualizationClient(providerName, configOf(providerName, props),
                requestService, recordService, anthropic, openai);
    }
}
