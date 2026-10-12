package com.jimeng.dataserver.ai.http.callback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpEnvelopeTest {

    /** 测试侧按代理的写法组一个请求封包（生产代码只读请求、只写回包）。 */
    static byte[] frame(String metaJson, byte[] body) {
        byte[] meta = metaJson.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(4 + meta.length + body.length);
        b.putInt(meta.length).put(meta).put(body);
        return b.array();
    }

    @Test
    @DisplayName("请求封包：长度头 + 元信息 + 原始请求体（二进制原样）")
    void readsRequest() throws Exception {
        byte[] body = new byte[]{0, 1, (byte) 0xff};
        HttpEnvelope.Decoded d = HttpEnvelope.readRequest(new ByteArrayInputStream(frame(
                "{\"method\":\"POST\",\"url\":\"https://a.example.com/x?t=%7B%7BK_1%7D%7D\",\"headers\":[[\"Authorization\",\"Bearer {{K_1}}\"],[\"X-A\",\"1\"],[\"X-A\",\"2\"]]}",
                body)), 1024);
        assertThat(d.meta().method()).isEqualTo("POST");
        assertThat(d.meta().url()).endsWith("%7B%7BK_1%7D%7D");
        assertThat(d.meta().headers()).containsExactly(List.of("Authorization", "Bearer {{K_1}}"), List.of("X-A", "1"), List.of("X-A", "2"));
        assertThat(d.body()).containsExactly(0, 1, (byte) 0xff);
    }

    @Test
    @DisplayName("坏封包：缺长度头、元信息长度不合法、元信息被截断、不是 JSON、请求体超上限，都报出原因")
    void rejectsBadFrames() {
        assertThatThrownBy(() -> HttpEnvelope.readRequest(new ByteArrayInputStream(new byte[]{0, 0}), 1024))
                .isInstanceOf(HttpEnvelope.EnvelopeException.class);
        assertThatThrownBy(() -> HttpEnvelope.readRequest(new ByteArrayInputStream(ByteBuffer.allocate(4).putInt(70_000).array()), 1024))
                .isInstanceOf(HttpEnvelope.EnvelopeException.class).hasMessageContaining("长度");
        assertThatThrownBy(() -> HttpEnvelope.readRequest(new ByteArrayInputStream(ByteBuffer.allocate(6).putInt(10).put((byte) '{').put((byte) '}').array()), 1024))
                .isInstanceOf(HttpEnvelope.EnvelopeException.class).hasMessageContaining("截断");
        assertThatThrownBy(() -> HttpEnvelope.readRequest(new ByteArrayInputStream(frame("not json", new byte[0])), 1024))
                .isInstanceOf(HttpEnvelope.EnvelopeException.class).hasMessageContaining("JSON");
        assertThatThrownBy(() -> HttpEnvelope.readRequest(new ByteArrayInputStream(frame("{\"method\":\"POST\",\"url\":\"u\"}", new byte[2000])), 1024))
                .isInstanceOf(HttpEnvelope.EnvelopeException.class).hasMessageContaining("请求体");
    }

    @Test
    void rejectsNullOrMissingMetaWithoutEchoingInput() {
        for (String meta : List.of("null", "{}", "{\"method\":\"GET\"}", "{\"method\":\"GET\",\"url\":\"u\",\"headers\":[[\"Authorization\",\"SECRET-SENTINEL\",\"extra\"]]}")) {
            assertThatThrownBy(() -> HttpEnvelope.readRequest(new ByteArrayInputStream(frame(meta, new byte[0])), 1024))
                    .isInstanceOf(HttpEnvelope.EnvelopeException.class).hasMessageNotContaining("SECRET-SENTINEL");
        }
    }

    @Test
    @DisplayName("回包封包：长度头 + 元信息 + 正文原始字节")
    void writesResponse() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpEnvelope.writeResponse(out, new HttpEnvelope.ResponseMeta(201, List.of(List.of("content-type", "image/png")), true, null, null),
                new byte[]{9, 8, 7});
        ByteBuffer b = ByteBuffer.wrap(out.toByteArray());
        int len = b.getInt();
        byte[] meta = new byte[len];
        b.get(meta);
        assertThat(new String(meta, StandardCharsets.UTF_8))
                .contains("\"status\":201").contains("[\"content-type\",\"image/png\"]").contains("\"truncated\":true");
        byte[] rest = new byte[b.remaining()];
        b.get(rest);
        assertThat(rest).containsExactly(9, 8, 7);
    }
}
