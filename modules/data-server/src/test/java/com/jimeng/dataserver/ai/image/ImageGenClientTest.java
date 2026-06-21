package com.jimeng.dataserver.ai.image;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ImageGenClientTest {

    @Test
    void normalizeBaseStripsV1AndSlash() {
        assertEquals("https://api.302.ai", ImageGenClient.normalizeBase("https://api.302.ai/v1"));
        assertEquals("https://api.302.ai", ImageGenClient.normalizeBase("https://api.302.ai/v1/"));
        assertEquals("https://x.com", ImageGenClient.normalizeBase("https://x.com/"));
    }

    @Test
    void seedreamBodyHasExpectedFields() {
        JSONObject b = JSONUtil.parseObj(ImageGenClient.buildSeedreamBody("doubao-seedream-5-0", "一只猫"));
        assertEquals("doubao-seedream-5-0", b.getStr("model"));
        assertEquals("2K", b.getStr("size"));
        assertEquals("disabled", b.getStr("sequential_image_generation"));
    }

    @Test
    void openAiBodyDefaultsSize() {
        JSONObject b = JSONUtil.parseObj(ImageGenClient.buildOpenAiBody("gpt-image-2", "a cat", null));
        assertEquals("1024x1024", b.getStr("size"));
        assertEquals(1, b.getInt("n").intValue());
    }

    @Test
    void extractImageBytesDecodesB64() throws Exception {
        ImageGenClient client = new ImageGenClient(new AgentSandboxProperties());
        byte[] raw = {(byte) 0x89, 'P', 'N', 'G'};
        String b64 = Base64.getEncoder().encodeToString(raw);
        byte[] bytes = client.extractImageBytes("{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}");
        assertArrayEquals(raw, bytes);
    }

    @Test
    void extractImageBytesThrowsOnError() {
        ImageGenClient client = new ImageGenClient(new AgentSandboxProperties());
        Exception e = assertThrows(RuntimeException.class,
                () -> client.extractImageBytes("{\"error\":{\"message\":\"quota exceeded\"}}"));
        assertTrue(e.getMessage().contains("quota"));
    }
}
