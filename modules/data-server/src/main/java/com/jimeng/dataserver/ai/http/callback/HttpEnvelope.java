package com.jimeng.dataserver.ai.http.callback;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * 脚本那条路（$JM_HTTP）上，出网代理与网关之间的封包（本计划设计第 1 条）。
 * <pre>
 * 请求：[4 字节大端长度][元信息 JSON {method, url, headers: [[名, 值], …]}][脚本的原始请求体]
 * 回包：[4 字节大端长度][元信息 JSON {status, headers, truncated, gatewayError, message}][正文原始字节]
 * </pre>
 * 为什么封包：脚本自己的 Authorization 等请求头要原样发给客户系统，而「代理 → 网关」这一跳的 Authorization 是平台的运行凭证。
 * 把脚本的整个请求装进请求体，两者就不在同一层，不可能冲突，网关的 AuthorizeFilter 也不用改。
 * 不用 base64 JSON：代理容器限 512 MB，50 MB 的回包 base64 后在 Node 里要占三四倍；长度前缀让代理能直接把正文流给脚本。
 */
public final class HttpEnvelope {

    public static final String CONTENT_TYPE = "application/x-jm-http-envelope";
    public static final int META_MAX_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // data-server 的 Jackson 是 2.11.1，不认 record：组件写明 JSON 名（与代理那边的字段名一致）、构造器标 @JsonCreator
    public record RequestMeta(@JsonProperty("method") String method, @JsonProperty("url") String url,
                              @JsonProperty("headers") List<List<String>> headers) {
        @JsonCreator
        public RequestMeta {}
    }

    public record ResponseMeta(@JsonProperty("status") int status, @JsonProperty("headers") List<List<String>> headers,
                               @JsonProperty("truncated") boolean truncated, @JsonProperty("gatewayError") String gatewayError,
                               @JsonProperty("message") String message) {
        @JsonCreator
        public ResponseMeta {}
    }

    public record Decoded(RequestMeta meta, byte[] body) {}

    /** 封包不合规；消息给脚本看。 */
    public static final class EnvelopeException extends IOException {
        public EnvelopeException(String message) {
            super(message);
        }
    }

    private HttpEnvelope() {}

    public static Decoded readRequest(InputStream in, long bodyMax) throws IOException {
        byte[] lenBytes = in.readNBytes(4);
        if (lenBytes.length < 4) throw new EnvelopeException("封包不完整：缺少长度头");
        int len = ByteBuffer.wrap(lenBytes).getInt();
        if (len <= 0 || len > META_MAX_BYTES) throw new EnvelopeException("封包元信息长度不合法：" + len);
        byte[] meta = in.readNBytes(len);
        if (meta.length < len) throw new EnvelopeException("封包不完整：元信息被截断");
        RequestMeta m;
        try {
            m = JSON.readValue(meta, RequestMeta.class);
        } catch (IOException e) {
            throw new EnvelopeException("封包元信息不是合法的 JSON");
        }
        if (m == null || m.method() == null || m.method().isBlank() || m.url() == null || m.url().isBlank()) {
            throw new EnvelopeException("封包缺少 method 或 url");
        }
        if (m.headers() != null && m.headers().stream().anyMatch(h -> h == null || h.size() != 2 || h.get(0) == null || h.get(1) == null)) {
            throw new EnvelopeException("封包请求头格式不合法");
        }
        byte[] body = in.readNBytes((int) Math.min(bodyMax + 1, Integer.MAX_VALUE - 8));
        if (body.length > bodyMax) throw new EnvelopeException("请求体超过 " + (bodyMax / 1024 / 1024) + " MB 上限");
        return new Decoded(m, body);
    }

    public static void writeResponse(OutputStream out, ResponseMeta meta, byte[] body) throws IOException {
        byte[] m = JSON.writeValueAsBytes(meta);
        out.write(ByteBuffer.allocate(4).putInt(m.length).array());
        out.write(m);
        out.write(body);
        out.flush();
    }
}
