package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.model.CatalogEntry;
import com.jimeng.dataserver.ai.connector.model.CatalogView;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorGateway;
import com.jimeng.dataserver.ai.connector.service.ConnectorSchemaService.ObjectDiff;
import com.jimeng.dataserver.ai.connector.service.ConnectorSchemaService.SnapshotResult;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.Presence;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SnapshotPresence;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 刷新结构时「这次没看到」与「没了」分不分得开，以及空目录闸、网关失败的 cause。
 *
 * <p>这几件事坏掉时都<b>不报错</b>：一张被挤出描述上限的表被报成 REMOVED，结果是挂在它上面的口径停止注入、
 * 管理台叫业务方重答；一次拿到空目录的刷新被照常落库，结果是整条连接的说明书一夜之间全部过期。
 */
class ConnectorSchemaPresenceGuardTest {

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
        when(semanticService.applyDrift(any(), any(), any(), any()))
                .thenReturn(new ConnectorSemanticService.StaleResult(0, 0));
        TenantContext.set("t1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static ObjectDetail detail(String name) {
        return new ObjectDetail(name, "BASE TABLE", null,
                List.of(new FieldDetail("id", "bigint", false, null, null)), Map.of());
    }

    /** 上一份快照里的行，指纹与这次描述出来的一致——免得 CHANGED 混进断言里。 */
    private static ConnectorSchema previousRow(String name) {
        ConnectorSchema s = new ConnectorSchema();
        s.setObjectName(name);
        s.setContentHash(ConnectorSemanticService.sha256(ConnectorSchemaService.structureFingerprint(detail(name))));
        s.setSyncedAt(new Date(1_700_000_000_000L));
        try {
            s.setDetailJson(CommonUtil.getObjectMapper().writeValueAsString(ConnectorSchemaService.toDetailMap(detail(name))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    private void previousSnapshot(String... names) {
        List<ConnectorSchema> rows = new ArrayList<>();
        for (String n : names) {
            rows.add(previousRow(n));
        }
        when(schemaMapper.selectList(any())).thenReturn(rows);
    }

    @SuppressWarnings("unchecked")
    private void customerLists(List<String> tables, boolean truncated, int total) {
        ConnectorSession session = mock(ConnectorSession.class, withSettings().extraInterfaces(DescribeCapable.class));
        DescribeCapable describe = (DescribeCapable) session;
        List<CatalogEntry> entries = new ArrayList<>();
        for (String t : tables) {
            entries.add(new CatalogEntry(t, "BASE TABLE", null));
        }
        when(describe.catalog()).thenReturn(new CatalogView("TABLE", entries, truncated, total, "按估算行数的数量级降序"));
        when(describe.describe(anyString())).thenAnswer(inv -> detail(inv.getArgument(0)));
        // 与真实连接器的默认实现一致：答不了存不存在（null）。不 stub 的话 Mockito 回一个空集合，等于「都确认不在」。
        when(describe.existingObjects(any())).thenCallRealMethod();
        when(gateway.executeAsPlatform(eq(100L), eq(Capability.DESCRIBE), anyString(), any()))
                .thenAnswer(inv -> ((ConnectorGateway.Op<Object>) inv.getArgument(3)).apply(session));
    }

    private static List<String> changed(SnapshotResult r, String kind) {
        return r.getDiffs().stream().filter(d -> kind.equals(d.getChange())).map(ObjectDiff::getObjectName).toList();
    }

    private SnapshotPresence presencePassedToDrift(List<String> expectedRemoved) {
        ArgumentCaptor<SnapshotPresence> p = ArgumentCaptor.forClass(SnapshotPresence.class);
        verify(semanticService).applyDrift(eq(100L), any(), p.capture(), eq(expectedRemoved));
        assertNotNull(p.getValue(), "处境必须传给漂移处置，否则它只能按「描述到的才算在」判");
        return p.getValue();
    }

    // ================================================================ 四种处境

    @Nested
    @DisplayName("「这次没看到」不等于「没了」")
    class Presences {

        /**
         * ★ 需求里点名的那一种：行数估算跨过一个数量级，表从第 200 名掉到第 201 名。
         * 它还在目录里，只是这次没被描述——不是 REMOVED，漂移处置拿到的处境是 LISTED_ONLY。
         */
        @Test
        @DisplayName("★ 被挤出描述上限的表：不报 REMOVED，处境是 LISTED_ONLY")
        void pushedBeyondDescribeCapIsListedOnly() {
            List<String> tables = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                tables.add(String.format("big_%03d", i));
            }
            tables.add("t_small");
            previousSnapshot("big_000", "t_small");
            customerLists(tables, false, 201);

            SnapshotResult r = service.refresh(100L);

            assertEquals(200, r.getObjectCount());
            assertTrue(r.isTruncated());
            assertTrue(changed(r, "REMOVED").isEmpty(), "还在目录里的表不能报成消失：" + r.getDiffs());
            SnapshotPresence p = presencePassedToDrift(List.of());
            assertEquals(Presence.LISTED_ONLY, p.stateOf("t_small"));
            assertEquals(Presence.LISTED_ONLY, p.stateOf("T_SMALL"), "处境按唯一键的排序规则比，大小写不敏感");
            assertEquals(Presence.DESCRIBED, p.stateOf("big_199"));
        }

        /** 目录自己也被截断了（超过 500 张）：没列出来的名字只能说「不知道」。 */
        @Test
        @DisplayName("★ 目录本身截断、没列出的表：不报 REMOVED，处境是 UNKNOWN")
        void unlistedOnTruncatedCatalogIsUnknown() {
            previousSnapshot("orders", "t_tiny");
            customerLists(List.of("orders"), true, 900);

            SnapshotResult r = service.refresh(100L);

            assertTrue(changed(r, "REMOVED").isEmpty(), r.getDiffs().toString());
            assertEquals(Presence.UNKNOWN, presencePassedToDrift(List.of()).stateOf("t_tiny"));
        }

        /**
         * 连接器漏报了截断（{@code truncated=false}），但列出来的比它自己报的总数少。
         * 这时把「没列出来」当成「没了」是往危险方向兜底——宁可晚一次刷新才标过期。
         */
        @Test
        @DisplayName("列出来的比总数少，哪怕 truncated=false 也按目录截断处理")
        void underReportedTruncationIsStillUnknown() {
            previousSnapshot("orders", "t_tiny");
            customerLists(List.of("orders"), false, 3);

            SnapshotResult r = service.refresh(100L);

            assertTrue(changed(r, "REMOVED").isEmpty(), r.getDiffs().toString());
            assertEquals(Presence.UNKNOWN, presencePassedToDrift(List.of()).stateOf("t_tiny"));
        }

        @Test
        @DisplayName("目录完整、没列出的表才是 REMOVED，处境 ABSENT")
        void unlistedOnCompleteCatalogIsRemoved() {
            previousSnapshot("orders", "t_refund");
            customerLists(List.of("orders"), false, 1);

            SnapshotResult r = service.refresh(100L);

            assertEquals(List.of("t_refund"), changed(r, "REMOVED"));
            assertEquals(Presence.ABSENT, presencePassedToDrift(List.of("t_refund")).stateOf("t_refund"));
        }

        /** 只改了大小写的表不是「没了」：唯一键不分大小写，语义层上它就是同一张表。 */
        @Test
        @DisplayName("只改了大小写的表不报 REMOVED")
        void caseOnlyRenameIsNotRemoved() {
            previousSnapshot("T_ORD");
            customerLists(List.of("t_ord"), false, 1);

            SnapshotResult r = service.refresh(100L);

            assertTrue(changed(r, "REMOVED").isEmpty(), r.getDiffs().toString());
            assertEquals(Presence.DESCRIBED, presencePassedToDrift(List.of()).stateOf("T_ORD"));
        }
    }

    // ================================================================ 空目录闸

    @Nested
    @DisplayName("空目录闸")
    class EmptyCatalogGuard {

        /**
         * ★ 客户库这次列出 0 个对象、上一份快照有 2 个：远比「真删光了」更常见于恢复、迁移、授权被收回。
         * 照常落库的代价是整条连接的说明书一次全部过期、口径全部停止注入——等授权恢复又全部复活。
         */
        @Test
        @DisplayName("★ 上一份快照非空时拿到空目录：不落库、不做漂移处置、不派发，返回上一份的数与原因")
        void emptyCatalogIsRefusedWhenPreviousSnapshotExists() {
            previousSnapshot("orders", "items");
            customerLists(List.of(), false, 0);

            SnapshotResult r = service.refresh(100L);

            assertNotNull(r.getGuardNote(), "拒绝落库必须说出来，否则「0 个变化」会被读成「客户库没变」");
            assertTrue(r.getGuardNote().contains("0") && r.getGuardNote().contains("2"), r.getGuardNote());
            assertEquals(2, r.getObjectCount(), "返回上一份快照的数");
            assertFalse(r.isFirstSnapshot());
            assertTrue(r.getDiffs().isEmpty());
            assertEquals(new Date(1_700_000_000_000L), r.getSyncedAt(), "这次什么都没同步，不能显示成刚刚刷新过");
            assertNull(r.getSemanticStaled(), "漂移处置确实没跑：null，不是 0");
            assertNull(r.getSemanticStaledByObject());

            verify(schemaMapper, never()).physicalDeleteByConnector(any());
            verify(schemaMapper, never()).insert(any(ConnectorSchema.class));
            verify(semanticService, never()).applyDrift(any(), any(), any(), any());
            verify(semanticService, never()).applyDrift(any(), any(), any());
            verify(derive, never()).deriveAddedAsync(any(), any());
        }

        /** 第一次快照就是空库：没有「上一份」可保护，照常落（空的）快照。 */
        @Test
        @DisplayName("第一次快照就是空目录：不拦")
        void emptyCatalogOnFirstSnapshotIsNotGuarded() {
            previousSnapshot();
            customerLists(List.of(), false, 0);

            SnapshotResult r = service.refresh(100L);

            assertNull(r.getGuardNote());
            assertTrue(r.isFirstSnapshot());
            verify(schemaMapper).physicalDeleteByConnector(100L);
        }

        @Test
        @DisplayName("被拦过之后，下一次非空的刷新照常落库、漂移处置、派发")
        void laterNonEmptyRefreshProceeds() {
            previousSnapshot("orders");
            customerLists(List.of(), false, 0);
            assertNotNull(service.refresh(100L).getGuardNote());

            customerLists(List.of("orders", "refunds"), false, 2);
            SnapshotResult r = service.refresh(100L);

            assertNull(r.getGuardNote());
            assertEquals(List.of("refunds"), changed(r, "ADDED"));
            verify(schemaMapper, times(1)).physicalDeleteByConnector(100L);
            verify(semanticService, times(1)).applyDrift(eq(100L), any(), any(), eq(List.of()));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<String>> names = ArgumentCaptor.forClass(Collection.class);
            verify(derive).deriveAddedAsync(eq(100L), names.capture());
            assertEquals(List.of("refunds"), new ArrayList<>(names.getValue()));
        }
    }

    // ================================================================ 网关失败

    /**
     * 定时刷新要从 cause 链上认出 RATE_LIMITED（「现在不方便」≠「坏了」）。转换时丢了 cause，
     * 它就只能比对文案——谁改一个字，繁忙就被当成故障、进冷却。
     */
    @Test
    @DisplayName("★ 网关拒绝转成业务异常时保留 cause，错误码可以从 cause 链上认出来")
    void gatewayFailureKeepsConnectorExceptionAsCause() {
        when(gateway.executeAsPlatform(eq(100L), eq(Capability.DESCRIBE), anyString(), any()))
                .thenThrow(ConnectorException.of(ConnectorErrorCode.RATE_LIMITED,
                        "连接「crm」当前并发查询已达上限，请稍后重试"));

        ServiceException e = assertThrows(ServiceException.class, () -> service.refresh(100L));

        assertEquals(ExceptionCode.INVALID_REQUEST.getResultCode(), e.getRespCode());
        assertTrue(e.getRespMsg().contains("并发查询已达上限"), "给管理台的文案不变");
        assertTrue(e.getCause() instanceof ConnectorException ce && ce.getCode() == ConnectorErrorCode.RATE_LIMITED,
                "cause 必须是原来的 ConnectorException，实际: " + e.getCause());
    }
}
