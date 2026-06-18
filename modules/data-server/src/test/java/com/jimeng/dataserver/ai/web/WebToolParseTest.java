package com.jimeng.dataserver.ai.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WebSearchService.parseResults（AnySearch 响应解析）+ WebFetchService.htmlToText 纯逻辑单测。 */
class WebToolParseTest {

    @Test
    void parsesAnySearchResponse() {
        String body = "{\"code\":0,\"message\":\"success\",\"data\":{\"results\":["
                + "{\"title\":\"A\",\"url\":\"https://a.com\",\"snippet\":\"sa\",\"content\":\"ca\"},"
                + "{\"title\":\"B\",\"url\":\"https://b.com\",\"content\":\"cb\"}]}}";
        List<Map<String, Object>> hits = WebSearchService.parseResults(body);
        assertEquals(2, hits.size());
        assertEquals("A", hits.get(0).get("title"));
        assertEquals("https://a.com", hits.get(0).get("url"));
        assertEquals("sa", hits.get(0).get("snippet"));
        assertEquals("cb", hits.get(1).get("snippet")); // snippet 缺失回退 content
    }

    @Test
    void parseResultsEmptyOnMissing() {
        assertTrue(WebSearchService.parseResults(null).isEmpty());
        assertTrue(WebSearchService.parseResults("{}").isEmpty());
        assertTrue(WebSearchService.parseResults("{\"data\":{}}").isEmpty());
    }

    @Test
    void htmlToTextStripsTags() {
        String t = WebFetchService.htmlToText(
                "<html><head><title>x</title></head><body><script>bad()</script><p>Hello&nbsp;World</p></body></html>");
        assertEquals("Hello World", t);
    }
}
