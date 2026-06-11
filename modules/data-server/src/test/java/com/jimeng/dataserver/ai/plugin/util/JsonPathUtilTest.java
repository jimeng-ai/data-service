package com.jimeng.dataserver.ai.plugin.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.jimeng.common.core.utils.CommonUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonPathUtilTest {

    private JsonNode parse(String s) throws Exception {
        return CommonUtil.getObjectMapper().readTree(s);
    }

    @Test
    void nestedObjectPath() throws Exception {
        JsonNode root = parse("{\"data\":{\"token\":\"abc\"}}");
        assertEquals("abc", JsonPathUtil.apply(root, "$.data.token").asText());
    }

    @Test
    void arrayIndexAndWildcard() throws Exception {
        JsonNode root = parse("{\"items\":[{\"name\":\"A\"},{\"name\":\"B\"}]}");
        assertEquals("A", JsonPathUtil.apply(root, "$.items[0].name").asText());
        assertEquals(2, JsonPathUtil.apply(root, "$.items[*].name").size());
    }

    @Test
    void dollarOrEmptyReturnsRoot() throws Exception {
        JsonNode root = parse("{\"a\":1}");
        assertTrue(JsonPathUtil.apply(root, "$").has("a"));
        assertTrue(JsonPathUtil.apply(root, "").has("a"));
    }

    @Test
    void missingReturnsNull() throws Exception {
        JsonNode root = parse("{\"a\":1}");
        assertNull(JsonPathUtil.apply(root, "$.b.c"));
    }
}
