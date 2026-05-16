package com.jimeng.dataserver.ai.rag.client;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.dataserver.ai.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenRouter Embeddings 客户端（OpenAI 兼容 schema）。
 * POST {base}/v1/embeddings
 *   { "model": "voyageai/voyage-3-large", "input": [text...] }
 * Response: { "data": [{"embedding":[...], "index":N}], ... }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenRouterEmbeddingClient {

    private final RequestService requestService;
    private final RagProperties ragProperties;

    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        RagProperties.OpenRouter cfg = ragProperties.getOpenrouter();
        if (StrUtil.isBlank(cfg.getApiKey())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "rag.openrouter.api-key 未配置");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getEmbeddingModel());
        body.put("input", texts);
        Map<String, String> headers = buildHeaders(cfg);
        String url = StrUtil.removeSuffix(cfg.getBaseUrl(), "/") + "/embeddings";

        RequestService.HttpResp resp = requestService.post(url, headers, Map.of(), body);
        if (resp.getStatusCode() == null || resp.getStatusCode() < 200 || resp.getStatusCode() >= 300) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "OpenRouter embeddings 调用失败: status=" + resp.getStatusCode() + ", body=" + resp.getBody());
        }
        return parseEmbeddings(resp.getBody(), texts.size(), cfg.getEmbeddingDims());
    }

    private List<float[]> parseEmbeddings(String json, int expected, int dims) {
        Map<?, ?> root = JSONUtil.parseObj(json);
        Object dataObj = root.get("data");
        if (!(dataObj instanceof List<?> dataList) || dataList.isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "OpenRouter embeddings 响应无 data: " + json);
        }
        float[][] ordered = new float[expected][];
        for (Object item : dataList) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Object idxObj = m.get("index");
            int idx = idxObj instanceof Number ? ((Number) idxObj).intValue() : 0;
            Object vec = m.get("embedding");
            if (!(vec instanceof List<?> vList)) continue;
            float[] vector = new float[vList.size()];
            for (int i = 0; i < vList.size(); i++) {
                vector[i] = ((Number) vList.get(i)).floatValue();
            }
            if (vector.length != dims) {
                log.warn("embedding 维度不匹配 expected={}, actual={}", dims, vector.length);
            }
            if (idx >= 0 && idx < ordered.length) ordered[idx] = vector;
        }
        return List.of(ordered);
    }

    private Map<String, String> buildHeaders(RagProperties.OpenRouter cfg) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + cfg.getApiKey());
        h.put("Content-Type", "application/json");
        return h;
    }
}
