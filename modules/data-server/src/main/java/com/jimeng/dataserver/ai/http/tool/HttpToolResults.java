package com.jimeng.dataserver.ai.http.tool;

import com.jimeng.dataserver.ai.http.HttpGatewayResult;
import com.jimeng.dataserver.ai.web.HtmlText;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 网关结果 → 给模型的形状（设计文档 §4.2）。两个平面共用这一份，模型看到的是同样的字节：
 * 沙箱的 MCP 工具只是把 /tool 回调返回的这个 Map 原样 JSON 化。
 */
public final class HttpToolResults {

    private static final Set<String> MAIN_HEADERS = Set.of("content-type", "location", "retry-after", "content-disposition",
            "x-request-id", "etag", "last-modified");
    static final String CLIP_NOTE = "正文超过上限已截断：只看到了前面一部分，不要据此下「总共 / 最多 / 没有」之类的结论";

    private HttpToolResults() {}

    public static Map<String, Object> rejected(int status, String code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("gateway_error", code);
        out.put("message", message);
        return out;
    }

    /** 生产工具应先调用 HttpGateway.executeTool，再传 asText=false，避免抹值后再次转换。 */
    public static Map<String, Object> toModelPayload(HttpGatewayResult r, boolean asText, int textMaxChars) {
        if (r.gatewayError() != null) return rejected(r.status(), r.gatewayError(), r.message());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.status());
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> h : r.headers()) {
            String k = h.getKey().toLowerCase(Locale.ROOT);
            if ((MAIN_HEADERS.contains(k) || k.startsWith("x-ratelimit-")) && !headers.containsKey(k)) headers.put(k, h.getValue());
        }
        if (!headers.isEmpty()) out.put("headers", headers);
        String contentType = r.header("content-type");
        String text = decodeText(r.body(), contentType);
        boolean clipped = false;
        if (text == null) {
            out.put("binary", Map.of("content_type", contentType == null ? "" : contentType, "bytes", r.body().length));
        } else {
            if (asText && HtmlText.isHtml(contentType, text)) text = HtmlText.toText(text);
            if (text.length() > textMaxChars) {
                int end = Math.max(0, textMaxChars);
                if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                text = text.substring(0, end);
                clipped = true;
            }
            out.put("body", text);
        }
        if (clipped || r.truncated()) {
            out.put("truncated", true);
            out.put("note", CLIP_NOTE);
        }
        return out;
    }

    /** 网关转换与模型展示共享同一文本 / 二进制判定；null 表示二进制。 */
    public static String decodeText(byte[] body, String contentType) {
        return isTextual(contentType) ? new String(body, StandardCharsets.UTF_8) : strictUtf8(body);
    }

    private static boolean isTextual(String contentType) {
        if (contentType == null) return false;
        String t = contentType.toLowerCase(Locale.ROOT);
        return t.startsWith("text/") || t.contains("json") || t.contains("xml") || t.contains("javascript")
                || t.contains("x-www-form-urlencoded") || t.contains("yaml") || t.contains("csv");
    }

    /** 没写类型或写成二进制的：严格 UTF-8 且没有 NUL 才当文本，否则 null。 */
    private static String strictUtf8(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
