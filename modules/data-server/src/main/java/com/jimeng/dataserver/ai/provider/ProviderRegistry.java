package com.jimeng.dataserver.ai.provider;

import cn.hutool.core.util.StrUtil;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties.ProviderConfig;
import com.jimeng.dataserver.ai.provider.config.AiSelectionProperties;
import com.jimeng.dataserver.ai.provider.spi.ChatClient;
import com.jimeng.dataserver.ai.provider.spi.ContextualizationClient;
import com.jimeng.dataserver.ai.provider.spi.EmbeddingClient;
import com.jimeng.dataserver.ai.provider.spi.RerankClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy + Registry：把 ProviderBeansConfig 注册的 client bean 按 providerName 分组，
 * 运行时按 {@link AiSelectionProperties#getProvider()} 返回对应 client。
 *
 * <p>启动 @PostConstruct 阶段做 fail-fast 校验：active provider 必须配齐 chat / embedding /
 * rerank / contextualization 四个 client，对应 yml 必填字段都存在；任一失败抛异常阻止启动。
 */
@Slf4j
@Component
public class ProviderRegistry {

    private final AiSelectionProperties selection;
    private final AiProviderProperties providerProperties;
    private final Map<String, ChatClient> chatClients = new LinkedHashMap<>();
    private final Map<String, EmbeddingClient> embeddingClients = new LinkedHashMap<>();
    private final Map<String, RerankClient> rerankClients = new LinkedHashMap<>();
    private final Map<String, ContextualizationClient> contextualizationClients = new LinkedHashMap<>();

    public ProviderRegistry(AiSelectionProperties selection,
                            AiProviderProperties providerProperties,
                            List<ChatClient> chatList,
                            List<EmbeddingClient> embeddingList,
                            List<RerankClient> rerankList,
                            List<ContextualizationClient> contextualizationList) {
        this.selection = selection;
        this.providerProperties = providerProperties;
        chatList.forEach(c -> chatClients.put(c.providerName(), c));
        embeddingList.forEach(c -> embeddingClients.put(c.providerName(), c));
        rerankList.forEach(c -> rerankClients.put(c.providerName(), c));
        contextualizationList.forEach(c -> contextualizationClients.put(c.providerName(), c));
    }

    @PostConstruct
    public void validate() {
        String active = selection.getProvider();
        if (StrUtil.isBlank(active)) {
            throw new IllegalStateException("ai.provider 未配置，请在 yml 中指定激活的 provider");
        }
        ProviderConfig cfg = providerProperties.getProviders().get(active);
        if (cfg == null) {
            throw new IllegalStateException("ai.provider=" + active
                    + " 在 providers.* 下没有对应配置块（已配置: "
                    + providerProperties.getProviders().keySet() + "）");
        }
        require(chatClients.containsKey(active),
                "缺少 chat client bean，请在 ProviderBeansConfig 添加 @Bean name=\"" + active + "-chat\"");
        require(embeddingClients.containsKey(active),
                "缺少 embedding client bean，请在 ProviderBeansConfig 添加 @Bean name=\"" + active + "-embedding\"");
        require(rerankClients.containsKey(active),
                "缺少 rerank client bean，请在 ProviderBeansConfig 添加 @Bean name=\"" + active + "-rerank\"");
        require(contextualizationClients.containsKey(active),
                "缺少 contextualization client bean，请在 ProviderBeansConfig 添加 @Bean name=\""
                        + active + "-contextualization\"");

        validateConfigFields(active, cfg);

        log.info("AI provider 激活: provider={} chat={{protocol={}, model={}}} "
                        + "embedding={{model={}, dims={}}} rerank={{protocol={}, model={}}} "
                        + "contextualization={{text-model={}, image-model={}, use-prompt-cache={}}}",
                active,
                cfg.getChat().getProtocol(), cfg.getChat().getModel(),
                cfg.getEmbedding().getModel(), cfg.getEmbedding().getDims(),
                cfg.getRerank().getProtocol(), cfg.getRerank().getModel(),
                cfg.getContextualization().getTextModel(),
                cfg.getContextualization().getImageModel(),
                cfg.getContextualization().isUsePromptCache());
    }

    public ChatClient chat() {
        return chatClients.get(selection.getProvider());
    }

    public EmbeddingClient embedding() {
        return embeddingClients.get(selection.getProvider());
    }

    public RerankClient rerank() {
        return rerankClients.get(selection.getProvider());
    }

    public ContextualizationClient contextualization() {
        return contextualizationClients.get(selection.getProvider());
    }

    public String activeProvider() {
        return selection.getProvider();
    }

    private static void validateConfigFields(String name, ProviderConfig cfg) {
        require(StrUtil.isNotBlank(cfg.getBaseUrl()), "providers." + name + ".base-url 未配置");
        require(StrUtil.isNotBlank(cfg.getApiKey()), "providers." + name + ".api-key 未配置");

        require(StrUtil.isNotBlank(cfg.getChat().getProtocol()),
                "providers." + name + ".chat.protocol 未配置（anthropic / openai）");
        require(StrUtil.isNotBlank(cfg.getChat().getModel()),
                "providers." + name + ".chat.model 未配置");

        require(StrUtil.isNotBlank(cfg.getEmbedding().getModel()),
                "providers." + name + ".embedding.model 未配置");
        require(cfg.getEmbedding().getDims() > 0,
                "providers." + name + ".embedding.dims 必须 > 0（与 ES 索引维度一致）");

        require(StrUtil.isNotBlank(cfg.getRerank().getProtocol()),
                "providers." + name + ".rerank.protocol 未配置（cohere / qwen3 / ...）");
        require(StrUtil.isNotBlank(cfg.getRerank().getModel()),
                "providers." + name + ".rerank.model 未配置");

        require(StrUtil.isNotBlank(cfg.getContextualization().getTextModel()),
                "providers." + name + ".contextualization.text-model 未配置");
        require(StrUtil.isNotBlank(cfg.getContextualization().getImageModel()),
                "providers." + name + ".contextualization.image-model 未配置");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
