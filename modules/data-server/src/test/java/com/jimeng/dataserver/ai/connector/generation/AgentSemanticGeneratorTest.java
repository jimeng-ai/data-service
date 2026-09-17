package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.entity.ConnectorSemanticGenerationTable;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationTableMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("agent 语义层生成受理")
class AgentSemanticGeneratorTest {

    private static final Long CONNECTOR_ID = 7L;
    private static final Long GENERATION_ID = 101L;
    private static final Instant NOW = Instant.parse("2026-09-17T08:00:00Z");

    private ConnectorSemanticGenerationMapper generationMapper;
    private ConnectorSemanticGenerationTableMapper tableMapper;
    private ConnectionMapper connectionMapper;
    private SingleCallSemanticGenerator singleCall;
    private SemanticGenerationKick kick;
    private ConnectorProperties properties;
    private PlatformTransactionManager transactionManager;
    private TransactionStatus transactionStatus;
    private AgentSemanticGenerator generator;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, Connection.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemanticGeneration.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemanticGenerationTable.class);
        generationMapper = mock(ConnectorSemanticGenerationMapper.class);
        tableMapper = mock(ConnectorSemanticGenerationTableMapper.class);
        connectionMapper = mock(ConnectionMapper.class);
        singleCall = mock(SingleCallSemanticGenerator.class);
        kick = mock(SemanticGenerationKick.class);
        transactionManager = mock(PlatformTransactionManager.class);
        transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        properties = new ConnectorProperties();
        ConnectorProperties.SemanticAgent agent = properties.getSemantic().getAgent();
        agent.setEnabled(true);
        agent.setCallbackBaseUrl("http://localhost:10011/data");
        agent.getLlm().setBaseUrl("https://api.example.test/anthropic");
        agent.getLlm().setAuthToken("secret-never-persist");
        agent.getLlm().setModel("deepseek-flash");
        agent.getLlm().setAuthScheme("api-key");
        generator = new AgentSemanticGenerator(generationMapper, tableMapper, connectionMapper,
                singleCall, kick, new SemanticGenerationNotes(), properties,
                transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
        TenantContext.set("tenant-a");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("同连接已有QUEUED合并")
    void 同连接已有QUEUED合并() {
        when(generationMapper.selectOne(any())).thenReturn(batch("QUEUED", null));

        GenerationAck ack = generator.submit(request(9L));

        assertEquals(new GenerationAck(GeneratorKind.AGENT, true, GENERATION_ID,
                "已有一次生成在排队或进行中，本次合并"), ack);
        verify(generationMapper, never()).insert(any());
        verify(kick, never()).kick();
    }

    @Test
    @DisplayName("RUNNING且心跳新鲜合并")
    void RUNNING且心跳新鲜合并() {
        when(generationMapper.selectOne(any())).thenReturn(batch("RUNNING", Date.from(NOW.minusSeconds(180))));

        GenerationAck ack = generator.submit(request(9L));

        assertEquals(GENERATION_ID, ack.generationId());
        assertEquals("已有一次生成在排队或进行中，本次合并", ack.note());
        verify(generationMapper, never()).update(any(), any());
        verify(kick, never()).kick();
    }

    @Test
    @DisplayName("INTERRUPTED续跑复用批次并重置GAVE_UP")
    void INTERRUPTED续跑复用批次并重置GAVE_UP() {
        ConnectorSemanticGeneration active = batch("INTERRUPTED", Date.from(NOW.minusSeconds(999)));
        active.setMode("STAGED");
        when(generationMapper.selectOne(any())).thenReturn(active);
        when(generationMapper.update(any(), any())).thenReturn(1);
        when(tableMapper.update(any(), any())).thenReturn(2);
        when(generationMapper.selectCount(any())).thenReturn(0L);
        when(connectionMapper.update(any(), any())).thenReturn(1);

        GenerationAck ack = generator.submit(request(9L));

        assertEquals(GENERATION_ID, ack.generationId());
        assertEquals("继续上次未完成的生成，只补未覆盖的表", ack.note());
        ArgumentCaptor<ConnectorSemanticGeneration> generation =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        verify(generationMapper).update(generation.capture(), any());
        assertEquals("QUEUED", generation.getValue().getStatus());
        ArgumentCaptor<ConnectorSemanticGenerationTable> table =
                ArgumentCaptor.forClass(ConnectorSemanticGenerationTable.class);
        verify(tableMapper).update(table.capture(), any());
        assertEquals("PENDING", table.getValue().getStatus());
        assertEquals(0, table.getValue().getDispatchCount());
        assertEquals(0, table.getValue().getSubmitCount());
        assertEquals(0, table.getValue().getStructureRetries());
        verify(kick).kick();
    }

    @Test
    @DisplayName("心跳过期的RUNNING续跑")
    void 心跳过期的RUNNING续跑() {
        when(generationMapper.selectOne(any())).thenReturn(batch("RUNNING", Date.from(NOW.minusSeconds(181))));
        when(generationMapper.update(any(), any())).thenReturn(1);
        when(generationMapper.selectCount(any())).thenReturn(0L);
        when(connectionMapper.update(any(), any())).thenReturn(1);

        GenerationAck ack = generator.submit(request(9L));

        assertEquals("继续上次未完成的生成，只补未覆盖的表", ack.note());
        verify(kick).kick();
    }

    @Test
    @DisplayName("续跑重置GAVE_UP失败回滚批次CAS")
    void 续跑重置GAVE_UP失败回滚批次CAS() {
        when(generationMapper.selectOne(any())).thenReturn(batch("INTERRUPTED", null));
        when(generationMapper.update(any(), any())).thenReturn(1);
        when(tableMapper.update(any(), any())).thenThrow(new IllegalStateException("write failed"));

        GenerationAck ack = generator.submit(request(9L));

        assertFalse(ack.accepted());
        assertEquals("agent 生成受理失败", ack.note());
        verify(transactionManager).rollback(transactionStatus);
        verify(kick, never()).kick();
        verify(connectionMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("续跑写tokens_at_resume")
    void 续跑写tokens_at_resume() {
        ConnectorSemanticGeneration active = batch("INTERRUPTED", null);
        active.setInputTokens(11L);
        active.setOutputTokens(13L);
        active.setCacheWriteTokens(17L);
        active.setCacheReadTokens(1_000L);
        when(generationMapper.selectOne(any())).thenReturn(active);
        when(generationMapper.update(any(), any())).thenReturn(1);
        when(generationMapper.selectCount(any())).thenReturn(0L);
        when(connectionMapper.update(any(), any())).thenReturn(1);

        generator.submit(request(9L));

        ArgumentCaptor<ConnectorSemanticGeneration> update =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        verify(generationMapper).update(update.capture(), any());
        assertEquals(41L, update.getValue().getTokensAtResume());
    }

    @Test
    @DisplayName("Java推导进行中跳过")
    void Java推导进行中跳过() {
        when(generationMapper.selectOne(any())).thenReturn(null);
        Connection connection = connection(null);
        connection.setSemanticStatus("RUNNING");
        connection.setSemanticClaimAt(Date.from(NOW.minusSeconds(30 * 60L)));
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(connection);

        GenerationAck ack = generator.submit(request(9L));

        assertFalse(ack.accepted());
        assertNull(ack.generationId());
        assertEquals("同一条连接上已有一次推导在进行中，本次跳过", ack.note());
        verify(generationMapper, never()).insert(any());
        verify(kick, never()).kick();
    }

    @Test
    @DisplayName("从未成功生成走DIRECT")
    void 从未成功生成走DIRECT() {
        prepareNew(connection(null));

        GenerationAck ack = generator.submit(request(9L));

        assertEquals(GENERATION_ID, ack.generationId());
        ArgumentCaptor<ConnectorSemanticGeneration> inserted =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        verify(generationMapper).insert(inserted.capture());
        assertEquals("DIRECT", inserted.getValue().getMode());
        assertEquals("tenant-a", inserted.getValue().getTenantId());
        assertEquals("MANUAL_REGENERATE", inserted.getValue().getTriggerKind());
        assertEquals(9L, inserted.getValue().getTriggeredBy());
        verify(kick).kick();
    }

    @Test
    @DisplayName("已有synced_at走STAGED")
    void 已有synced_at走STAGED() {
        prepareNew(connection(Date.from(NOW.minusSeconds(3_600))));

        generator.submit(request(9L));

        ArgumentCaptor<ConnectorSemanticGeneration> inserted =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        verify(generationMapper).insert(inserted.capture());
        assertEquals("STAGED", inserted.getValue().getMode());
    }

    @Test
    @DisplayName("并发受理撞唯一键按合并处理")
    void 并发受理撞唯一键按合并处理() {
        ConnectorSemanticGeneration winner = batch("QUEUED", null);
        when(generationMapper.selectOne(any())).thenReturn(null, winner);
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(connection(null));
        when(generationMapper.insert(any())).thenThrow(new DuplicateKeyException("active unique"));

        GenerationAck ack = generator.submit(request(9L));

        assertTrue(ack.accepted());
        assertEquals(GENERATION_ID, ack.generationId());
        assertEquals("已有一次生成在排队或进行中，本次合并", ack.note());
        verify(kick, never()).kick();
    }

    @Test
    @DisplayName("缺触发人走单次推导")
    void 缺触发人走单次推导() {
        GenerationAck fallback = new GenerationAck(GeneratorKind.SINGLE_CALL, true, null, "已提交单次推导");
        when(singleCall.submit(any())).thenReturn(fallback);

        GenerationAck ack = generator.submit(request(null));

        assertEquals(fallback, ack);
        ArgumentCaptor<GenerationRequest> delegated = ArgumentCaptor.forClass(GenerationRequest.class);
        verify(singleCall).submit(delegated.capture());
        assertEquals("缺少触发人，无法签发回调凭据", delegated.getValue().degradeReason());
        verify(generationMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("排队说明只数本租户批次")
    void 排队说明只数本租户批次() {
        prepareNew(connection(null));
        when(generationMapper.selectCount(any())).thenAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.get());
            assertFalse(TenantContext.isSystemMode());
            return 2L;
        });

        GenerationAck ack = generator.submit(request(9L));

        assertEquals("语义层生成已排队（agent，全平台串行执行），本租户前面还有 2 个", ack.note());
        ArgumentCaptor<Connection> update = ArgumentCaptor.forClass(Connection.class);
        verify(connectionMapper).update(update.capture(), any());
        assertEquals(ack.note(), update.getValue().getSemanticNote());
    }

    @Test
    @DisplayName("配置快照不落authToken")
    void 配置快照不落authToken() {
        prepareNew(connection(null));

        generator.submit(request(9L));

        ArgumentCaptor<ConnectorSemanticGeneration> inserted =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        verify(generationMapper).insert(inserted.capture());
        String json = inserted.getValue().getConfigJson();
        assertTrue(json.contains("deepseek-flash"));
        assertFalse(json.contains("secret-never-persist"));
        assertFalse(json.toLowerCase().contains("authtoken"));
    }

    private void prepareNew(Connection connection) {
        when(generationMapper.selectOne(any())).thenReturn(null);
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(connection);
        when(generationMapper.insert(any())).thenAnswer(invocation -> {
            ConnectorSemanticGeneration row = invocation.getArgument(0);
            row.setId(GENERATION_ID);
            row.setCreateTime(Date.from(NOW));
            return 1;
        });
        when(generationMapper.selectCount(any())).thenReturn(0L);
        when(connectionMapper.update(any(), any())).thenReturn(1);
    }

    private static GenerationRequest request(Long userId) {
        return new GenerationRequest(CONNECTOR_ID, "tenant-a", userId,
                GenerationTrigger.MANUAL_REGENERATE, null);
    }

    private static Connection connection(Date syncedAt) {
        Connection connection = new Connection();
        connection.setId(CONNECTOR_ID);
        connection.setTenantId("tenant-a");
        connection.setStatus("ACTIVE");
        connection.setSemanticStatus("READY");
        connection.setSemanticSyncedAt(syncedAt);
        return connection;
    }

    private static ConnectorSemanticGeneration batch(String status, Date heartbeatAt) {
        ConnectorSemanticGeneration batch = new ConnectorSemanticGeneration();
        batch.setId(GENERATION_ID);
        batch.setTenantId("tenant-a");
        batch.setConnectorId(CONNECTOR_ID);
        batch.setMode("DIRECT");
        batch.setStatus(status);
        batch.setHeartbeatAt(heartbeatAt);
        batch.setCreateTime(Date.from(NOW.minusSeconds(600)));
        batch.setInputTokens(0L);
        batch.setOutputTokens(0L);
        batch.setCacheWriteTokens(0L);
        return batch;
    }
}
