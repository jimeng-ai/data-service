package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.model.CatalogEntry;
import com.jimeng.dataserver.ai.connector.model.CatalogView;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorGateway;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.cap.DescribeCapable;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 刷新结构之后的两件事：交给增量推导（§8 ADDED / 契约 C-A），以及消失的表按表报影响（§8 REMOVED）。
 */
class ConnectorSchemaAddedDispatchTest {

    private ConnectorSchemaMapper schemaMapper;
    private ConnectorSemanticService semanticService;
    private ConnectorSemanticDeriveService derive;
    private ConnectorGateway gateway;
    private ConnectorSchemaService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ConnectionMapper connectionMapper = mock(ConnectionMapper.class);
        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        schemaMapper = mock(ConnectorSchemaMapper.class);
        semanticService = mock(ConnectorSemanticService.class);
        derive = mock(ConnectorSemanticDeriveService.class);
        gateway = mock(ConnectorGateway.class);
        ObjectProvider<ConnectorSemanticDeriveService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(derive);
        service = new ConnectorSchemaService(connectionMapper, schemaMapper, registry, semanticService,
                mock(PlatformTransactionManager.class), gateway, provider, mock(org.redisson.api.RedissonClient.class));
        service.initTx();

        Connection row = new Connection();
        row.setId(100L);
        row.setTenantId("t1");
        row.setName("crm");
        row.setKind("MYSQL");
        row.setStatus("ACTIVE");
        row.setTransport("direct");
        when(connectionMapper.selectById(100L)).thenReturn(row);
        Connector connector = mock(Connector.class);
        when(connector.declaredCapabilities()).thenReturn(Set.of(Capability.QUERY, Capability.DESCRIBE));
        when(registry.require("MYSQL")).thenReturn(connector);
        TenantContext.set("t1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static ObjectDetail detail(String name) {
        return new ObjectDetail(name, "BASE TABLE", null, List.of(new FieldDetail("id", "bigint", false, null, null)), Map.of());
    }

    private static ConnectorSchema previousRow(String name) {
        ConnectorSchema s = new ConnectorSchema();
        s.setObjectName(name);
        s.setContentHash("stale-hash-does-not-matter");
        try {
            s.setDetailJson(CommonUtil.getObjectMapper().writeValueAsString(ConnectorSchemaService.toDetailMap(detail(name))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private void customerHas(String... tables) {
        ConnectorSession session = mock(ConnectorSession.class, withSettings().extraInterfaces(DescribeCapable.class));
        DescribeCapable describe = (DescribeCapable) session;
        List<CatalogEntry> entries = new ArrayList<>();
        for (String t : tables) {
            entries.add(new CatalogEntry(t, "BASE TABLE", null));
            when(describe.describe(t)).thenReturn(detail(t));
        }
        when(describe.catalog()).thenReturn(new CatalogView("TABLE", entries, false, tables.length, "按估算行数"));
        when(describe.existingObjects(any())).thenCallRealMethod();
        when(gateway.executeAsPlatform(eq(100L), eq(Capability.DESCRIBE), anyString(), any()))
                .thenAnswer(inv -> ((ConnectorGateway.Op<Object>) inv.getArgument(3)).apply(session));
    }

    @Test
    @DisplayName("★ 新出现的表一批入队一次（50 张新表是一次模型调用，不是 50 次）")
    void addedTablesAreDispatchedAsOneBatch() {
        when(schemaMapper.selectList(any())).thenReturn(List.of(previousRow("orders")));
        customerHas("orders", "refunds", "items");

        service.refresh(100L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> names = ArgumentCaptor.forClass(Collection.class);
        verify(derive).deriveAddedAsync(eq(100L), names.capture());
        assertEquals(List.of("refunds", "items"), new ArrayList<>(names.getValue()));
    }

    /**
     * 契约 C-A：没有新表也派发。推导那边用这一次补齐「快照里有、说明书里却没有」的对象——
     * 被挤出过描述上限又回来的表、上一次增量推导失败的表。它们在 diff 里都不是 ADDED，
     * 只在有 ADDED 时才派发的话，它们要等到下一次恰好有新表出现，可能永远等不到。
     */
    @Test
    @DisplayName("★ 没有新表也派发（空名单），让推导补齐没覆盖到的对象")
    void dispatchesEvenWithoutAddedTables() {
        when(schemaMapper.selectList(any())).thenReturn(List.of(previousRow("orders")));
        customerHas("orders");

        service.refresh(100L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> names = ArgumentCaptor.forClass(Collection.class);
        verify(derive).deriveAddedAsync(eq(100L), names.capture());
        assertTrue(names.getValue().isEmpty(), "没有新表时传空名单，而不是不派发");
    }

    /** 第一次快照里全是「新增」。当增量推，就是一次绕过认领 CAS 的全量推导。 */
    @Test
    @DisplayName("★ 第一次快照不派发增量推导")
    void firstSnapshotNeverDispatches() {
        when(schemaMapper.selectList(any())).thenReturn(List.of());
        customerHas("orders", "refunds");

        service.refresh(100L);

        verify(derive, never()).deriveAddedAsync(any(), any());
    }

    @Test
    @DisplayName("派发失败不让一次成功的刷新失败")
    void dispatchFailureDoesNotFailRefresh() {
        when(schemaMapper.selectList(any())).thenReturn(List.of(previousRow("orders")));
        customerHas("orders", "refunds");
        doThrow(new IllegalStateException("队列满")).when(derive).deriveAddedAsync(any(), any());

        ConnectorSchemaService.SnapshotResult r = service.refresh(100L);

        assertEquals(2, r.getObjectCount());
    }

    @Test
    @DisplayName("消失的表按表报影响；漂移处置失败时三个字段一起是 null")
    void removedImpactIsReportedPerObject() {
        when(schemaMapper.selectList(any())).thenReturn(List.of(previousRow("orders"), previousRow("t_refund")));
        customerHas("orders");
        when(semanticService.applyDrift(eq(100L), any(), any(), eq(List.of("t_refund")))).thenReturn(
                new ConnectorSemanticService.StaleResult(3, 0, List.of(
                        new ConnectorSemanticService.ObjectStale("t_refund", 2, 1, List.of("销售额")))));

        ConnectorSchemaService.SnapshotResult r = service.refresh(100L);

        assertEquals(1, r.getSemanticStaledByObject().size());
        ConnectorSchemaService.RemovedImpact i = r.getSemanticStaledByObject().get(0);
        assertEquals("t_refund", i.getObjectName());
        assertEquals(2, i.getStaledRows());
        assertEquals(1, i.getStaledMetrics());
        assertEquals(List.of("销售额"), i.getMetricTerms());

        when(semanticService.applyDrift(any(), any(), any(), any())).thenThrow(new RuntimeException("库抖了"));
        ConnectorSchemaService.SnapshotResult failed = service.refresh(100L);
        assertNull(failed.getSemanticStaled());
        assertNull(failed.getSemanticStaledByObject(), "null = 没算成，不等于没有影响");
    }
}
