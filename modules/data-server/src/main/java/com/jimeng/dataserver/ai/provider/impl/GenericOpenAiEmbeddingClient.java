package com.jimeng.dataserver.ai.provider.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.dataserver.ai.claude.service.AiModelCallRecordService;
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
    /** 计费记录；可能为 null（极端情况下不影响 embedding 主流程）。 */
    private final AiModelCallRecordService recordService;

    public GenericOpenAiEmbeddingClient(String providerName,
                                        ProviderConfig config,
                                        RequestService requestService,
                                        AiModelCallRecordService recordService) {
        this.providerName = providerName;
        this.config = config;
        this.requestService = requestService;
        this.recordService = recordService;
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

        // 计费记录：embedding 也消耗 token、按输入收费，必须落 ai_model_call_log。
        // 注意 EmbeddingService 已做 Redis 缓存，命中的不会走到这里，所以记到的都是真实付费调用。
        Long logId = safeRecordRequest(texts.size(), headers, url);
        long start = System.currentTimeMillis();
        RequestService.HttpResp resp;
        try {
            resp = requestService.post(url, headers, Map.of(), body);
        } catch (RuntimeException e) {
            safeRecordException(logId, e, start);
            throw e;
        }
        safeRecordResponse(logId, resp.getStatusCode(), resp.getBody(), start);
        if (resp.getStatusCode() == null || resp.getStatusCode() < 200 || resp.getStatusCode() >= 300) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "embedding 调用失败 provider=" + providerName
                            + " status=" + resp.getStatusCode() + " body=" + resp.getBody());
        }
        return parseEmbeddings(resp.getBody(), texts.size());
    }

    // -------------------------------------------------- 计费记录（失败绝不影响 embedding 主流程）

    /**
     * 记录请求。落库的是紧凑 meta 体（仅 model + 输入条数），不存全部 input 文本，
     * 避免一批 100 个 chunk 的原文灌进 content 表。tenant/user 由 RecordService 从
     * TenantContext / 请求头解析（入库链路靠 Consumer 设置的 TenantContext 兜底）。
     */
    private Long safeRecordRequest(int inputCount, Map<String, String> headers, String url) {
        if (recordService == null) {
            return null;
        }
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("model", config.getEmbedding().getModel());
            meta.put("input_count", inputCount);
            meta.put("biz_type", "rag_embedding");
            meta.put("scene_code", providerName);
            return recordService.recordRequest(meta, headers, providerName, url, config.getEmbedding().getModel());
        } catch (Exception e) {
            log.warn("embedding 计费 recordRequest 失败 provider={}: {}", providerName, e.getMessage());
            return null;
        }
    }

    private void safeRecordResponse(Long logId, Integer status, String body, long startMs) {
        if (recordService == null || logId == null) {
            return;
        }
        try {
            recordService.recordResponse(logId, status, body, (int) (System.currentTimeMillis() - startMs));
        } catch (Exception e) {
            log.warn("embedding 计费 recordResponse 失败 provider={}: {}", providerName, e.getMessage());
        }
    }

    private void safeRecordException(Long logId, Throwable t, long startMs) {
        if (recordService == null || logId == null) {
            return;
        }
        try {
            recordService.recordException(logId, t, (int) (System.currentTimeMillis() - startMs));
        } catch (Exception e) {
            log.warn("embedding 计费 recordException 失败 provider={}: {}", providerName, e.getMessage());
        }
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
