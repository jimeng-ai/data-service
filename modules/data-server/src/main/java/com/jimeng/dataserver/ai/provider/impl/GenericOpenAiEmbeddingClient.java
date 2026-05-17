package com.jimeng.dataserver.ai.provider.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties.ProviderConfig;
import com.jimeng.dataserver.ai.provider.spi.EmbeddingClient;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 schema 的嵌入客户端：POST {base-url}{endpoint-path}
 *   { "model": "...", "input": [text...] }
 *   Response: { "data": [{"embedding":[...], "index":N}], ... }
 *
 * <p>OpenRouter 与 302.ai 的 /v1/embeddings 都走这套 schema。
 */
@Slf4j
public class GenericOpenAiEmbeddingClient implements EmbeddingClient {

    private final String providerName;
    private final ProviderConfig config;
    private final RequestService requestService;

    public GenericOpenAiEmbeddingClient(String providerName,
                                        ProviderConfig config,
                                        RequestService requestService) {
        this.providerName = providerName;
        this.config = config;
        this.requestService = requestService;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        if (StrUtil.isBlank(config.getApiKey())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "providers." + providerName + ".api-key 未配置");
        }
        if (StrUtil.isBlank(config.getEmbedding().getModel())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "providers." + providerName + ".embedding.model 未配置");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getEmbedding().getModel());
        body.put("input", texts);
        Map<String, String> headers = buildHeaders();
        String url = buildUrl();

        RequestService.HttpResp resp = requestService.post(url, headers, Map.of(), body);
        if (resp.getStatusCode() == null || resp.getStatusCode() < 200 || resp.getStatusCode() >= 300) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "embedding 调用失败 provider=" + providerName
                            + " status=" + resp.getStatusCode() + " body=" + resp.getBody());
        }
        return parseEmbeddings(resp.getBody(), texts.size());
    }

    @Override
    public int dims() {
        return config.getEmbedding().getDims();
    }

    @Override
    public String modelId() {
        return config.getEmbedding().getModel();
    }

    @Override
    public String providerName() {
        return providerName;
    }

    private List<float[]> parseEmbeddings(String json, int expected) {
        Map<?, ?> root = JSONUtil.parseObj(json);
        Object dataObj = root.get("data");
        if (!(dataObj instanceof List<?> dataList) || dataList.isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "embedding 响应无 data provider=" + providerName + " body=" + json);
        }
        float[][] ordered = new float[expected][];
        int dims = dims();
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
                log.warn("embedding 维度不匹配 provider={} model={} expected={} actual={}",
                        providerName, modelId(), dims, vector.length);
            }
            if (idx >= 0 && idx < ordered.length) ordered[idx] = vector;
        }
        return List.of(ordered);
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + config.getApiKey());
        h.put("Content-Type", "application/json");
        return h;
    }

    private String buildUrl() {
        String path = StrUtil.isBlank(config.getEmbedding().getEndpointPath())
                ? "/embeddings" : config.getEmbedding().getEndpointPath();
        return StrUtil.removeSuffix(config.getBaseUrl(), "/") + path;
    }
}
