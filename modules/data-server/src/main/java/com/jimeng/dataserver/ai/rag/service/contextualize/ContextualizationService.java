package com.jimeng.dataserver.ai.rag.service.contextualize;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.dataserver.ai.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Contextual Retrieval：把整篇文档作为 cached 前缀，对每个 chunk 调 Claude Haiku
 * 生成 50-100 字"该片段在整篇中的定位"，prepend 到 chunk 文本里用于 BM25 / Embedding。
 *
 * <p>关键技巧：cache_control=ephemeral 让整篇文档在 5min 内复用，避免每个 chunk 都重发整篇。
 * 单文档串行调用以保证 cache hit；不同文档之间可并发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextualizationService {

    private final RequestService requestService;
    private final RagProperties ragProperties;

    @Value("${ai-api.claude.api-key}")
    private String claudeApiKey;

    @Value("${ai-api.claude.base-Url}")
    private String claudeBaseUrl;

    /** 4xx 类错误（鉴权、余额、参数等）连续出现达到此阈值即 fail-fast，避免在已知会失败的请求上空转。 */
    private static final int CLIENT_ERROR_FAIL_FAST_THRESHOLD = 3;

    public String generateContext(String fullDocument, String chunkContent) {
        if (!ragProperties.getContextualization().isEnabled()) return "";
        if (StrUtil.isBlank(fullDocument) || StrUtil.isBlank(chunkContent)) return "";

        Map<String, Object> body = buildBody(fullDocument, chunkContent);
        Map<String, String> headers = buildHeaders();
        String url = StrUtil.removeSuffix(claudeBaseUrl, "/") + "/v1/messages";

        RequestService.HttpResp resp = requestService.post(url, headers, Map.of(), body);
        Integer status = resp.getStatusCode();
        if (status == null || status < 200 || status >= 300) {
            log.warn("Contextualization 调用失败 status={} body={}", status, resp.getBody());
            // 4xx 是"重试也没用"的错（401 鉴权 / 402 余额 / 403 / 429 限流），抛出去让上游 fail-fast。
            // 5xx / null 仍按"软失败"处理，return "" 让单个 chunk 退化成无 context 继续。
            if (status != null && status >= 400 && status < 500) {
                throw new ContextualizationClientException(status, resp.getBody());
            }
            return "";
        }
        return extractText(resp.getBody());
    }

    /**
     * 给一组 chunks 串行生成 context（保证 prompt cache 命中）。
     *
     * <p>遇到 4xx 累计 {@value #CLIENT_ERROR_FAIL_FAST_THRESHOLD} 次即抛出，让 Rabbit retry 接管，
     * 避免一份 1000 chunk 的文档把 401/402 重复请求 1000 遍。
     */
    public List<String> generateContexts(String fullDocument, List<String> chunks) {
        List<String> out = new ArrayList<>(chunks.size());
        int consecutiveClientErrors = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            try {
                out.add(generateContext(fullDocument, chunk));
                consecutiveClientErrors = 0;
            } catch (ContextualizationClientException e) {
                consecutiveClientErrors++;
                log.warn("chunk[{}/{}] contextualization 4xx status={}（连续 {} 次）",
                        i + 1, chunks.size(), e.getStatus(), consecutiveClientErrors);
                if (consecutiveClientErrors >= CLIENT_ERROR_FAIL_FAST_THRESHOLD) {
                    throw new ServiceException(
                            ExceptionCode.SERVICE_UNAVAILABLE,
                            "Contextualization 连续 " + consecutiveClientErrors +
                                    " 次返回 " + e.getStatus() +
                                    "，疑似 OpenRouter 鉴权/余额/限流问题，已 fail-fast 终止本次入库。body=" +
                                    StrUtil.maxLength(e.getBody() == null ? "" : e.getBody(), 200));
                }
                out.add("");
            } catch (Exception e) {
                log.warn("chunk[{}/{}] contextualization 失败，使用原文 fallback: {}",
                        i + 1, chunks.size(), e.getMessage());
                out.add("");
            }
        }
        return out;
    }

    /** 内部异常：标记 contextualization 拿到 4xx，需要上游决定是否 fail-fast。 */
    private static class ContextualizationClientException extends RuntimeException {
        private final int status;
        private final String body;

        ContextualizationClientException(int status, String body) {
            super("contextualization client error: " + status);
            this.status = status;
            this.body = body;
        }

        int getStatus() { return status; }
        String getBody() { return body; }
    }

    private Map<String, Object> buildBody(String fullDocument, String chunkContent) {
        RagProperties.Contextualization cfg = ragProperties.getContextualization();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getTextModel());
        body.put("max_tokens", cfg.getMaxOutputTokens());
        body.put("system", "You are an assistant that writes brief Chinese contextual descriptions for document chunks.");

        // user message: document block (cached) + chunk + instruction
        List<Map<String, Object>> content = new ArrayList<>();

        Map<String, Object> docBlock = new LinkedHashMap<>();
        docBlock.put("type", "text");
        docBlock.put("text", "<document>\n" + fullDocument + "\n</document>");
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("type", "ephemeral");
        docBlock.put("cache_control", cache);
        content.add(docBlock);

        Map<String, Object> instr = new LinkedHashMap<>();
        instr.put("type", "text");
        instr.put("text", "以下是需要在整篇文档中定位的片段：\n<chunk>\n" + chunkContent + "\n</chunk>\n\n"
                + "请用 50-100 字简洁中文描述此片段在整篇文档中的位置与作用，以提升检索效果。"
                + "只返回简洁的定位描述，不要任何前言或 markdown。");
        content.add(instr);

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", content);
        body.put("messages", List.of(userMsg));
        return body;
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + claudeApiKey);
        h.put("Content-Type", "application/json");
        h.put("anthropic-beta", "prompt-caching-2024-07-31");
        return h;
    }

    @SuppressWarnings("unchecked")
    private String extractText(String json) {
        if (StrUtil.isBlank(json)) return "";
        try {
            Map<String, Object> root = JSONUtil.parseObj(json).toBean(Map.class);
            Object contentObj = root.get("content");
            if (!(contentObj instanceof List<?> list)) return "";
            StringBuilder sb = new StringBuilder();
            for (Object block : list) {
                if (!(block instanceof Map<?, ?> m)) continue;
                if ("text".equals(m.get("type"))) {
                    Object t = m.get("text");
                    if (t != null) sb.append(t);
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("解析 Claude 响应失败: {}", e.getMessage());
            return "";
        }
    }
}
