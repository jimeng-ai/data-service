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

    /**
     * 注意这个测试<b>不校验白名单的完备性</b>：它只逐条断言"已经想到的表在里面"，
     * 新加一张带 tenant_id 的表却忘了登记时，它照样绿。
     * 这是一个已知的门禁缺口——真正的防线仍然是"加表时手工补白名单 + 补这里的断言"。
     * 要堵住它得反过来做（扫实体上的 tenant_id 字段，反查白名单），目前没做。
     */
    @Test
    void ignoreTable_tenantAwareTablesReturnFalse() {
        assertFalse(handler.ignoreTable("agent"));
        assertFalse(handler.ignoreTable("agent_skill"));
        assertFalse(handler.ignoreTable("connection"));
        assertFalse(handler.ignoreTable("agent_connection"));
        // 连接器框架的两张新表：自描述缓存里有客户的表名/字段名，审计里有客户的语句原文，
        // 漏登记就是跨租户静默泄露。
        assertFalse(handler.ignoreTable("connector_schema"));
        assertFalse(handler.ignoreTable("connector_audit"));
        // 待审批写操作队列：漏登记不报错，后果是 A 租户的超管能在审批列表里看到、
        // 并【批准】B 租户 Agent 提交的写语句——一次跨租户的数据篡改。
        assertFalse(handler.ignoreTable("connector_pending_write"));
        assertFalse(handler.ignoreTable("knowledge_base"));
        assertFalse(handler.ignoreTable("ai_trace"));
        assertFalse(handler.ignoreTable("ai_trace_step"));
        // 大小写不敏感
        assertFalse(handler.ignoreTable("Agent"));
    }

    @Test
    void ignoreTable_otherTablesReturnTrue() {
        assertTrue(handler.ignoreTable("poi_category_dict"));
        // ai_model_call_log 刻意不在白名单：聚合查询显式带 tenant_id，避免拦截器改写自定义 SQL。
        assertTrue(handler.ignoreTable("ai_model_call_log"));
        assertTrue(handler.ignoreTable("kb_chunk"));
        assertTrue(handler.ignoreTable("random_unknown_table"));
    }

    @Test
    void ignoreTable_systemModeAlwaysTrue() {
        TenantContext.runAsSystem(() -> {
            assertTrue(handler.ignoreTable("agent_skill"));
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
