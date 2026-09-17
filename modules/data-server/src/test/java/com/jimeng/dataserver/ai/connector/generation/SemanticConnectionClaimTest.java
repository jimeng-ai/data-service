package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticConnectionClaimTest {

    private static final Long CONNECTOR_ID = 41L;
    private static final Long GENERATION_ID = 73L;
    private static final String OWNER = "owner-token";

    private ConnectionMapper connectionMapper;
    private ConnectorSemanticGenerationMapper generationMapper;
    private MutableClock clock;
    private SemanticConnectionClaim claim;
    private ExecutorService executor;

    @BeforeAll
    static void initLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Connection.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), ConnectorSemanticGeneration.class);
    }

    @BeforeEach
    void setUp() {
        connectionMapper = mock(ConnectionMapper.class);
        generationMapper = mock(ConnectorSemanticGenerationMapper.class);
        clock = new MutableClock(Instant.parse("2026-09-17T01:02:03Z"));
        claim = new SemanticConnectionClaim(connectionMapper, generationMapper, clock);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("CAS 条件逐项沿用旧 claim，并先读取认领前状态")
    @SuppressWarnings("unchecked")
    void CAS条件与旧claim一致() {
        Connection before = connection("READY", null);
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(before);
        when(connectionMapper.update(any(), any())).thenReturn(1);

        SemanticConnectionClaim.Credential credential = claim.claim(CONNECTOR_ID, null, "正在生成");

        assertNotNull(credential);
        assertEquals("READY", credential.previousSemanticStatus());
        assertEquals(Date.from(clock.instant()), credential.claimAt());
        InOrder order = inOrder(connectionMapper);
        order.verify(connectionMapper).selectById(CONNECTOR_ID);
        ArgumentCaptor<Connection> entity = ArgumentCaptor.forClass(Connection.class);
        ArgumentCaptor<LambdaUpdateWrapper<Connection>> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        order.verify(connectionMapper).update(entity.capture(), wrapper.capture());
        assertEquals("RUNNING", entity.getValue().getSemanticStatus());
        assertEquals("正在生成", entity.getValue().getSemanticNote());
        assertEquals(Date.from(clock.instant()), entity.getValue().getSemanticClaimAt());
        String sql = wrapper.getValue().getSqlSegment();
        assertTrue(sql.contains("semantic_status <>"), sql);
        assertTrue(sql.contains("semantic_status IS NULL"), sql);
        assertTrue(sql.contains("semantic_claim_at IS NULL"), sql);
        assertTrue(sql.contains("semantic_claim_at <"), sql);
    }

    @Test
    @DisplayName("续跑允许用本批次保存的旧 claimAt 接管自己的认领")
    @SuppressWarnings("unchecked")
    void 允许接管本批旧认领() {
        Date previous = Date.from(clock.instant().minus(Duration.ofMinutes(2)));
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(connection("RUNNING", previous));
        when(connectionMapper.update(any(), any())).thenReturn(1);

        assertNotNull(claim.claim(CONNECTOR_ID, previous, "继续生成"));

        ArgumentCaptor<LambdaUpdateWrapper<Connection>> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(connectionMapper).update(any(), wrapper.capture());
        String sql = wrapper.getValue().getSqlSegment();
        assertTrue(sql.contains("semantic_claim_at ="), sql);
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue(previous),
                wrapper.getValue().getParamNameValuePairs().toString());
    }

    @Test
    @DisplayName("认领写库异常返回 null，绝不按无并发继续")
    void 写库异常返回null() {
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(connection("READY", null));
        when(connectionMapper.update(any(), any())).thenThrow(new IllegalStateException("db down"));

        assertNull(claim.claim(CONNECTOR_ID, null, "正在生成"));
        assertNull(claim.currentCredential());
    }

    @Test
    @DisplayName("续期连接 CAS 为 0 时，回读已是新值视为提交成功")
    void 续期0行回读等于新值成功() {
        Date first = Date.from(clock.instant());
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(connection("READY", null));
        when(connectionMapper.update(any(), any())).thenReturn(1);
        when(generationMapper.update(any(), any())).thenReturn(1);
        assertNotNull(claim.claim(CONNECTOR_ID, null, "正在生成"));

        clock.advance(Duration.ofMinutes(20));
        Date renewedAt = Date.from(clock.instant());
        when(connectionMapper.update(any(), any())).thenReturn(0);
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(connection("RUNNING", renewedAt));

        assertTrue(claim.renew(GENERATION_ID, OWNER));
        assertFalse(claim.lost());
        assertEquals(renewedAt, claim.currentCredential().claimAt());
        assertFalse(first.equals(claim.currentCredential().claimAt()));
        verify(generationMapper).update(any(), any());
    }

    @Test
    @DisplayName("续期发现连接已被接管时置 lost，且不再写批次")
    void 被接管lost() {
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(connection("READY", null));
        when(connectionMapper.update(any(), any())).thenReturn(1);
        assertNotNull(claim.claim(CONNECTOR_ID, null, "正在生成"));

        clock.advance(Duration.ofMinutes(20));
        when(connectionMapper.update(any(), any())).thenReturn(0);
        when(connectionMapper.selectById(CONNECTOR_ID))
                .thenReturn(connection("RUNNING", Date.from(clock.instant().plusSeconds(1))));

        assertFalse(claim.renew(GENERATION_ID, OWNER));
        assertTrue(claim.lost());
        verify(generationMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("释放成功以最新凭据 CAS 写终态，并清掉内存凭据")
    @SuppressWarnings("unchecked")
    void release成功写终态并清凭据() {
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(connection("READY", null));
        when(connectionMapper.update(any(), any())).thenReturn(1);
        assertNotNull(claim.claim(CONNECTOR_ID, null, "正在生成"));
        Date credential = claim.currentCredential().claimAt();
        clock.advance(Duration.ofSeconds(8));

        assertTrue(claim.release(CONNECTOR_ID, "READY", "生成完成", true));
        assertNull(claim.currentCredential());
        assertFalse(claim.lost());

        ArgumentCaptor<Connection> entities = ArgumentCaptor.forClass(Connection.class);
        ArgumentCaptor<LambdaUpdateWrapper<Connection>> wrappers = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(connectionMapper, times(2)).update(entities.capture(), wrappers.capture());
        Connection terminal = entities.getAllValues().get(1);
        assertEquals("READY", terminal.getSemanticStatus());
        assertEquals("生成完成", terminal.getSemanticNote());
        assertEquals(Date.from(clock.instant()), terminal.getSemanticSyncedAt());
        LambdaUpdateWrapper<Connection> where = wrappers.getAllValues().get(1);
        where.getSqlSegment();
        assertTrue(where.getParamNameValuePairs().containsValue("RUNNING"), where.getParamNameValuePairs().toString());
        assertTrue(where.getParamNameValuePairs().containsValue(credential), where.getParamNameValuePairs().toString());
    }

    @Test
    @DisplayName("释放 CAS 为 0 说明凭据已丢，置 lost 且不清当前凭据")
    void release凭据丢失() {
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(connection("READY", null));
        when(connectionMapper.update(any(), any())).thenReturn(1);
        assertNotNull(claim.claim(CONNECTOR_ID, null, "正在生成"));
        when(connectionMapper.update(any(), any())).thenReturn(0);

        assertFalse(claim.release(CONNECTOR_ID, "FAILED", "生成失败", false));
        assertTrue(claim.lost());
        assertNotNull(claim.currentCredential());
    }

    @Test
    @DisplayName("释放到 FAILED 或恢复旧状态时不误盖 semantic_synced_at")
    void release不盖成功时间() {
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(connection("READY", null));
        when(connectionMapper.update(any(), any())).thenReturn(1);
        assertNotNull(claim.claim(CONNECTOR_ID, null, "正在生成"));

        assertTrue(claim.release(CONNECTOR_ID, "FAILED", "本轮失败", false));

        ArgumentCaptor<Connection> entities = ArgumentCaptor.forClass(Connection.class);
        verify(connectionMapper, times(2)).update(entities.capture(), any());
        assertNull(entities.getAllValues().get(1).getSemanticSyncedAt());
    }

    @Test
    @DisplayName("续期与连接说明并发写时，说明 CAS 使用续期后的最新凭据")
    @SuppressWarnings("unchecked")
    void 并发写用最新凭据() throws Exception {
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(connection("READY", null));
        AtomicInteger writes = new AtomicInteger();
        CountDownLatch noteWriteEntered = new CountDownLatch(1);
        when(connectionMapper.update(any(), any())).thenAnswer(invocation -> {
            if (writes.incrementAndGet() == 3) {
                noteWriteEntered.countDown();
            }
            return 1;
        });
        assertNotNull(claim.claim(CONNECTOR_ID, null, "正在生成"));
        Date oldCredential = claim.currentCredential().claimAt();
        clock.advance(Duration.ofMinutes(20));
        Date newCredential = Date.from(clock.instant());

        CountDownLatch batchWriteEntered = new CountDownLatch(1);
        CountDownLatch allowBatchWrite = new CountDownLatch(1);
        when(generationMapper.update(any(), any())).thenAnswer(invocation -> {
            batchWriteEntered.countDown();
            assertTrue(allowBatchWrite.await(2, TimeUnit.SECONDS));
            return 1;
        });

        Future<Boolean> renewing = executor.submit(() -> claim.renew(GENERATION_ID, OWNER));
        assertTrue(batchWriteEntered.await(2, TimeUnit.SECONDS));
        Future<Boolean> noteWriting = executor.submit(() -> claim.writeNote(CONNECTOR_ID, "进度更新"));
        assertFalse(noteWriteEntered.await(100, TimeUnit.MILLISECONDS), "续期事务未完成时说明写入必须等在 synchronized 外");
        allowBatchWrite.countDown();

        assertTrue(renewing.get(2, TimeUnit.SECONDS));
        assertTrue(noteWriting.get(2, TimeUnit.SECONDS));
        assertTrue(noteWriteEntered.await(2, TimeUnit.SECONDS));

        ArgumentCaptor<LambdaUpdateWrapper<Connection>> wrappers = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(connectionMapper, times(3)).update(any(), wrappers.capture());
        LambdaUpdateWrapper<Connection> noteWrapper = wrappers.getAllValues().get(2);
        noteWrapper.getSqlSegment();
        assertTrue(noteWrapper.getParamNameValuePairs().containsValue(newCredential),
                noteWrapper.getParamNameValuePairs().toString());
        assertFalse(noteWrapper.getParamNameValuePairs().containsValue(oldCredential),
                noteWrapper.getParamNameValuePairs().toString());
    }

    @Test
    @DisplayName("续期是一个事务边界")
    void 续期声明事务() throws Exception {
        Method renew = SemanticConnectionClaim.class.getMethod("renew", Long.class, String.class);
        assertNotNull(renew.getAnnotation(Transactional.class));
    }

    @Test
    @DisplayName("存在测试时钟构造器时，生产构造器仍显式声明 Spring 注入入口")
    void 生产构造器显式Autowired() throws Exception {
        assertNotNull(SemanticConnectionClaim.class
                .getConstructor(ConnectionMapper.class, ConnectorSemanticGenerationMapper.class)
                .getAnnotation(Autowired.class));
    }

    @Test
    @DisplayName("lockRow 逐字沿用连接行 FOR UPDATE 写法")
    @SuppressWarnings("unchecked")
    void lockRow() {
        claim.lockRow(CONNECTOR_ID);

        ArgumentCaptor<Wrapper<Connection>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(connectionMapper).selectList(wrapper.capture());
        assertTrue(wrapper.getValue().getSqlSegment().contains("FOR UPDATE"), wrapper.getValue().getSqlSegment());
    }

    private static Connection connection(String semanticStatus, Date claimAt) {
        Connection row = new Connection();
        row.setId(CONNECTOR_ID);
        row.setStatus("ACTIVE");
        row.setSemanticStatus(semanticStatus);
        row.setSemanticClaimAt(claimAt);
        return row;
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
