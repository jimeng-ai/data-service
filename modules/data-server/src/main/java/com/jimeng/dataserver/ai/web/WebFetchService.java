package com.jimeng.dataserver.ai.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * web_fetch：宿主侧抓取单个公网页面并抽正文文本。先过 {@link WebSsrfGuard}，每个重定向跳转都重新校验，
 * 限重定向跳数 / 体积 / 超时 / 输出长度（与沙箱一致：3 跳 / 2MiB / 15s / 8000 字）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebFetchService {

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TEXT = 8000;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebSsrfGuard ssrfGuard;

    // followRedirects=NEVER：我们手动处理 3xx，使每一跳都过 SSRF 校验。
    // proxy=系统默认：本机若设了 -Dhttp(s).proxyHost 则走代理，否则直连（不设即 NO_PROXY）。
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(TIMEOUT)
            .proxy(ProxySelector.getDefault())
            .build();

    public Map<String, Object> fetch(String url) {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            String reason = ssrfGuard.validate(current);
            if (reason != null) {
                throw new IllegalArgumentException("blocked: " + reason);
            }
            HttpResponse<byte[]> resp;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(current))
                        .timeout(TIMEOUT)
                        .header("User-Agent", "jimeng-ai/web-fetch")
                        .header("Accept", "text/html,*/*")
                        .GET()
                        .build();
                resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            } catch (Exception e) {
                throw new RuntimeException("fetch error: " + e.getMessage(), e);
            }
            int status = resp.statusCode();
            if (status >= 300 && status < 400) {
                String loc = resp.headers().firstValue("location").orElse(null);
                if (loc == null) {
                    break;
                }
                current = URI.create(current).resolve(loc).toString();
                continue;
            }
            byte[] body = resp.body();
            if (body.length > MAX_BYTES) {
                body = Arrays.copyOf(body, MAX_BYTES);
            }
            String ct = resp.headers().firstValue("content-type").orElse("").toLowerCase();
            String raw = new String(body, StandardCharsets.UTF_8);
            String text = ct.contains("html") ? htmlToText(raw) : truncate(raw, MAX_TEXT);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("url", current);
            out.put("status", status);
            out.put("text", text.isBlank() ? "(empty document)" : text);
            return out;
        }
        throw new RuntimeException("too many redirects");
    }

    /** 把 HTML 压成可读文本（零依赖，best-effort）。 */
    static String htmlToText(String html) {
        String s = html
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<head.*?</head>", " ")
                .replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\s+", " ")
                .trim();
        return truncate(s, MAX_TEXT);
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…[truncated]" : s;
    }
}
