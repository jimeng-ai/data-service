package com.jimeng.dataserver.ai.web;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.service.RequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * web_search：调专业搜索 API 返回 {title, url, snippet} 列表。v1 仅实现 anysearch（已实测
 * POST /v1/search -> {code,message,data:{results:[{title,url,snippet,content}]}}）；新增后端 = 加分支。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSearchService {

    private final RequestService requestService;
    private final WebSearchProperties props;

    public List<Map<String, Object>> search(String query, Integer count) {
        String provider = props.getProvider() == null ? "anysearch" : props.getProvider();
        if (!"anysearch".equals(provider)) {
            throw new IllegalStateException("unsupported web-search provider: " + provider);
        }
        int n = count != null && count > 0
                ? count
                : (props.getMaxResults() != null && props.getMaxResults() > 0 ? props.getMaxResults() : 5);

        String url = props.getBaseUrl().replaceAll("/+$", "") + "/v1/search";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + props.getApiKey());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("max_results", n);

        RequestService.HttpResp resp = requestService.post(url, headers, Collections.emptyMap(), body);
        Integer code = resp.getStatusCode();
        if (code == null || code < 200 || code >= 300) {
            throw new RuntimeException("web search HTTP " + code);
        }
        return parseResults(resp.getBody());
    }

    /** 解析 AnySearch 响应 -> [{title,url,snippet}]。结构缺失/异常时返回空表。 */
    static List<Map<String, Object>> parseResults(String bodyStr) {
        List<Map<String, Object>> hits = new ArrayList<>();
        if (bodyStr == null || bodyStr.isBlank()) {
            return hits;
        }
        JSONObject root = JSONUtil.parseObj(bodyStr);
        JSONObject data = root.getJSONObject("data");
        if (data == null) {
            return hits;
        }
        JSONArray results = data.getJSONArray("results");
        if (results == null) {
            return hits;
        }
        for (Object o : results) {
            if (!(o instanceof JSONObject r)) {
                continue;
            }
            String snippet = r.getStr("snippet");
            if (snippet == null || snippet.isBlank()) {
                snippet = r.getStr("content", "");
            }
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("title", r.getStr("title", ""));
            hit.put("url", r.getStr("url", ""));
            hit.put("snippet", snippet);
            hits.add(hit);
        }
        return hits;
    }
}
