package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.StaleResult;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.cap.DescribeCapable;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 结构刷新这一轮补上的几道闸：大库上删掉的表要能被判成消失（K-1）、旧拉取不许覆盖新快照、
 * 漂移处置失败过的那次 REMOVED 不能丢、描述失败的表不算「描述到了」、档位外的真实取值要顺手清掉。
 *
 * <p>每一条坏掉时都不报错：说明书一直注入一张不存在的表、删掉的表随一次慢拉取复活、口径失效永远补不上、
 * 一张没授权的表上的说明全部假过期、客户收回授权后取值仍躺在我们库里。
 */
class ConnectorSchemaDriftGuardsTest {

    private static final Long ID = 100L;
    private static final Date OLD = new Date(1_700_000_000_000L);

    private ConnectionMapper connectionMapper;
    private ConnectorSchemaMapper schemaMapper;
    private ConnectorSemanticService semanticService;
    private ConnectorSemanticDeriveService derive;
    private ConnectorGateway gateway;
    private RedissonClient redisson;
    private RSet<String> pending;
    private ConnectorSchemaService service;
    private Connection row;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ConnectorSchema.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Connection.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        connectionMapper = mock(ConnectionMapper.class);
        schemaMapper = mock(ConnectorSchemaMapper.class);
        semanticService = mock(ConnectorSemanticService.class);
        derive = mock(ConnectorSemanticDeriveService.class);
        gateway = mock(ConnectorGateway.class);
        redisson = mock(RedissonClient.class);
        pending = mock(RSet.class);
        when(redisson.<String>getSet(anyString())).thenReturn(pending);
        when(pending.readAll()).thenReturn(new LinkedHashSet<>());
        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        ObjectProvider<ConnectorSemanticDeriveService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(derive);
        service = new ConnectorSchemaService(connectionMapper, schemaMapper, registry, semanticService,
                mock(PlatformTransactionManager.class), gateway, provider, redisson);
        service.initTx();

        row = new Connection();
        row.setId(ID);
        row.setTenantId("t1");
        row.setName("crm");
        row.setKind("MYSQL");
        row.setStatus("ACTIVE");
        row.setTransport("direct");
        when(connectionMapper.selectById(ID)).thenReturn(row);
        Connector connector = mock(Connector.class);
        when(connector.declaredCapabilities()).thenReturn(Set.of(Capability.QUERY, Capability.DESCRIBE));
        when(registry.require("MYSQL")).thenReturn(connector);
        when(semanticService.applyDrift(any(), any(), any(), any())).thenReturn(new StaleResult(0, 0));
        TenantContext.set("t1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ================================================================ 夹具

    private static ObjectDetail detail(String name) {
        return new ObjectDetail(name, "BASE TABLE", null,
                List.of(new FieldDetail("id", "bigint", false, null, null)), Map.of());
    }

    private static ConnectorSchema previousRow(String name, Date syncedAt) {
        ConnectorSchema s = new ConnectorSchema();
        s.setObjectName(name);
        s.setContentHash(ConnectorSemanticService.sha256(ConnectorSchemaService.structureFingerprint(detail(name))));
        s.setSyncedAt(syncedAt);
        try {
            s.setDetailJson(CommonUtil.getObjectMapper().writeValueAsString(ConnectorSchemaService.toDetailMap(detail(name))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    private void previousSnapshot(Date syncedAt, String... names) {
        List<ConnectorSchema> rows = new ArrayList<>();
        for (String n : names) {
            rows.add(previousRow(n, syncedAt));
        }
        when(schemaMapper.selectList(any())).thenReturn(rows);
    }

    @SuppressWarnings("unchecked")
    private DescribeCapable customer(List<String> tables, boolean truncated, int total) {
        ConnectorSession session = mock(ConnectorSession.class, withSettings().extraInterfaces(DescribeCapable.class));
        DescribeCapable describe = (DescribeCapable) session;
        List<CatalogEntry> entries = new ArrayList<>();
        for (String t : tables) {
            entries.add(new CatalogEntry(t, "BASE TABLE", null));
        }
        when(describe.catalog()).thenReturn(new CatalogView("TABLE", entries, truncated, total, "按估算行数的数量级降序"));
        when(describe.describe(anyString())).thenAnswer(inv -> detail(inv.getArgument(0)));
        when(describe.existingObjects(any())).thenCallRealMethod();
        when(gateway.executeAsPlatform(eq(ID), eq(Capability.DESCRIBE), anyString(), any()))
                .thenAnswer(inv -> ((ConnectorGateway.Op<Object>) inv.getArgument(3)).apply(session));
        return describe;
    }

    private SnapshotPresence presenceToDrift() {
        ArgumentCaptor<SnapshotPresence> p = ArgumentCaptor.forClass(SnapshotPresence.class);
        verify(semanticService).applyDrift(eq(ID), any(), p.capture(), any());
        return p.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<String> removedToDrift() {
        ArgumentCaptor<List<String>> r = ArgumentCaptor.forClass(List.class);
        verify(semanticService).applyDrift(eq(ID), any(), any(), r.capture());
        return r.getValue();
    }

    private static List<String> changed(SnapshotResult r, String kind) {
        return r.getDiffs().stream().filter(d -> kind.equals(d.getChange())).map(ObjectDiff::getObjectName).toList();
    }

    // ================================================================ 条目 1：目录截断时确认存不存在

    @Nested
    @DisplayName("目录截断：上次见过、这次没列出的名字在同一个会话里确认存不存在")
    class ExistenceCheck {

        /** ★ 需求里点名的：对象数超过 500 的库上删掉一张表。从前它永远是 UNKNOWN，说明书一直注入它。 */
        @Test
        @DisplayName("★ 确认不在：报 REMOVED，处境 ABSENT，并按消失归因")
        void confirmedMissingIsRemoved() {
            previousSnapshot(OLD, "orders", "t_gone");
            DescribeCapable describe = customer(List.of("orders"), true, 900);
            doReturn(Set.of()).when(describe).existingObjects(any());

            SnapshotResult r = service.refresh(ID);

            assertEquals(List.of("t_gone"), changed(r, "REMOVED"));
            assertEquals(Presence.ABSENT, presenceToDrift().stateOf("t_gone"));
            assertEquals(List.of("t_gone"), removedToDrift());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<String>> asked = ArgumentCaptor.forClass(Collection.class);
            verify(describe).existingObjects(asked.capture());
            assertTrue(asked.getValue().contains("t_gone"));
            assertFalse(asked.getValue().contains("orders"), "目录列出了的不必再问");
        }

        @Test
        @DisplayName("确认还在（写法不同）：不报 REMOVED，处境 LISTED_ONLY")
        void confirmedExistingIsListedOnly() {
            previousSnapshot(OLD, "orders", "t_tiny");
            DescribeCapable describe = customer(List.of("orders"), true, 900);
            doReturn(Set.of("T_TINY")).when(describe).existingObjects(any());

            SnapshotResult r = service.refresh(ID);

            assertTrue(changed(r, "REMOVED").isEmpty(), r.getDiffs().toString());
            assertEquals(Presence.LISTED_ONLY, presenceToDrift().stateOf("t_tiny"));
        }

        @Test
        @DisplayName("连接器答不了（null）：仍是 UNKNOWN，不报 REMOVED")
        void connectorCannotTell() {
            previousSnapshot(OLD, "orders", "t_tiny");
            customer(List.of("orders"), true, 900);

            SnapshotResult r = service.refresh(ID);

            assertTrue(changed(r, "REMOVED").isEmpty());
            assertEquals(Presence.UNKNOWN, presenceToDrift().stateOf("t_tiny"));
        }

        @Test
        @DisplayName("确认本身失败：退回 UNKNOWN，刷新照常做完")
        void checkFailureDegrades() {
            previousSnapshot(OLD, "orders", "t_tiny");
            DescribeCapable describe = customer(List.of("orders"), true, 900);
            doThrow(ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "超时")).when(describe).existingObjects(any());

            SnapshotResult r = service.refresh(ID);

            assertNull(r.getGuardNote());
            assertEquals(Presence.UNKNOWN, presenceToDrift().stateOf("t_tiny"));
        }

        @Test
        @DisplayName("语义层提到过、从没进过快照的表也一起问（关系指向的表、口径名单里的表）")
        void semanticReferencedNamesAreAskedToo() {
            previousSnapshot(OLD, "orders");
            when(semanticService.referencedObjectNames(ID)).thenReturn(Set.of("users"));
            DescribeCapable describe = customer(List.of("orders"), true, 900);
            doReturn(Set.of()).when(describe).existingObjects(any());

            service.refresh(ID);

            assertEquals(Presence.ABSENT, presenceToDrift().stateOf("users"));
        }

        @Test
        @DisplayName("目录完整时不问")
        void completeCatalogNeverAsks() {
            previousSnapshot(OLD, "orders", "t_gone");
            DescribeCapable describe = customer(List.of("orders"), false, 1);

            service.refresh(ID);

            verify(describe, never()).existingObjects(any());
            verify(semanticService, never()).referencedObjectNames(any());
        }
    }

    // ================================================================ 条目 5：旧拉取不许覆盖新快照

    @Nested
    @DisplayName("旧拉取不许覆盖新快照")
    class OlderPull {

        /** ★ 需求里点名的：一次慢的手工刷新在一次更晚开始、已经看到表被删掉的定时刷新之后落库。 */
        @Test
        @DisplayName("★ 库里快照比本次拉取开始得晚：不落库、不做漂移处置、不派发、不动名单，说明原因")
        void newerSnapshotWins() {
            previousSnapshot(new Date(System.currentTimeMillis() + 3_600_000L), "orders");
            customer(List.of("orders", "t_dropped_later"), false, 2);

            SnapshotResult r = service.refresh(ID);

            assertNotNull(r.getGuardNote());
            assertTrue(r.getGuardNote().contains("更晚开始"), r.getGuardNote());
            assertTrue(r.getDiffs().isEmpty());
            assertNull(r.getSemanticStaled());
            verify(schemaMapper, never()).physicalDeleteByConnector(any());
            verify(schemaMapper, never()).insert(any(ConnectorSchema.class));
            verify(semanticService, never()).applyDrift(any(), any(), any(), any());
            verify(derive, never()).deriveAddedAsync(any(), any());
            verify(pending, never()).addAll(any());
            verify(semanticService, never()).purgeSampleValues(any());
        }

        @Test
        @DisplayName("★ 先锁连接行（FOR UPDATE）再读上一份快照，两次落库才会排队比较")
        @SuppressWarnings("unchecked")
        void locksConnectionRowBeforeReadingSnapshot() {
            previousSnapshot(OLD, "orders");
            customer(List.of("orders"), false, 1);

            service.refresh(ID);

            InOrder o = inOrder(connectionMapper, schemaMapper);
            ArgumentCaptor<Wrapper<Connection>> lock = ArgumentCaptor.forClass(Wrapper.class);
            o.verify(connectionMapper).selectList(lock.capture());
            o.verify(schemaMapper).selectList(any());
            o.verify(schemaMapper).physicalDeleteByConnector(ID);
            assertTrue(lock.getValue().getSqlSegment().contains("FOR UPDATE"), lock.getValue().getSqlSegment());
        }

        @Test
        @DisplayName("快照每行的 synced_at 是拉取开始的时刻，与返回的 syncedAt 一致")
        void rowsCarryPullStart() {
            previousSnapshot(OLD, "orders");
            customer(List.of("orders", "items"), false, 2);
            Date before = new Date();

            SnapshotResult r = service.refresh(ID);

            ArgumentCaptor<ConnectorSchema> ins = ArgumentCaptor.forClass(ConnectorSchema.class);
            verify(schemaMapper, times(2)).insert(ins.capture());
            for (ConnectorSchema s : ins.getAllValues()) {
                assertEquals(r.getSyncedAt(), s.getSyncedAt());
            }
            assertFalse(r.getSyncedAt().before(before));
        }
    }

    // ================================================================ 条目 4：待补报名单

    @Nested
    @DisplayName("待补报名单：漂移处置失败过的那次 REMOVED 不丢")
    class PendingRemovals {

        @Test
        @DisplayName("★ 快照一落库就先记名单、再做漂移处置；处置成功后划掉")
        void rememberedBeforeDriftAndSettledAfter() {
            previousSnapshot(OLD, "orders", "t_refund");
            customer(List.of("orders"), false, 1);

            service.refresh(ID);

            InOrder o = inOrder(pending, semanticService);
            o.verify(pending).addAll(List.of("t_refund"));
            o.verify(semanticService).applyDrift(eq(ID), any(), any(), eq(List.of("t_refund")));
            o.verify(pending).removeAll(List.of("t_refund"));
            verify(pending).expire(ConnectorSchemaService.PENDING_REMOVED_TTL_DAYS, TimeUnit.DAYS);
        }

        /** ★ 需求里点名的：漂移处置失败，下一次 diff 不再报这张表。名单让下一次刷新把它补上。 */
        @Test
        @DisplayName("★ 漂移处置失败：名单留着；下一次刷新把名单并进归因，成功后划掉")
        void failedDriftIsRetriedFromPendingList() {
            previousSnapshot(OLD, "orders", "t_refund");
            customer(List.of("orders"), false, 1);
            doThrow(new RuntimeException("库抖了")).when(semanticService).applyDrift(any(), any(), any(), any());

            SnapshotResult failed = service.refresh(ID);

            assertNull(failed.getSemanticStaled());
            verify(pending, never()).removeAll(any());

            doReturn(new StaleResult(1, 0)).when(semanticService).applyDrift(any(), any(), any(), any());
            previousSnapshot(OLD, "orders");
            when(pending.readAll()).thenReturn(new LinkedHashSet<>(List.of("t_refund")));
            customer(List.of("orders"), false, 1);

            SnapshotResult retried = service.refresh(ID);

            assertTrue(changed(retried, "REMOVED").isEmpty(), "这一次的 diff 已经不会再报它");
            verify(semanticService, times(2)).applyDrift(eq(ID), any(), any(), eq(List.of("t_refund")));
            verify(pending).removeAll(List.of("t_refund"));
        }

        @Test
        @DisplayName("还确认不了存不存在的名字（UNKNOWN）留在名单里")
        void unknownNamesStay() {
            previousSnapshot(OLD, "orders");
            when(pending.readAll()).thenReturn(new LinkedHashSet<>(List.of("t_x")));
            customer(List.of("orders"), true, 900);

            service.refresh(ID);

            assertEquals(List.of("t_x"), removedToDrift());
            verify(pending, never()).removeAll(any());
        }

        @Test
        @DisplayName("漂移处置有行被别处抢先改写：名单整份留到下一次")
        void concurrentSkipsKeepList() {
            previousSnapshot(OLD, "orders", "t_refund");
            customer(List.of("orders"), false, 1);
            when(semanticService.applyDrift(any(), any(), any(), any())).thenReturn(new StaleResult(0, 0, List.of(), 1));

            service.refresh(ID);

            verify(pending, never()).removeAll(any());
        }

        @Test
        @DisplayName("Redis 不可用：刷新照常做完，退化成只按这一次的 diff 归因")
        void redisDownDegrades() {
            when(redisson.getSet(anyString())).thenThrow(new RuntimeException("redis down"));
            previousSnapshot(OLD, "orders", "t_refund");
            customer(List.of("orders"), false, 1);

            SnapshotResult r = service.refresh(ID);

            assertNull(r.getGuardNote());
            assertEquals(List.of("t_refund"), removedToDrift());
        }
    }

    // ================================================================ 描述失败的表

    @Nested
    @DisplayName("describe 失败的表")
    class DescribeFailure {

        /** 只读账号的授权只到部分表时，那张表只有一条名字行。它在，只是这次没有列可比。 */
        @Test
        @DisplayName("★ 按「列出了、没描述」处理：处境 LISTED_ONLY，不以空列表进漂移处置")
        @SuppressWarnings("unchecked")
        void describeFailureIsListedOnly() {
            previousSnapshot(OLD, "orders", "t_denied");
            DescribeCapable describe = customer(List.of("orders", "t_denied"), false, 2);
            when(describe.describe("t_denied")).thenThrow(ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "没有权限"));

            service.refresh(ID);

            ArgumentCaptor<Map<String, Map<String, FieldDetail>>> fields = ArgumentCaptor.forClass(Map.class);
            ArgumentCaptor<SnapshotPresence> p = ArgumentCaptor.forClass(SnapshotPresence.class);
            verify(semanticService).applyDrift(eq(ID), fields.capture(), p.capture(), any());
            assertFalse(fields.getValue().containsKey("t_denied"), fields.getValue().keySet().toString());
            assertTrue(fields.getValue().containsKey("orders"));
            assertEquals(Presence.LISTED_ONLY, p.getValue().stateOf("t_denied"));
            verify(schemaMapper, times(2)).insert(any(ConnectorSchema.class));
        }
    }

    // ================================================================ 档位外的真实取值

    @Nested
    @DisplayName("刷新后顺手清掉档位外的真实取值")
    class TierSweep {

        @Test
        @DisplayName("★ 档位不允许真实取值：删一遍（接住档位下调之前就在跑的采集、以及上线之前降过档的连接）")
        void sweepsWhenTierDisallows() {
            previousSnapshot(OLD, "orders");
            customer(List.of("orders"), false, 1);

            service.refresh(ID);

            verify(semanticService).purgeSampleValues(ID);
        }

        @Test
        @DisplayName("第 3 档：不删")
        void keepsOnSampleValuesTier() {
            row.setSemanticDataTier("SAMPLE_VALUES");
            previousSnapshot(OLD, "orders");
            customer(List.of("orders"), false, 1);

            service.refresh(ID);

            verify(semanticService, never()).purgeSampleValues(any());
        }

        @Test
        @DisplayName("读不到档位：不删（答错的方向与注入路径相反）；删除失败不让刷新失败")
        void unreadableTierDoesNotSweepAndFailureIsQuiet() {
            previousSnapshot(OLD, "orders");
            customer(List.of("orders"), false, 1);
            when(semanticService.purgeSampleValues(ID)).thenThrow(new RuntimeException("库抖了"));

            assertNotNull(service.refresh(ID));

            when(connectionMapper.selectById(ID)).thenReturn(row, (Connection) null);
            service.refresh(ID);
            verify(semanticService, times(1)).purgeSampleValues(ID);
        }
    }
}
