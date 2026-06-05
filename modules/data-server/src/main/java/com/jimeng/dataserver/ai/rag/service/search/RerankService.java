package com.jimeng.dataserver.ai.rag.service.search;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.billing.usage.NormalizedUsage;
import com.jimeng.dataserver.ai.billing.usage.UsageExtractor;
import com.jimeng.dataserver.ai.provider.ProviderRegistry;
import com.jimeng.dataserver.ai.provider.spi.RerankHit;
import com.jimeng.dataserver.ai.provider.spi.RerankResult;
import com.jimeng.dataserver.ai.rag.model.SearchResultItem;
import com.jimeng.dataserver.ai.rag.service.chunk.TokenCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class RerankService {

    private final ProviderRegistry providerRegistry;
    private final AiModelCallRecordService recordService;
    private final UsageExtractor usageExtractor;
    private final TokenCounter tokenCounter;

    // @Lazy 打破循环：ProviderRegistry → ChatClient → AiConversationLoop → SkillRuntimeService
    //   → SkillToolExecutorRegistryService → RagSkillToolExecutor → RerankService → ProviderRegistry
    public RerankService(@Lazy ProviderRegistry providerRegistry,
                         AiModelCallRecordService recordService,
                         UsageExtractor usageExtractor,
                         TokenCounter tokenCounter) {
        this.providerRegistry = providerRegistry;
        this.recordService = recordService;
        this.usageExtractor = usageExtractor;
        this.tokenCounter = tokenCounter;
    }

    public List<SearchResultItem> rerank(String query, List<SearchResultItem> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        List<String> docs = candidates.stream()
                .map(c -> c.getContent() == null ? "" : c.getContent())
                .toList();
        RerankResult result;
        long start = System.currentTimeMillis();
        try {
            result = providerRegistry.rerank().rerank(query, docs, topK);
        } catch (Exception e) {
            log.warn("Rerank 失败，退回 RRF 排序: {}", e.getMessage());
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
        // 计费：rerank 按 token 收费（query + 所有候选文档）。响应带 usage 就用真实值，
        // 不带就用 jtokkit 估算；落一行 rag_rerank。记账失败不影响检索主流程。
        safeRecord(result, query, docs, (int) (System.currentTimeMillis() - start));

        List<RerankHit> hits = result.hits();
        List<SearchResultItem> out = new ArrayList<>(hits.size());
        for (RerankHit h : hits) {
            if (h.getIndex() < 0 || h.getIndex() >= candidates.size()) continue;
            SearchResultItem item = candidates.get(h.getIndex());
            item.setRerankScore(h.getRelevanceScore());
            out.add(item);
        }
        return out;
    }

    private void safeRecord(RerankResult result, String query, List<String> docs, int latencyMs) {
        try {
            NormalizedUsage usage;
            boolean estimated;
            NormalizedUsage parsed = parseUsage(result.usageJson());
            if (parsed != null && (parsed.getInputTokens() != null || parsed.getTotalTokens() != null)) {
                // rerank 无 output，部分 provider 只回 total_tokens：缺 input 时用 total 顶上
                if (parsed.getInputTokens() == null) {
                    parsed.setInputTokens(parsed.getTotalTokens());
                }
                usage = parsed;
                estimated = false;
            } else {
                int est = tokenCounter.count(query);
                for (String d : docs) {
                    est += tokenCounter.count(d);
                }
                usage = new NormalizedUsage();
                usage.setInputTokens(est);
                usage.setOutputTokens(0);
                usage.setTotalTokens(est);
                JSONObject raw = new JSONObject();
                raw.set("estimated", true);
                raw.set("input_tokens", est);
                usage.setRawJson(raw.toString());
                estimated = true;
            }
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("biz_type", "rag_rerank");
            note.put("doc_count", docs.size());
            note.put("usage_estimated", estimated);
            recordService.recordComputedCall(
                    providerRegistry.activeProvider(),
                    "rag:rerank",
                    result.model(),
                    "rag_rerank",
                    usage,
                    200,
                    latencyMs,
                    note);
        } catch (Exception e) {
            log.warn("rerank 计费落账失败: {}", e.getMessage());
        }
    }

    private NormalizedUsage parseUsage(String usageJson) {
        if (StrUtil.isBlank(usageJson) || !JSONUtil.isTypeJSON(usageJson)) {
            return null;
        }
        return usageExtractor.extract(JSONUtil.parseObj(usageJson));
    }
}
