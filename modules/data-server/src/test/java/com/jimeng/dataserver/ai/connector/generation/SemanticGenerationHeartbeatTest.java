package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticGenerationHeartbeatTest {

    private ConnectorSemanticGenerationMapper generationMapper;
    private ConnectionMapper connectionMapper;
    private SemanticGenerationLease lease;
    private SemanticConnectionClaim claim;
    private SemanticConnectionClaimTest.MutableClock clock;
    private SemanticGenerationHeartbeat heartbeat;
    private ConnectorSemanticGeneration generation;

    @BeforeAll
    static void initLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Connection.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), ConnectorSemanticGeneration.class);
    }

    @BeforeEach
    void setUp() {
        generationMapper = mock(ConnectorSemanticGenerationMapper.class);
        connectionMapper = mock(ConnectionMapper.class);
        lease = mock(SemanticGenerationLease.class);
        claim = mock(SemanticConnectionClaim.class);
        clock = new SemanticConnectionClaimTest.MutableClock(Instant.parse("2026-09-17T01:02:03Z"));
        heartbeat = new SemanticGenerationHeartbeat(generationMapper, connectionMapper, lease, claim,
                new SemanticGenerationNotes(), clock);
        generation = generation();

        when(generationMapper.update(any(), any())).thenReturn(1);
        when(generationMapper.selectById(generation.getId())).thenReturn(generation);
        when(lease.renew("lease-token")).thenReturn(true);
        when(claim.renewIfDue(generation.getId(), generation.getOwnerToken(), Duration.ofMinutes(20))).thenReturn(true);
        when(claim.writeNote(eq(generation.getConnectorId()), anyString())).thenReturn(true);
        when(connectionMapper.selectById(generation.getConnectorId())).thenReturn(activeConnection());
        heartbeat.start(generation, "lease-token");
    }

    @AfterEach
    void tearDown() {
        heartbeat.stop();
        heartbeat.close();
        TenantContext.clear();
    }

    @Test
    @DisplayName("批次心跳 CAS 为 0 时置 lost，条件包含 owner 与两种活跃状态")
    @SuppressWarnings("unchecked")
    void 批次心跳0行lost() {
        when(generationMapper.update(any(), any())).thenReturn(0);

        heartbeat.tick();

        assertTrue(heartbeat.checkpoint().lost());
        verify(lease, never()).renew(anyString());
        ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemanticGeneration>> wrapper =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(generationMapper).update(any(), wrapper.capture());
        String sql = wrapper.getValue().getSqlSegment();
        assertTrue(sql.contains("owner_token"), sql);
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue("RUNNING"),
                wrapper.getValue().getParamNameValuePairs().toString());
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue("FINALIZING"),
                wrapper.getValue().getParamNameValuePairs().toString());
    }

    @Test
    @DisplayName("Redis 租约续租失败时置 lost")
    void 租约失败lost() {
        when(lease.renew("lease-token")).thenReturn(false);

        heartbeat.tick();

        assertTrue(heartbeat.checkpoint().lost());
        verify(connectionMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("读不到连接时置 connectionDeleted")
    void 连接缺失flag() {
        when(connectionMapper.selectById(generation.getConnectorId())).thenReturn(null);

        heartbeat.tick();

        assertTrue(heartbeat.checkpoint().connectionDeleted());
        assertFalse(heartbeat.checkpoint().connectionDisabled());
    }

    @Test
    @DisplayName("连接不再 ACTIVE 时置 connectionDisabled")
    void deactivatedFlag() {
        Connection disabled = activeConnection();
        disabled.setStatus("DISABLED");
        when(connectionMapper.selectById(generation.getConnectorId())).thenReturn(disabled);

        heartbeat.tick();

        assertTrue(heartbeat.checkpoint().connectionDisabled());
        assertFalse(heartbeat.checkpoint().connectionDeleted());
    }

    @Test
    @DisplayName("进度说明只在 k/g 变化且距上次满 20 秒时写入")
    void 进度按变化与20秒双条件节流() {
        heartbeat.tick();
        clock.advance(Duration.ofSeconds(5));
        generation.setDoneTables(3);
        heartbeat.tick();
        clock.advance(Duration.ofSeconds(15));
        heartbeat.tick();
        clock.advance(Duration.ofSeconds(30));
        heartbeat.tick();

        ArgumentCaptor<String> notes = ArgumentCaptor.forClass(String.class);
        verify(claim, times(2)).writeNote(eq(generation.getConnectorId()), notes.capture());
        assertTrue(notes.getAllValues().get(0).contains("已完成 2/10 张表（第 1/5 片）"));
        assertTrue(notes.getAllValues().get(1).contains("已完成 3/10 张表（第 1/5 片）"));
    }

    @Test
    @DisplayName("每一拍先设置真实租户，任何出路 finally 都清 TenantContext")
    void 每拍设置并清TenantContext() {
        when(generationMapper.update(any(), any())).thenAnswer(invocation -> {
            assertTrue("tenant-a".equals(TenantContext.get()));
            throw new IllegalStateException("heartbeat write failed");
        });

        heartbeat.tick();

        assertTrue(heartbeat.checkpoint().lost());
        assertNull(TenantContext.get());
    }

    @Test
    @DisplayName("已 lost 后的定时拍也会设置真实租户并 finally 清理")
    void lost后的心跳仍清TenantContext() {
        when(generationMapper.update(any(), any())).thenReturn(0);
        heartbeat.tick();
        TenantContext.set("stale-tenant");

        heartbeat.tick();

        assertNull(TenantContext.get());
    }

    @Test
    @DisplayName("start 与 stop 可重复调用")
    void startStop幂等() {
        heartbeat.start(generation, "lease-token");
        heartbeat.stop();
        heartbeat.stop();

        assertFalse(heartbeat.running());
        verify(generationMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("编排器可直接用批次 ownerToken 启动心跳")
    void start重载使用ownerToken作租约凭据() {
        heartbeat.stop();
        when(lease.renew(generation.getOwnerToken())).thenReturn(true);

        heartbeat.start(generation);
        heartbeat.tick();

        verify(lease).renew(generation.getOwnerToken());
    }

    @Test
    @DisplayName("片边界可强制写进度，不受 20 秒节流影响")
    void forceProgressNote跳过节流() {
        heartbeat.tick();

        heartbeat.forceProgressNote();

        verify(claim, times(2)).writeNote(eq(generation.getConnectorId()), anyString());
    }

    private static ConnectorSemanticGeneration generation() {
        ConnectorSemanticGeneration row = new ConnectorSemanticGeneration();
        row.setId(9L);
        row.setTenantId("tenant-a");
        row.setConnectorId(41L);
        row.setOwnerToken("owner-token");
        row.setStatus("RUNNING");
        row.setMode("DIRECT");
        row.setStartedAt(Date.from(Instant.parse("2026-09-17T00:00:00Z")));
        row.setTotalTables(10);
        row.setDoneTables(2);
        row.setGaveUpTables(0);
        row.setCurrentSliceNo(1);
        row.setSliceCount(5);
        return row;
    }

    private static Connection activeConnection() {
        Connection row = new Connection();
        row.setId(41L);
        row.setStatus("ACTIVE");
        row.setSemanticStatus("RUNNING");
        return row;
    }
}
