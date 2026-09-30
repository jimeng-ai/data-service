package com.jimeng.dataserver.ai.connector.graph;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.JimengTenantLineHandler;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemSummary;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.connection;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.join;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.pk;
import static com.jimeng.dataserver.ai.connector.graph.DataGraphFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataGraphServiceTest {

    private ConnectionMapper connectionMapper;
    private ConnectorSchemaMapper schemaMapper;
    private ConnectorSemanticMapper semanticMapper;
    private DataGraphService service;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 的列名解析要用 MP 的实体缓存；纯单测里没有 Spring，得自己灌一遍。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Connection.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSchema.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemantic.class);
        connectionMapper = mock(ConnectionMapper.class);
        schemaMapper = mock(ConnectorSchemaMapper.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        service = new DataGraphService(connectionMapper, schemaMapper, semanticMapper);
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
    }

    @Test
    @DisplayName("连接 id 不合法或查不到（含别的租户的连接）：系统不存在，且不再查快照")
    void 系统不存在() {
        ServiceException bad = assertThrows(ServiceException.class, () -> service.system("abc"));
        assertEquals(ExceptionCode.NOT_FOUND.getResultCode(), bad.getRespCode());
        assertEquals("系统不存在", bad.getRespMsg());
        when(connectionMapper.selectById(99L)).thenReturn(null);
        assertEquals("系统不存在", assertThrows(ServiceException.class, () -> service.system("99")).getRespMsg());
        verifyNoInteractions(schemaMapper, semanticMapper);
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
    @DisplayName("★ 星图的租户隔离完全依赖这三张源表在租户白名单里：少一张，别的租户的表结构就会漏出来")
    void 三张源表都按租户过滤() {
        JimengTenantLineHandler handler = new JimengTenantLineHandler();
        ReflectionTestUtils.setField(handler, "extraTenantTables", "");
        for (String table : List.of("connection", "connector_schema", "connector_semantic")) {
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
