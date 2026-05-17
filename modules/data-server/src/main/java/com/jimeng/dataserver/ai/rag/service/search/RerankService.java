package com.jimeng.dataserver.ai.rag.service.search;

import com.jimeng.dataserver.ai.provider.ProviderRegistry;
import com.jimeng.dataserver.ai.provider.spi.RerankHit;
import com.jimeng.dataserver.ai.rag.model.SearchResultItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class RerankService {

    private final ProviderRegistry providerRegistry;

    // @Lazy 打破循环：ProviderRegistry → ChatClient → AiConversationLoop → SkillRuntimeService
    //   → SkillToolExecutorRegistryService → RagSkillToolExecutor → RerankService → ProviderRegistry
    public RerankService(@Lazy ProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    public List<SearchResultItem> rerank(String query, List<SearchResultItem> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        List<String> docs = candidates.stream()
                .map(c -> c.getContent() == null ? "" : c.getContent())
                .toList();
        List<RerankHit> hits;
        try {
            hits = providerRegistry.rerank().rerank(query, docs, topK);
        } catch (Exception e) {
            log.warn("Rerank 失败，退回 RRF 排序: {}", e.getMessage());
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
        List<SearchResultItem> out = new ArrayList<>(hits.size());
        for (RerankHit h : hits) {
            if (h.getIndex() < 0 || h.getIndex() >= candidates.size()) continue;
            SearchResultItem item = candidates.get(h.getIndex());
            item.setRerankScore(h.getRelevanceScore());
            out.add(item);
        }
        return out;
    }
}
