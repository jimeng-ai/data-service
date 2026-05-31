package com.jimeng.dataserver.ai.provider.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.dataserver.ai.claude.service.AiModelCallRecordService;
import com.jimeng.dataserver.ai.provider.config.AiProviderProperties.ProviderConfig;
import com.jimeng.dataserver.ai.provider.spi.ContextualizationClient;
import com.jimeng.dataserver.ai.provider.spi.ContextualizationClientException;
import com.jimeng.dataserver.ai.provider.spi.ContextualizationResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 上下文化默认实现：走 anthropic /v1/messages 协议；当 chat.protocol=anthropic
 * 且 contextualization.use-prompt-cache=true 时附加 ephemeral cache_control + anthropic-beta header。
 *
 * <p>chat.protocol=openai 时降级为普通调用（无 prompt cache）。
 */
@Slf4j
public class DefaultContextualizationClient implements ContextualizationClient {

    private static final String ANTHROPIC = "anthropic";

    private final String providerName;
    private final ProviderConfig config;
    private final RequestService requestService;
    /** 计费记录；可能为 null（极端情况下不影响 contextualization 主流程）。 */
    private final AiModelCallRecordService recordService;

    public DefaultContextualizationClient(String providerName,
                                          ProviderConfig config,
                                          RequestService requestService,
                                          AiModelCallRecordService recordService) {
        this.providerName = providerName;
        this.config = config;
        this.requestService = requestService;
        this.recordService = recordService;
    }

    @Override
    public ContextualizationResult generateContext(String fullDocument, String chunkContent) {
        if (StrUtil.isBlank(fullDocument) || StrUtil.isBlank(chunkContent)) return ContextualizationResult.empty();
        String textModel = config.getContextualization().getTextModel();
        if (StrUtil.isBlank(textModel)) {
            log.warn("providers.{}.contextualization.text-model 未配置，跳过 contextualization", providerName);
            return ContextualizationResult.empty();
        }
        Map<String, Object> body = isAnthropicProtocol()
                ? buildAnthropicTextBody(fullDocument, chunkContent, textModel)
                : buildOpenAiTextBody(fullDocument, chunkContent, textModel);
        // 文本上下文化不在此自行落库：usage 上抛给 ContextualizationService 按整篇文档汇总成一行。
        RequestService.HttpResp resp = isAnthropicProtocol()
                ? rawAnthropic(body)
                : rawOpenAi(body);
        String text = isAnthropicProtocol() ? handleAnthropicResponse(resp) : handleOpenAiResponse(resp);
        return new ContextualizationResult(text, extractUsageJson(resp.getBody()), textModel);
    }

    @Override
    public String describeImage(byte[] imageBytes, String mediaType) {
        if (imageBytes == null || imageBytes.length == 0) return "";
        String imageModel = config.getContextualization().getImageModel();
        if (StrUtil.isBlank(imageModel)) {
            log.warn("providers.{}.contextualization.image-model 未配置，跳过 image description", providerName);
            return "";
        }
        if (isAnthropicProtocol()) {
            return callAnthropic(buildAnthropicImageBody(imageBytes, mediaType, imageModel), "rag_image_desc");
        }
        return callOpenAi(buildOpenAiImageBody(imageBytes, mediaType, imageModel), "rag_image_desc");
    }

    @Override
    public String providerName() {
        return providerName;
    }

    // -------------------------------------------------- HTTP

    private String callAnthropic(Map<String, Object> body, String bizType) {
        Map<String, String> headers = buildHeaders(true);
        String url = StrUtil.removeSuffix(config.getBaseUrl(), "/") + "/messages";
        RequestService.HttpResp resp = recordedPost(url, headers, body, bizType);
        return handleAnthropicResponse(resp);
    }

    private String callOpenAi(Map<String, Object> body, String bizType) {
        Map<String, String> headers = buildHeaders(false);
        String url = StrUtil.removeSuffix(config.getBaseUrl(), "/") + "/chat/completions";
        RequestService.HttpResp resp = recordedPost(url, headers, body, bizType);
        return handleOpenAiResponse(resp);
    }

    /** 文本上下文化用：不落库的裸 POST（usage 由上层汇总记账）。 */
    private RequestService.HttpResp rawAnthropic(Map<String, Object> body) {
        Map<String, String> headers = buildHeaders(true);
        String url = StrUtil.removeSuffix(config.getBaseUrl(), "/") + "/messages";
        return requestService.post(url, headers, Map.of(), body);
    }

    private RequestService.HttpResp rawOpenAi(Map<String, Object> body) {
        Map<String, String> headers = buildHeaders(false);
        String url = StrUtil.removeSuffix(config.getBaseUrl(), "/") + "/chat/completions";
        return requestService.post(url, headers, Map.of(), body);
    }

    /** 从响应体抽出 usage 对象原文（无 / 非 JSON 时返回 null）。 */
    private String extractUsageJson(String json) {
        if (StrUtil.isBlank(json) || !JSONUtil.isTypeJSON(json)) {
            return null;
        }
        Object usage = JSONUtil.parseObj(json).get("usage");
        return usage == null ? null : usage.toString();
    }

    /**
     * 包了计费记录的 POST：把每次 contextualization / 图片描述调用落到 ai_model_call_log。
     * 记录失败绝不影响主流程；response 体含 usage，由 RecordService 解析算 cost。
     *
     * <p>落库用紧凑 meta 体（不含整篇文档前缀）：contextualization 对每个 chunk 都把整篇文档作为
     * cached 前缀重发，若把原始 body 入 content 表，一份千 chunk 文档会复制上千份全文，必须 redact。
     */
    private RequestService.HttpResp recordedPost(String url, Map<String, String> headers,
                                                 Map<String, Object> body, String bizType) {
        Long logId = safeRecordRequest(body, headers, url, bizType);
        long start = System.currentTimeMillis();
        try {
            RequestService.HttpResp resp = requestService.post(url, headers, Map.of(), body);
            safeRecordResponse(logId, resp.getStatusCode(), resp.getBody(), start);
            return resp;
        } catch (RuntimeException e) {
            safeRecordException(logId, e, start);
            throw e;
        }
    }

    private Long safeRecordRequest(Map<String, Object> body, Map<String, String> headers,
                                   String url, String bizType) {
        if (recordService == null) {
            return null;
        }
        try {
            Object model = body.get("model");
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("model", model);
            meta.put("max_tokens", body.get("max_tokens"));
            meta.put("biz_type", bizType);
            meta.put("scene_code", providerName);
            return recordService.recordRequest(meta, headers, providerName, url,
                    model == null ? null : String.valueOf(model));
        } catch (Exception e) {
            log.warn("contextualization 计费 recordRequest 失败 provider={}: {}", providerName, e.getMessage());
            return null;
        }
    }

    private void safeRecordResponse(Long logId, Integer status, String respBody, long startMs) {
        if (recordService == null || logId == null) {
            return;
        }
        try {
            recordService.recordResponse(logId, status, respBody, (int) (System.currentTimeMillis() - startMs));
        } catch (Exception e) {
            log.warn("contextualization 计费 recordResponse 失败 provider={}: {}", providerName, e.getMessage());
        }
    }

    private void safeRecordException(Long logId, Throwable t, long startMs) {
        if (recordService == null || logId == null) {
            return;
        }
        try {
            recordService.recordException(logId, t, (int) (System.currentTimeMillis() - startMs));
        } catch (Exception e) {
            log.warn("contextualization 计费 recordException 失败 provider={}: {}", providerName, e.getMessage());
        }
    }

    private String handleAnthropicResponse(RequestService.HttpResp resp) {
        Integer status = resp.getStatusCode();
        if (status == null || status < 200 || status >= 300) {
            log.warn("Contextualization (anthropic) 调用失败 provider={} status={} body={}",
                    providerName, status, resp.getBody());
            if (status != null && status >= 400 && status < 500) {
                throw new ContextualizationClientException(status, resp.getBody());
            }
            return "";
        }
        return extractAnthropicText(resp.getBody());
    }

    private String handleOpenAiResponse(RequestService.HttpResp resp) {
        Integer status = resp.getStatusCode();
        if (status == null || status < 200 || status >= 300) {
            log.warn("Contextualization (openai) 调用失败 provider={} status={} body={}",
                    providerName, status, resp.getBody());
            if (status != null && status >= 400 && status < 500) {
                throw new ContextualizationClientException(status, resp.getBody());
            }
            return "";
        }
        return extractOpenAiText(resp.getBody());
    }

    // -------------------------------------------------- body builders (anthropic)

    private Map<String, Object> buildAnthropicTextBody(String fullDocument, String chunkContent, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", config.getContextualization().getMaxOutputTokens());
        body.put("system", "You are an assistant that writes brief Chinese contextual descriptions for document chunks.");

        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> docBlock = new LinkedHashMap<>();
        docBlock.put("type", "text");
        docBlock.put("text", "<document>\n" + fullDocument + "\n</document>");
        if (shouldUsePromptCache()) {
            Map<String, Object> cache = new LinkedHashMap<>();
            cache.put("type", "ephemeral");
            docBlock.put("cache_control", cache);
        }
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

    private Map<String, Object> buildAnthropicImageBody(byte[] bytes, String mediaType, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 400);
        body.put("system", "You are an assistant that writes structured Chinese descriptions of images for RAG indexing.");

        Map<String, Object> imageBlock = new LinkedHashMap<>();
        imageBlock.put("type", "image");
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "base64");
        source.put("media_type", StrUtil.isBlank(mediaType) ? "image/png" : mediaType);
        source.put("data", Base64.getEncoder().encodeToString(bytes));
        imageBlock.put("source", source);

        Map<String, Object> instr = new LinkedHashMap<>();
        instr.put("type", "text");
        instr.put("text", "请用 100-200 字结构化描述这张图片：\n"
                + "1) 图片类型（图表 / 截图 / 示意图 / 照片）\n"
                + "2) 核心主体或场景\n"
                + "3) 关键数据要点或文字\n"
                + "只返回描述本身，不要 markdown 包裹或前言。");

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", List.of(imageBlock, instr));
        body.put("messages", List.of(userMsg));
        return body;
    }

    // -------------------------------------------------- body builders (openai fallback)

    private Map<String, Object> buildOpenAiTextBody(String fullDocument, String chunkContent, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_completion_tokens", config.getContextualization().getMaxOutputTokens());

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", "You are an assistant that writes brief Chinese contextual descriptions for document chunks.");
        messages.add(systemMsg);

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", "<document>\n" + fullDocument + "\n</document>\n\n"
                + "以下是需要在整篇文档中定位的片段：\n<chunk>\n" + chunkContent + "\n</chunk>\n\n"
                + "请用 50-100 字简洁中文描述此片段在整篇文档中的位置与作用，以提升检索效果。"
                + "只返回简洁的定位描述，不要任何前言或 markdown。");
        messages.add(userMsg);
        body.put("messages", messages);
        return body;
    }

    private Map<String, Object> buildOpenAiImageBody(byte[] bytes, String mediaType, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_completion_tokens", 400);

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", "You are an assistant that writes structured Chinese descriptions of images for RAG indexing.");
        messages.add(systemMsg);

        String mime = StrUtil.isBlank(mediaType) ? "image/png" : mediaType;
        String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);

        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", Map.of("url", dataUrl));

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", "请用 100-200 字结构化描述这张图片：\n"
                + "1) 图片类型（图表 / 截图 / 示意图 / 照片）\n"
                + "2) 核心主体或场景\n"
                + "3) 关键数据要点或文字\n"
                + "只返回描述本身，不要 markdown 包裹或前言。");

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", List.of(imagePart, textPart));
        messages.add(userMsg);
        body.put("messages", messages);
        return body;
    }

    // -------------------------------------------------- helpers

    private boolean isAnthropicProtocol() {
        return ANTHROPIC.equalsIgnoreCase(config.getChat().getProtocol());
    }

    private boolean shouldUsePromptCache() {
        return isAnthropicProtocol() && config.getContextualization().isUsePromptCache();
    }

    private Map<String, String> buildHeaders(boolean anthropic) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + config.getApiKey());
        h.put("Content-Type", "application/json");
        if (anthropic && shouldUsePromptCache()) {
            h.put("anthropic-beta", "prompt-caching-2024-07-31");
        }
        return h;
    }

    @SuppressWarnings("unchecked")
    private String extractAnthropicText(String json) {
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
            log.warn("解析 anthropic 响应失败 provider={} err={}", providerName, e.getMessage());
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private String extractOpenAiText(String json) {
        if (StrUtil.isBlank(json)) return "";
        try {
            Map<String, Object> root = JSONUtil.parseObj(json).toBean(Map.class);
            Object choicesObj = root.get("choices");
            if (!(choicesObj instanceof List<?> list) || list.isEmpty()) return "";
            Object first = list.get(0);
            if (!(first instanceof Map<?, ?> choice)) return "";
            Object msg = choice.get("message");
            if (!(msg instanceof Map<?, ?> message)) return "";
            Object content = message.get("content");
            return content == null ? "" : String.valueOf(content).trim();
        } catch (Exception e) {
            log.warn("解析 openai 响应失败 provider={} err={}", providerName, e.getMessage());
            return "";
        }
    }
}
