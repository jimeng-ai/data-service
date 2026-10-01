package com.jimeng.dataserver.ai.connector.graph;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.JimengTenantLineHandler;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemSummary;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorBusinessView;
import com.jimeng.persistence.entity.ConnectorEnrichmentState;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorBusinessViewMapper;
import com.jimeng.persistence.mapper.ConnectorEnrichmentStateMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.connection;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.join;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.objectView;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.pk;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataGraphServiceTest {

    private ConnectionMapper connectionMapper;
    private ConnectorSchemaMapper schemaMapper;
    private ConnectorSemanticMapper semanticMapper;
    private ConnectorBusinessViewMapper viewMapper;
    private ConnectorEnrichmentStateMapper stateMapper;
    private DataGraphService service;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 的列名解析要用 MP 的实体缓存；纯单测里没有 Spring，得自己灌一遍。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Connection.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSchema.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemantic.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorBusinessView.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorEnrichmentState.class);
        connectionMapper = mock(ConnectionMapper.class);
        schemaMapper = mock(ConnectorSchemaMapper.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        viewMapper = mock(ConnectorBusinessViewMapper.class);
        stateMapper = mock(ConnectorEnrichmentStateMapper.class);
        service = new DataGraphService(connectionMapper, schemaMapper, semanticMapper, viewMapper, stateMapper);
    }

    @Test
    @DisplayName("系统列表：只列出至少有一个 TABLE / VIEW 的连接，计数不含其他对象类型")
    void 系统列表() {
        Connection http = new Connection();
        http.setId(8L);
        http.setName("http");
        Connection seqOnly = new Connection();
        seqOnly.setId(9L);
        seqOnly.setName("seq-only");
        when(connectionMapper.selectList(any())).thenReturn(List.of(connection(), http, seqOnly));
        when(schemaMapper.selectList(any())).thenReturn(List.of(
                schemaRow(7L, "BASE TABLE"), schemaRow(7L, "VIEW"), schemaRow(9L, "SEQUENCE")));

        List<SystemSummary> systems = service.systems();

        assertEquals(1, systems.size());
        assertEquals("7", systems.get(0).getConnectorId());
        assertEquals("ERP 系统", systems.get(0).getDisplayName());
        assertEquals("READY", systems.get(0).getSemanticStatus());
        assertEquals(2, systems.get(0).getTableCount());
        assertFalse(systems.get(0).isTruncated());
        assertNull(systems.get(0).getViewStatus(), "补全链从没跑过");
    }

    @Test
    @DisplayName("系统列表：快照达到 200 个对象（含非表对象）为 truncated；带上业务文字的整理状态")
    void 截断与整理状态() {
        when(connectionMapper.selectList(any())).thenReturn(List.of(connection()));
        List<ConnectorSchema> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 199; i++) {
            rows.add(schemaRow(7L, "BASE TABLE"));
        }
        rows.add(schemaRow(7L, "SEQUENCE"));
        when(schemaMapper.selectList(any())).thenReturn(rows);
        ConnectorEnrichmentState state = new ConnectorEnrichmentState();
        state.setConnectorId(7L);
        state.setLastStatus("READY");
        when(stateMapper.selectList(any())).thenReturn(List.of(state));

        SystemSummary s = service.systems().get(0);

        assertEquals(199, s.getTableCount());
        assertTrue(s.isTruncated());
        assertEquals("READY", s.getViewStatus());
    }

    @Test
    @DisplayName("整理状态：认领未过期为 RUNNING；过期了看上次结果；没有状态行为 null")
    void 整理状态() {
        ConnectorEnrichmentState running = new ConnectorEnrichmentState();
        running.setClaimAt(new Date(System.currentTimeMillis() - 60_000));
        running.setLastStatus("READY");
        assertEquals("RUNNING", DataGraphService.viewStatus(running));

        ConnectorEnrichmentState stale = new ConnectorEnrichmentState();
        stale.setClaimAt(new Date(System.currentTimeMillis() - 31 * 60_000));
        stale.setLastStatus("FAILED");
        assertEquals("FAILED", DataGraphService.viewStatus(stale));

        assertNull(DataGraphService.viewStatus(null));
    }

    @Test
    @DisplayName("系统图：业务视图交给投影，标题取业务名")
    void 系统图带业务名() {
        when(connectionMapper.selectById(7L)).thenReturn(connection());
        when(schemaMapper.selectList(any())).thenReturn(List.of(table("t_order", "订单表", 1, List.of(pk("id")), "id")));
        when(semanticMapper.selectList(any())).thenReturn(List.of());
        when(viewMapper.selectList(any())).thenReturn(List.of(objectView("t_order", "销售订单", "说明", "销售")));

        SystemGraph g = service.system("7");

        assertEquals("销售订单", g.getTables().get(0).getDisplayName());
        assertEquals("BUSINESS_VIEW", g.getTables().get(0).getNameSource());
        assertEquals("销售订单", service.table("7", "t_order").getDisplayName());
    }

    @Test
    @DisplayName("连接 id 不合法或查不到（含别的租户的连接）：系统不存在，且不再查快照")
    void 系统不存在() {
        ServiceException bad = assertThrows(ServiceException.class, () -> service.system("abc"));
        assertEquals(ExceptionCode.NOT_FOUND.getResultCode(), bad.getRespCode());
        assertEquals("系统不存在", bad.getRespMsg());
        when(connectionMapper.selectById(99L)).thenReturn(null);
        assertEquals("系统不存在", assertThrows(ServiceException.class, () -> service.system("99")).getRespMsg());
        verifyNoInteractions(schemaMapper, semanticMapper, viewMapper, stateMapper);
    }

    @Test
    @DisplayName("单表详情：表名为空是非法请求；表不在快照里是表不存在；表名两端空白会去掉")
    void 单表() {
        assertEquals("表名不能为空", assertThrows(ServiceException.class, () -> service.table("7", " ")).getRespMsg());
        when(connectionMapper.selectById(7L)).thenReturn(connection());
        when(schemaMapper.selectList(any())).thenReturn(List.of(table("t_order", "订单表", 1, List.of(pk("id")), "id")));
        when(semanticMapper.selectList(any())).thenReturn(List.of());

        ServiceException missing = assertThrows(ServiceException.class, () -> service.table("7", "t_nope"));
        assertEquals(ExceptionCode.NOT_FOUND.getResultCode(), missing.getRespCode());
        assertEquals("表不存在", missing.getRespMsg());
        assertEquals("t_order", service.table("7", " t_order ").getName());
    }

    @Test
    @DisplayName("表名只去两端空白，中间的空格原样保留")
    void 表名中间空格() {
        when(connectionMapper.selectById(7L)).thenReturn(connection());
        when(schemaMapper.selectList(any())).thenReturn(List.of(table("采购 单", "采购单", 1, List.of(pk("编号")), "编号")));
        when(semanticMapper.selectList(any())).thenReturn(List.of());
        assertEquals("采购 单", service.table("7", " 采购 单 ").getName());
    }

    @Test
    @DisplayName("★ 星图的租户隔离完全依赖这五张源表在租户白名单里：少一张，别的租户的表结构或业务名称就会漏出来")
    void 源表都按租户过滤() {
        JimengTenantLineHandler handler = new JimengTenantLineHandler();
        ReflectionTestUtils.setField(handler, "extraTenantTables", "");
        for (String table : List.of("connection", "connector_schema", "connector_semantic",
                "connector_business_view", "connector_enrichment_state")) {
            assertFalse(handler.ignoreTable(table), table);
        }
    }

    @Test
    @DisplayName("系统图：把这条连接的快照与 JOIN 行交给投影")
    void 系统图() {
        when(connectionMapper.selectById(7L)).thenReturn(connection());
        when(schemaMapper.selectList(any())).thenReturn(List.of(
                table("t_order", "订单表", 1, List.of(pk("id")), "id"),
                table("t_item", "订单明细", 2, List.of(pk("id")), "id", "order_id")));
        when(semanticMapper.selectList(any())).thenReturn(List.of(join("t_item", "order_id", "t_order", "id")));

        SystemGraph g = service.system("7");

        assertEquals("7", g.getConnectorId());
        assertEquals(2, g.getTables().size());
        assertEquals(1, g.getRelations().size());
    }

    private static ConnectorSchema schemaRow(long connectorId, String objectType) {
        ConnectorSchema row = new ConnectorSchema();
        row.setConnectorId(connectorId);
        row.setObjectType(objectType);
        return row;
    }
}
