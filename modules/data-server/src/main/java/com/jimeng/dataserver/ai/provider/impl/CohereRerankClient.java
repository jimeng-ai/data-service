package com.jimeng.dataserver.ai.provider.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties.ProviderConfig;
import com.jimeng.dataserver.ai.provider.spi.RerankClient;
import com.jimeng.dataserver.ai.provider.spi.RerankHit;
import com.jimeng.dataserver.ai.provider.spi.RerankResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cohere 兼容 schema 的 Rerank 客户端（OpenRouter / 任何 Cohere 直连皆可用）。
 * POST {base-url}{endpoint-path}
 *   { "model": "...", "query": "...", "documents": [...], "top_n": K }
 *   Response: { "results": [{"index": N, "relevance_score": 0.x}, ...] }
 */
@Slf4j
public class CohereRerankClient implements RerankClient {

    private final String providerName;
    private final ProviderConfig config;
    private final RequestService requestService;

    public CohereRerankClient(String providerName,
                              ProviderConfig config,
                              RequestService requestService) {
        this.providerName = providerName;
        this.config = config;
        this.requestService = requestService;
    }

    @Override
    public RerankResult rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            return RerankResult.of(List.of(), null, config.getRerank().getModel());
        }
        if (StrUtil.isBlank(config.getApiKey())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "providers." + providerName + ".api-key 未配置");
        }
        if (StrUtil.isBlank(config.getRerank().getModel())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "providers." + providerName + ".rerank.model 未配置");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getRerank().getModel());
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", Math.min(topN, documents.size()));
        if (config.getRerank().getExtraParams() != null) {
            body.putAll(config.getRerank().getExtraParams());
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + config.getApiKey());
        headers.put("Content-Type", "application/json");
        String path = StrUtil.isBlank(config.getRerank().getEndpointPath())
                ? "/rerank" : config.getRerank().getEndpointPath();
        String url = StrUtil.removeSuffix(config.getBaseUrl(), "/") + path;

        RequestService.HttpResp resp = requestService.post(url, headers, Map.of(), body);
        if (resp.getStatusCode() == null || resp.getStatusCode() < 200 || resp.getStatusCode() >= 300) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "rerank 调用失败 provider=" + providerName + " status=" + resp.getStatusCode()
                            + " body=" + resp.getBody());
        }
        return RerankResult.of(parseResults(resp.getBody()), extractUsageJson(resp.getBody()),
                config.getRerank().getModel());
    }

    @Override
    public String providerName() {
        return providerName;
    }

    /** 抽响应里的 usage 对象原文（Cohere 多走按次计费、通常无 usage，返回 null 让上层估算）。 */
    private String extractUsageJson(String json) {
        if (StrUtil.isBlank(json) || !JSONUtil.isTypeJSON(json)) return null;
        Object usage = JSONUtil.parseObj(json).get("usage");
        return usage == null ? null : usage.toString();
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
}
