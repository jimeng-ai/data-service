package com.jimeng.dataserver.ai.image;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话路径的生图客户端：复用 agent.sandbox.image-gen 配置，调 302.ai 上游（seedream / openai 兼容），
 * 返回图片字节。与 sidecar src/mcp/imageGenTool.ts 同构（同 endpoint / 同请求体）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageGenClient {

    private static final Duration GEN_TIMEOUT = Duration.ofSeconds(150);

    private final AgentSandboxProperties props;

    // proxy(getDefault()) 让本地 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=4780 生效，绕过本机对
    // api.302.ai 的 DNS 污染；生产无系统代理时即直连。http 字段带初始化，不进 @RequiredArgsConstructor。
    private final HttpClient http = HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** 生图三件套（base-url/auth-token/model）齐全才算启用。 */
    public boolean enabled() {
        AgentSandboxProperties.ImageGen ig = props.getImageGen();
        return ig != null && StrUtil.isAllNotBlank(ig.getBaseUrl(), ig.getAuthToken(), ig.getModel());
    }

    /** 生成 count 张图，返回每张图片的字节；任一张失败即抛异常。 */
    public List<byte[]> generate(String prompt, String size, int count) throws Exception {
        AgentSandboxProperties.ImageGen ig = props.getImageGen();
        boolean seedream = "seedream".equalsIgnoreCase(StrUtil.blankToDefault(ig.getProvider(), "openai"));
        List<byte[]> images = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String json = seedream
                    ? postJson(seedreamUrl(ig), authValue(ig), buildSeedreamBody(ig.getModel(), prompt))
                    : postJson(openAiUrl(ig), authValue(ig), buildOpenAiBody(ig.getModel(), prompt, size));
            images.add(extractImageBytes(json));
        }
        return images;
    }

    // ---- URL / auth ----
    static String normalizeBase(String baseUrl) {
        return baseUrl.replaceAll("/+$", "").replaceAll("(?i)/v1$", "");
    }

    private static String seedreamUrl(AgentSandboxProperties.ImageGen ig) {
        return normalizeBase(ig.getBaseUrl()) + "/doubao/images/generations";
    }

    private static String openAiUrl(AgentSandboxProperties.ImageGen ig) {
        return normalizeBase(ig.getBaseUrl()) + "/v1/images/generations";
    }

    private static String authValue(AgentSandboxProperties.ImageGen ig) {
        String scheme = StrUtil.blankToDefault(ig.getAuthScheme(), "bearer");
        return "api-key".equalsIgnoreCase(scheme) ? ig.getAuthToken() : "Bearer " + ig.getAuthToken();
    }

    // ---- 请求体（与 sidecar imageGenTool.ts 同构）----
    static String buildSeedreamBody(String model, String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("size", "2K");                               // seedream 用 2K/4K 而非像素；v1 固定 2K（同 sidecar 默认）
        body.put("response_format", "url");
        body.put("watermark", false);
        body.put("sequential_image_generation", "disabled");  // 每次出 1 张，多张交给外层 count 循环
        return JSONUtil.toJsonStr(body);
    }

    static String buildOpenAiBody(String model, String prompt, String size) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", StrUtil.blankToDefault(size, "1024x1024"));
        body.put("quality", "medium");
        return JSONUtil.toJsonStr(body);
    }

    private String postJson(String url, String auth, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(GEN_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", auth)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("生图接口 HTTP " + resp.statusCode() + ": " + StrUtil.maxLength(resp.body(), 300));
        }
        return resp.body();
    }

    /** 解析 {data:[{url|b64_json}], error:{message}}；优先 b64_json，否则下载 url。包私有便于单测。 */
    byte[] extractImageBytes(String json) throws Exception {
        JSONObject obj = JSONUtil.parseObj(json);
        JSONObject error = obj.getJSONObject("error");
        if (error != null && StrUtil.isNotBlank(error.getStr("message"))) {
            throw new RuntimeException("生图失败: " + error.getStr("message"));
        }
        JSONArray data = obj.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            throw new RuntimeException("生图接口未返回图片");
        }
        JSONObject first = data.getJSONObject(0);
        String b64 = first.getStr("b64_json");
        if (StrUtil.isNotBlank(b64)) {
            return Base64.getDecoder().decode(b64);
        }
        String url = first.getStr("url");
        if (StrUtil.isBlank(url)) {
            throw new RuntimeException("生图返回项既无 b64_json 也无 url");
        }
        return downloadBytes(url);
    }

    private byte[] downloadBytes(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(GEN_TIMEOUT).GET().build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("下载生成图片失败 HTTP " + resp.statusCode());
        }
        return resp.body();
    }
}
