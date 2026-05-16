package com.jimeng.dataserver.ai.rag.service.contextualize;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.dataserver.ai.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图片转结构化描述：Claude Sonnet vision，把图片解析成 100-200 字中文描述，作为独立 chunk 索引。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageDescriptionService {

    private final RequestService requestService;
    private final RagProperties ragProperties;

    @Value("${ai-api.claude.api-key}")
    private String claudeApiKey;

    @Value("${ai-api.claude.base-Url}")
    private String claudeBaseUrl;

    public String describe(byte[] imageBytes, String mediaType) {
        if (imageBytes == null || imageBytes.length == 0) return "";
        Map<String, Object> body = buildBody(imageBytes, mediaType);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + claudeApiKey);
        headers.put("Content-Type", "application/json");
        String url = StrUtil.removeSuffix(claudeBaseUrl, "/") + "/v1/messages";

        RequestService.HttpResp resp = requestService.post(url, headers, Map.of(), body);
        if (resp.getStatusCode() == null || resp.getStatusCode() < 200 || resp.getStatusCode() >= 300) {
            log.warn("Image description 调用失败 status={} body={}", resp.getStatusCode(), resp.getBody());
            return "";
        }
        return extractText(resp.getBody());
    }

    private Map<String, Object> buildBody(byte[] bytes, String mediaType) {
        RagProperties.Contextualization cfg = ragProperties.getContextualization();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getImageModel());
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
            log.warn("解析图片描述响应失败: {}", e.getMessage());
            return "";
        }
    }
}
