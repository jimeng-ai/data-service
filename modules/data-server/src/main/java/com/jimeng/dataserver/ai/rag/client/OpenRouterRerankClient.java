package com.jimeng.dataserver.ai.rag.client;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.dataserver.ai.rag.config.RagProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenRouter Rerank 客户端（Cohere 兼容 schema）。
 * POST {base}/v1/rerank
 *   { "model": "...", "query": "...", "documents": [...], "top_n": K }
 * Response: { "results": [{"index": N, "relevance_score": 0.x}, ...] }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenRouterRerankClient {

    private final RequestService requestService;
    private final RagProperties ragProperties;

    public List<RerankHit> rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty()) return List.of();
        RagProperties.OpenRouter cfg = ragProperties.getOpenrouter();
        if (StrUtil.isBlank(cfg.getApiKey())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "rag.openrouter.api-key 未配置");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getRerankModel());
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", Math.min(topN, documents.size()));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + cfg.getApiKey());
        headers.put("Content-Type", "application/json");
        String url = StrUtil.removeSuffix(cfg.getBaseUrl(), "/") + "/rerank";

        RequestService.HttpResp resp = requestService.post(url, headers, Map.of(), body);
        if (resp.getStatusCode() == null || resp.getStatusCode() < 200 || resp.getStatusCode() >= 300) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "OpenRouter rerank 调用失败: status=" + resp.getStatusCode() + ", body=" + resp.getBody());
        }
        return parseResults(resp.getBody());
    }

    private List<RerankHit> parseResults(String json) {
        Map<?, ?> root = JSONUtil.parseObj(json);
        Object resultsObj = root.get("results");
        List<RerankHit> hits = new ArrayList<>();
        if (!(resultsObj instanceof List<?> list)) return hits;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Object idxObj = m.get("index");
            Object scoreObj = m.get("relevance_score");
            int idx = idxObj instanceof Number ? ((Number) idxObj).intValue() : -1;
            double score = scoreObj instanceof Number ? ((Number) scoreObj).doubleValue() : 0.0;
            hits.add(new RerankHit(idx, score));
        }
        return hits;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RerankHit {
        private int index;
        private double relevanceScore;
    }
}
