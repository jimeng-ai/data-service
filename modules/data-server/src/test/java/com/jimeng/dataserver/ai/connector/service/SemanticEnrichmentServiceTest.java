package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.connector.businessview.BusinessViewGenerator;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorEnrichmentState;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorEnrichmentStateMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticEnrichmentServiceTest {

    private static final Long CONN_ID = 7L;
    private static final String TENANT = "t1";
    private static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");

    private ConnectorEnrichmentStateMapper stateMapper;
    private ConnectionMapper connectionMapper;
    private ConnectorSchemaMapper schemaMapper;
    private ConnectorSemanticMapper semanticMapper;
    private SemanticRelationDiscovery discovery;
    private BusinessViewGenerator generator;
    private ConnectorSemanticDeriveService deriveService;
    private ConnectorProperties properties;
    private ThreadPoolTaskExecutor executor;
    private SemanticEnrichmentService service;
    private ConnectorEnrichmentState state;
    private final List<LambdaUpdateWrapper<ConnectorEnrichmentState>> updates = new ArrayList<>();

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ConnectorEnrichmentState.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSchema.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemantic.class);
        TableInfoHelper.initTableInfo(assistant, Connection.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stateMapper = mock(ConnectorEnrichmentStateMapper.class);
        connectionMapper = mock(ConnectionMapper.class);
        schemaMapper = mock(ConnectorSchemaMapper.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        discovery = mock(SemanticRelationDiscovery.class);
        generator = mock(BusinessViewGenerator.class);
        deriveService = mock(ConnectorSemanticDeriveService.class);
        properties = new ConnectorProperties();
        executor = mock(ThreadPoolTaskExecutor.class);
        // 同步执行：派发出去的任务当场跑完，好在用例里看结果。
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        service = new SemanticEnrichmentService(stateMapper, connectionMapper, schemaMapper, semanticMapper,
                discovery, generator, deriveService, properties, executor, Clock.fixed(NOW, ZoneId.of("UTC")));

        state = new ConnectorEnrichmentState();
        state.setId(1L);
        state.setConnectorId(CONN_ID);
        state.setTenantId(TENANT);
        state.setRelationPassFingerprint("pass-0");
        when(stateMapper.selectOne(any())).thenReturn(state);
        when(stateMapper.claim(eq(TENANT), eq(CONN_ID), any(), any())).thenReturn(1);
        when(stateMapper.update(any(), any())).thenAnswer(inv -> {
            updates.add(inv.getArgument(1));
            return 1;
        });
        when(schemaMapper.selectMaps(any())).thenReturn(List.of(
                Map.of("object_name", "t_order", "content_hash", "h1", "uk", "[]")));
        when(semanticMapper.selectList(any())).thenReturn(List.of());
        discoveryReturns(null, "T_ORDER");
        when(generator.generate(eq(CONN_ID), any())).thenReturn(new BusinessViewGenerator.Result(3, 3, 2, 0, 0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void discoveryReturns(String modelError, String... touched) {
        when(discovery.discover(eq(CONN_ID), any())).thenReturn(new SemanticRelationDiscovery.Result(0, 1, 0, 0,
                true, "pass-1", modelError,
                new ConnectorSemanticService.DiscoveryWrite(touched.length, 0, 0, 0, 0, List.of(touched))));
    }

    /** 最后一次收尾写下去的值。 */
    private Map<String, Object> released() {
        LambdaUpdateWrapper<ConnectorEnrichmentState> last = updates.get(updates.size() - 1);
        assertTrue(last.getSqlSet().contains("last_status"), "最后一次写不是收尾：" + last.getSqlSet());
        return last.getParamNameValuePairs();
    }

    @Nested
    @DisplayName("一轮补全链")
    class OneRun {

        @Test
        @DisplayName("★ 顺序：关系发现 → 派发采样核对 → 业务文字 → 收尾 READY（记输入指纹、模型指纹、两段说明）")
        void 顺序() {
            service.onRequest(SemanticEnrichmentRequest.afterDerive(CONN_ID, TENANT, "推导完成"));

            InOrder order = inOrder(discovery, deriveService, generator);
            order.verify(discovery).discover(CONN_ID, "pass-0");
            order.verify(deriveService).dispatchValidation(CONN_ID, TENANT, "推导完成", null);
            order.verify(generator).generate(eq(CONN_ID), any());
            Map<String, Object> values = released();
            assertTrue(values.containsValue(ConnectorEnrichmentState.STATUS_READY));
            assertTrue(values.containsValue("pass-1"), "模型指纹前移");
            assertTrue(values.containsValue(service.fingerprint(CONN_ID)), "记跑完时的输入指纹");
            assertTrue(values.values().stream().anyMatch(v -> String.valueOf(v).startsWith("业务名 3 条")));
        }

        @Test
        @DisplayName("增量补写：核对范围 = 原本的表 ∪ 关系发现动过的表")
        void 增量范围() {
            service.onRequest(SemanticEnrichmentRequest.afterAddedDerive(CONN_ID, TENANT, "新增 1 张表", Set.of("t_refund")));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Set<String>> scope = ArgumentCaptor.forClass(Set.class);
            verify(deriveService).dispatchValidation(eq(CONN_ID), eq(TENANT), eq("新增 1 张表"), scope.capture());
            assertEquals(Set.of("t_refund", "t_order"), scope.getValue());
        }

        @Test
        @DisplayName("定时对账触发：只核对关系发现动过的表；一张都没动就不派发")
        void 对账范围() {
            service.onRequest(SemanticEnrichmentRequest.reconcile(CONN_ID, TENANT));
            verify(deriveService).dispatchValidation(CONN_ID, TENANT, null, Set.of("t_order"));

            discoveryReturns(null);
            service.onRequest(SemanticEnrichmentRequest.reconcile(CONN_ID, TENANT));
            verify(deriveService).dispatchValidation(any(), any(), any(), any());
        }

        @Test
        @DisplayName("★ agent 定稿触发：关系发现、业务文字照跑，但不派发采样核对（这条路径原来就不做）")
        void agent路径() {
            service.onRequest(SemanticEnrichmentRequest.afterAgentGeneration(CONN_ID, TENANT));

            verify(discovery).discover(CONN_ID, "pass-0");
            verify(generator).generate(eq(CONN_ID), any());
            verify(deriveService, never()).dispatchValidation(any(), any(), any(), any());
        }

        @Test
        @DisplayName("模型关系那一遍失败：业务文字照常生成，这一轮记 FAILED（6 小时后再试），模型指纹不前移")
        void 模型那一遍失败() {
            when(discovery.discover(eq(CONN_ID), any())).thenReturn(new SemanticRelationDiscovery.Result(0, 1, 0, 0,
                    true, "pass-0", "超时", new ConnectorSemanticService.DiscoveryWrite(1, 0, 0, 0, 0, List.of("T_ORDER"))));

            service.onRequest(SemanticEnrichmentRequest.afterDerive(CONN_ID, TENANT, "n"));

            verify(generator).generate(eq(CONN_ID), any());
            Map<String, Object> values = released();
            assertTrue(values.containsValue(ConnectorEnrichmentState.STATUS_FAILED));
            assertTrue(values.containsValue("pass-0"));
        }

        @Test
        @DisplayName("业务文字那一步抛错：记 FAILED、记开始时的输入指纹，关系发现的结果留在说明里")
        void 生成失败() {
            when(generator.generate(eq(CONN_ID), any())).thenThrow(new IllegalStateException("模型回复解析失败"));
            String before = service.fingerprint(CONN_ID);

            service.onRequest(SemanticEnrichmentRequest.afterDerive(CONN_ID, TENANT, "n"));

            Map<String, Object> values = released();
            assertTrue(values.containsValue(ConnectorEnrichmentState.STATUS_FAILED));
            assertTrue(values.containsValue(before));
            assertTrue(values.values().stream().anyMatch(v -> String.valueOf(v).contains("模型回复解析失败")));
        }

        @Test
        @DisplayName("★ 这条连接已有一轮在跑（抢不到认领）：不跑，但原本要派发的采样核对照样派出去")
        void 抢不到认领() {
            when(stateMapper.claim(eq(TENANT), eq(CONN_ID), any(), any())).thenReturn(0);

            service.onRequest(SemanticEnrichmentRequest.afterAddedDerive(CONN_ID, TENANT, "n", Set.of("t_refund")));

            verify(discovery, never()).discover(any(), any());
            verify(generator, never()).generate(any(), any());
            verify(deriveService).dispatchValidation(CONN_ID, TENANT, "n", Set.of("t_refund"));
        }

        @Test
        @DisplayName("认领按秒写、按 30 分钟判过期")
        void 认领参数() {
            service.onRequest(SemanticEnrichmentRequest.reconcile(CONN_ID, TENANT));

            ArgumentCaptor<Date> now = ArgumentCaptor.forClass(Date.class);
            ArgumentCaptor<Date> staleBefore = ArgumentCaptor.forClass(Date.class);
            verify(stateMapper).claim(eq(TENANT), eq(CONN_ID), now.capture(), staleBefore.capture());
            assertEquals(Date.from(NOW), now.getValue());
            assertEquals(Date.from(NOW.minusSeconds(30 * 60)), staleBefore.getValue());
        }

        @Test
        @DisplayName("跑到一半认领被别处接走（续期失败）：中止，不写结果")
        void 认领丢了() {
            when(stateMapper.update(any(), any())).thenReturn(0);
            when(generator.generate(eq(CONN_ID), any())).thenAnswer(inv -> {
                ((BusinessViewGenerator.Progress) inv.getArgument(1)).beforeModelCall();
                return new BusinessViewGenerator.Result(0, 0, 0, 0, 0);
            });
            // 同一秒内续期不打库；换到下一秒才会真的去续。
            service = new SemanticEnrichmentService(stateMapper, connectionMapper, schemaMapper, semanticMapper,
                    discovery, generator, deriveService, properties, executor, new TickingClock(NOW));

            service.onRequest(SemanticEnrichmentRequest.afterDerive(CONN_ID, TENANT, "n"));

            verify(generator, never()).generate(any(), any());
            verify(stateMapper, never()).update(any(), eqLastStatus());
        }

        @Test
        @DisplayName("状态行还没有：先建一行再认领")
        void 建状态行() {
            when(stateMapper.selectOne(any())).thenReturn(null);
            when(stateMapper.insert(any(ConnectorEnrichmentState.class))).thenAnswer(inv -> {
                ((ConnectorEnrichmentState) inv.getArgument(0)).setId(9L);
                return 1;
            });

            service.onRequest(SemanticEnrichmentRequest.reconcile(CONN_ID, TENANT));

            ArgumentCaptor<ConnectorEnrichmentState> row = ArgumentCaptor.forClass(ConnectorEnrichmentState.class);
            verify(stateMapper).insert(row.capture());
            assertEquals(TENANT, row.getValue().getTenantId());
            assertEquals(CONN_ID, row.getValue().getConnectorId());
            verify(discovery).discover(CONN_ID, null);
        }
    }

    @Nested
    @DisplayName("开关与派发失败")
    class Switches {

        @Test
        @DisplayName("★ 开关关着：不跑补全链，原本要派发的采样核对照原样派出去")
        void 开关关着() {
            properties.getSemantic().getEnrichment().setEnabled(false);

            service.onRequest(SemanticEnrichmentRequest.afterDerive(CONN_ID, TENANT, "n"));

            verify(executor, never()).execute(any(Runnable.class));
            verify(deriveService).dispatchValidation(CONN_ID, TENANT, "n", null);
            assertNull(service.reconcileOnce());
        }

        @Test
        @DisplayName("后台队列满：不报错，原本要派发的采样核对先派出去，补全链等下一轮对账")
        void 池满() {
            doThrow(new RejectedExecutionException("full")).when(executor).execute(any(Runnable.class));

            assertFalse(service.submit(SemanticEnrichmentRequest.afterDerive(CONN_ID, TENANT, "n")));

            verify(deriveService).dispatchValidation(CONN_ID, TENANT, "n", null);
        }

        @Test
        @DisplayName("agent 路径排不上：没有要补派的核对")
        void agent排不上() {
            doThrow(new RejectedExecutionException("full")).when(executor).execute(any(Runnable.class));
            service.submit(SemanticEnrichmentRequest.afterAgentGeneration(CONN_ID, TENANT));
            verify(deriveService, never()).dispatchValidation(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("定时对账")
    class Reconcile {

        private Connection connection(long id) {
            Connection c = new Connection();
            c.setId(id);
            c.setTenantId(TENANT);
            c.setSemanticStatus("READY");
            return c;
        }

        @BeforeEach
        void readyConnections() {
            when(connectionMapper.selectList(any())).thenReturn(List.of(connection(CONN_ID)));
        }

        private void stateIs(String lastStatus, String fingerprint, Instant lastAttempt, Instant claimAt) {
            state.setLastStatus(lastStatus);
            state.setInputFingerprint(fingerprint);
            state.setLastAttemptAt(lastAttempt == null ? null : Date.from(lastAttempt));
            state.setClaimAt(claimAt == null ? null : Date.from(claimAt));
            when(stateMapper.selectList(any())).thenReturn(List.of(state));
        }

        @Test
        @DisplayName("从没跑过：派发")
        void 从没跑过() {
            when(stateMapper.selectList(any())).thenReturn(List.of());
            assertEquals(CONN_ID, service.reconcileOnce());
            verify(discovery).discover(eq(CONN_ID), any());
        }

        @Test
        @DisplayName("★ 上次成功、输入指纹没变：不派发")
        void 指纹没变() {
            stateIs("READY", service.fingerprint(CONN_ID), NOW.minusSeconds(3600), null);
            assertNull(service.reconcileOnce());
            verify(discovery, never()).discover(any(), any());
        }

        @Test
        @DisplayName("输入指纹变了：派发")
        void 指纹变了() {
            stateIs("READY", "old", NOW.minusSeconds(3600), null);
            assertEquals(CONN_ID, service.reconcileOnce());
        }

        @Test
        @DisplayName("上次失败、输入没变：满 6 小时才重试")
        void 失败退避() {
            stateIs("FAILED", service.fingerprint(CONN_ID), NOW.minusSeconds(5 * 3600), null);
            assertNull(service.reconcileOnce());

            stateIs("FAILED", service.fingerprint(CONN_ID), NOW.minusSeconds(6 * 3600), null);
            assertEquals(CONN_ID, service.reconcileOnce());
        }

        @Test
        @DisplayName("认领还没过期（正在跑）：跳过")
        void 正在跑() {
            stateIs(null, null, null, NOW.minusSeconds(60));
            assertNull(service.reconcileOnce());
        }

        @Test
        @DisplayName("每一轮最多派发一个连接")
        void 一次一个() {
            when(connectionMapper.selectList(any())).thenReturn(List.of(connection(CONN_ID), connection(8L)));
            when(stateMapper.selectList(any())).thenReturn(List.of());
            assertEquals(CONN_ID, service.reconcileOnce());
            verify(discovery, never()).discover(eq(8L), any());
        }
    }

    private static LambdaUpdateWrapper<ConnectorEnrichmentState> eqLastStatus() {
        return org.mockito.ArgumentMatchers.argThat(w -> w != null && w.getSqlSet() != null
                && w.getSqlSet().contains("last_status"));
    }

    /** 每读一次往后走一秒的时钟：让续期真的发生。 */
    private static final class TickingClock extends Clock {
        private Instant now;

        TickingClock(Instant start) {
            this.now = start;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant current = now;
            now = now.plusSeconds(1);
            return current;
        }
    }
}
