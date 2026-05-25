package com.jimeng.dataserver.ai.plugin;

import com.jimeng.dataserver.ai.plugin.service.PluginResponseExtractor;
import com.jimeng.persistence.entity.PluginHttpMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginResponseExtractorTest {

    private PluginResponseExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new PluginResponseExtractor();
    }

    private PluginHttpMapping mapping(String extract, Integer maxItems) {
        PluginHttpMapping m = new PluginHttpMapping();
        m.setResponseExtract(extract);
        m.setResponseMaxItems(maxItems);
        return m;
    }

    @Test
    void extract_root_returnsFull() {
        String body = "{\"foo\":\"bar\"}";
        Object out = extractor.extract(body, mapping(null, null));
        assertTrue(out instanceof Map);
        assertEquals("bar", ((Map<?, ?>) out).get("foo"));
    }

    @Test
    void extract_nestedPath() {
        String body = "{\"data\":{\"main\":{\"temp\":25.3}}}";
        Object out = extractor.extract(body, mapping("$.data.main", null));
        assertTrue(out instanceof Map);
        assertEquals(25.3, ((Map<?, ?>) out).get("temp"));
    }

    @Test
    void extract_missingPath_returnsNull() {
        String body = "{\"data\":{}}";
        Object out = extractor.extract(body, mapping("$.data.missing.deep", null));
        assertNull(out);
    }

    @Test
    void extract_arrayIndex() {
        String body = "{\"items\":[{\"id\":1},{\"id\":2}]}";
        Object out = extractor.extract(body, mapping("$.items[0]", null));
        assertTrue(out instanceof Map);
        assertEquals(1, ((Map<?, ?>) out).get("id"));
    }

    @Test
    void extract_arrayTruncation() {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"i\":").append(i).append("}");
        }
        sb.append("]}");

        Object out = extractor.extract(sb.toString(), mapping("$.items", 10));
        // 截断的结果是包了 {items, _truncated, _total, _returned}
        assertTrue(out instanceof Map);
        Map<?, ?> result = (Map<?, ?>) out;
        assertEquals(Boolean.TRUE, result.get("_truncated"));
        assertEquals(100, result.get("_total"));
        assertEquals(10, result.get("_returned"));
        assertEquals(10, ((List<?>) result.get("items")).size());
    }

    @Test
    void extract_nonJsonBody_returnsRaw() {
        Object out = extractor.extract("plain text", mapping(null, null));
        assertEquals("plain text", out);
    }

    @Test
    void extract_emptyBody_returnsNull() {
        assertNull(extractor.extract("", mapping(null, null)));
        assertNull(extractor.extract(null, mapping(null, null)));
    }
}
