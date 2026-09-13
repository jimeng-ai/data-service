package com.jimeng.dataserver.ai.connector.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.service.ConnectorAuditQuery;
import com.jimeng.dataserver.ai.connector.service.ConnectorAuditView;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.ConnectorAudit;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.ConnectorAuditMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 审计写入与查询。 */
class ConnectorAuditServiceTest {

    private ConnectorAuditMapper auditMapper;
    private AgentMapper agentMapper;
    private ConnectorAuditService service;

    @BeforeEach
    void setUp() {
        auditMapper = mock(ConnectorAuditMapper.class);
        agentMapper = mock(AgentMapper.class);
        service = new ConnectorAuditService(auditMapper, agentMapper);
    }

    private ConnectorInstance inst() {
        return new ConnectorInstance(100L, "t1", "MYSQL", "crm", "CRM", Map.of(), "pwd", "direct");
    }

    // ================================================================ 写入

    /**
     * ★ 审计写不进去，不能让客户的查询跟着失败。
     *
     * <p>这是一个刻意的取舍：宁可丢一条审计，也不要因为审计表出问题而让主流程崩掉。
     * 但也不能静默——失败要带着足以重建这条记录的字段进日志。
     */
    @Test
    @DisplayName("审计写入失败不影响主流程")
    void 写入失败不抛异常() {
        when(auditMapper.insert(any(ConnectorAudit.class))).thenThrow(new RuntimeException("表锁了"));
        // 不抛就是通过
        service.record(inst(), 7L, Capability.QUERY, "conn_query", "SELECT 1", 1, 5,
                true, null, null);
    }

    @Test
    @DisplayName("超长语句截断并显式标注")
    void 超长语句被标注截断() {
        String longSql = "SELECT " + "x".repeat(9000);
        service.record(inst(), 7L, Capability.QUERY, "conn_query", longSql, 1, 5, true, null, null);

        ArgumentCaptor<ConnectorAudit> cap = ArgumentCaptor.forClass(ConnectorAudit.class);
        verify(auditMapper).insert(cap.capture());
        String stored = cap.getValue().getStatementText();
        assertTrue(stored.length() < longSql.length());
        // 不标注的话，后人查审计会以为客户真的写了一条正好 4000 字符的 SQL。
        assertTrue(stored.endsWith("[已截断]"));
    }

    @Test
    @DisplayName("失败记录带上错误码与脱敏原因")
    void 失败记录带错误码() {
        service.record(inst(), 7L, Capability.QUERY, "conn_query", null, null, 12,
                false, ConnectorErrorCode.NOT_FOUND, "指定的表或库不存在");

        ArgumentCaptor<ConnectorAudit> cap = ArgumentCaptor.forClass(ConnectorAudit.class);
        verify(auditMapper).insert(cap.capture());
        assertEquals("NOT_FOUND", cap.getValue().getErrorCode());
        assertEquals(Boolean.FALSE, cap.getValue().getSuccess());
    }

    // ================================================================ 查询

    @SuppressWarnings("unchecked")
    private void givenPage(List<ConnectorAudit> rows) {
        Page<ConnectorAudit> p = new Page<>(1, 20, rows.size());
        p.setRecords(rows);
        when(auditMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(p);
    }

    private ConnectorAudit row(Long agentId) {
        ConnectorAudit r = new ConnectorAudit();
        r.setId(1L);
        r.setTenantId("t1");
        r.setConnectorId(100L);
        r.setConnectorName("crm");
        r.setAgentId(agentId);
        r.setCapability("QUERY");
        r.setOperation("conn_query");
        r.setSuccess(true);
        r.setCreateTime(new Date());
        return r;
    }

    @Test
    @DisplayName("分页大小被夹取，防止一次拉全表")
    @SuppressWarnings("unchecked")
    void 分页大小被夹取() {
        givenPage(List.of());
        ConnectorAuditQuery q = new ConnectorAuditQuery();
        q.setSize(99999);
        service.query(q);

        ArgumentCaptor<Page<ConnectorAudit>> cap = ArgumentCaptor.forClass(Page.class);
        verify(auditMapper).selectPage(cap.capture(), any(LambdaQueryWrapper.class));
        assertEquals(200L, cap.getValue().getSize());
    }

    @Test
    @DisplayName("Agent 名批量解析，不逐行查")
    void Agent名批量解析() {
        Agent a = new Agent();
        a.setId(7L);
        a.setName("全能助手");
        givenPage(List.of(row(7L), row(7L), row(7L)));
        when(agentMapper.selectBatchIds(any())).thenReturn(List.of(a));

        Page<ConnectorAuditView> out = service.query(new ConnectorAuditQuery());
        assertEquals(3, out.getRecords().size());
        assertEquals("全能助手", out.getRecords().get(0).getAgentName());
        // 一页 200 行就是 200 次查询——批量这一步不能退化。
        verify(agentMapper).selectBatchIds(any());
    }

    /** 审计记的是历史事实，不该随 Agent 消失。名字取不到就留空，界面按「已删除」展示。 */
    @Test
    @DisplayName("Agent 已删除时 agentName 为空，但记录仍在")
    void Agent删除后记录仍在() {
        givenPage(List.of(row(999L)));
        when(agentMapper.selectBatchIds(any())).thenReturn(List.of());

        ConnectorAuditView v = service.query(new ConnectorAuditQuery()).getRecords().get(0);
        assertNotNull(v.getId());
        assertEquals("999", v.getAgentId());
        assertNull(v.getAgentName());
    }

    @Test
    @DisplayName("没有 agentId 时不去查 Agent 表")
    void 无Agent时不查表() {
        givenPage(List.of(row(null)));
        service.query(new ConnectorAuditQuery());
        verify(agentMapper, never()).selectBatchIds(any());
    }
}
