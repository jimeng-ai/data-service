package com.jimeng.common.core.tenant;

import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JimengTenantLineHandlerTest {

    private JimengTenantLineHandler handler;

    @BeforeEach
    void setUp() {
        handler = new JimengTenantLineHandler();
        ReflectionTestUtils.setField(handler, "extraTenantTables", "");
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void tenantIdColumn() {
        assertEquals("tenant_id", handler.getTenantIdColumn());
    }

    @Test
    void ignoreTable_tenantAwareTablesReturnFalse() {
        assertFalse(handler.ignoreTable("plugin"));
        assertFalse(handler.ignoreTable("plugin_tool"));
        assertFalse(handler.ignoreTable("plugin_http_mapping"));
        assertFalse(handler.ignoreTable("plugin_credential"));
        assertFalse(handler.ignoreTable("agent"));
        assertFalse(handler.ignoreTable("agent_plugin"));
        // 大小写不敏感
        assertFalse(handler.ignoreTable("Plugin"));
    }

    @Test
    void ignoreTable_otherTablesReturnTrue() {
        assertTrue(handler.ignoreTable("poi_category_dict"));
        assertTrue(handler.ignoreTable("ai_model_call_log"));
        assertTrue(handler.ignoreTable("knowledge_base"));
        assertTrue(handler.ignoreTable("kb_chunk"));
        assertTrue(handler.ignoreTable("random_unknown_table"));
    }

    @Test
    void ignoreTable_systemModeAlwaysTrue() {
        TenantContext.runAsSystem(() -> {
            assertTrue(handler.ignoreTable("plugin"));
            assertTrue(handler.ignoreTable("agent"));
        });
    }

    @Test
    void getTenantId_withContext() {
        TenantContext.set("tenant-a");
        Object expr = handler.getTenantId();
        assertInstanceOf(StringValue.class, expr);
        assertEquals("tenant-a", ((StringValue) expr).getValue());
    }

    @Test
    void getTenantId_withoutContext_returnsGuard() {
        Object expr = handler.getTenantId();
        assertInstanceOf(StringValue.class, expr);
        // 防御性兜底
        assertEquals("__no_tenant__", ((StringValue) expr).getValue());
    }

    @Test
    void extraTenantTables_picksUp() {
        ReflectionTestUtils.setField(handler, "extraTenantTables", "extra_table_a, extra_table_b");
        assertFalse(handler.ignoreTable("extra_table_a"));
        assertFalse(handler.ignoreTable("extra_table_b"));
        assertTrue(handler.ignoreTable("not_in_list"));
    }
}
