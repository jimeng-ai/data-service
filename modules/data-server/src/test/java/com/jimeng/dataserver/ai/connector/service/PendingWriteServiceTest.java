package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;
import com.jimeng.dataserver.ai.connector.model.WritePlan;
import com.jimeng.dataserver.ai.connector.model.WriteResult;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorGateway;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.cap.WriteCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.WriteOptions;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.ConnectorPendingWrite;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.ConnectorPendingWriteMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 写操作审批流。
 *
 * <p>这里的每个用例对应一类<b>会改掉客户生产数据</b>的事故，不是覆盖率填充：
 * 并发点两次批准执行两遍、陈年请求被批准、执行失败后被再点一次、
 * 审批时用错身份绕过 Agent 授权、分页不夹取把表拖垮。
 */
class PendingWriteServiceTest {

    private ConnectorPendingWriteMapper mapper;
    private AgentMapper agentMapper;
    private ConnectorGateway gateway;
    private ConnectorProperties properties;
    private PendingWriteService service;

    /**
     * MyBatis-Plus 的 lambda 列缓存平时由 Spring 扫 mapper 时建立，纯单测里没有 Spring，
     * 于是 {@code LambdaUpdateWrapper.set(Entity::getX, ...)} 会直接抛
     * 「can not find lambda cache for this entity」。这里手工建一次。
     *
     * <p>顺带的好处：字段名写错（比如实体上压根没有那个属性）在这里就会炸，
     * 而不是等到真连库时才发现。
     */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ConnectorPendingWrite.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(ConnectorPendingWriteMapper.class);
        agentMapper = mock(AgentMapper.class);
        gateway = mock(ConnectorGateway.class);
        properties = new ConnectorProperties();
        service = new PendingWriteService(mapper, agentMapper, gateway, properties);
    }

    @AfterEach
    void cleanup() {
        // 这些是 ThreadLocal，JUnit 的线程是复用的，不清会串到下一个用例。
        AgentContext.clear();
        TenantContext.clear();
    }

    // ================================================================ 夹具

    private ConnectorPendingWrite pendingRow() {
        ConnectorPendingWrite row = new ConnectorPendingWrite();
        row.setId(1001L);
        row.setTenantId("t1");
        row.setConnectorId(500L);
        row.setConnectorName("crm");
        row.setAgentId(7L);
        row.setOperation("UPDATE");
        row.setTargetTable("orders");
        row.setStatementText("UPDATE orders SET status = 'PAID' WHERE id = 9");
        row.setStatus(PendingWriteService.STATUS_PENDING);
        row.setCreateTime(new Date());
        row.setExpiresAt(new Date(System.currentTimeMillis() + 3600_000L));
        return row;
    }

    /** 一个什么都不做的会话，只是为了让 {@code instanceof WriteCapable} 成立。 */
    private static class FakeSession implements ConnectorSession, WriteCapable {
        private final AtomicInteger executions = new AtomicInteger();
        private String lastStatement;
        private WriteOptions lastOptions;

        @Override public void ping() {}
        @Override public ReadOnlyVerdict verifyReadOnly() { return null; }
        @Override public Set<Capability> probeCapabilities() { return Set.of(Capability.WRITE); }
        @Override public void close() {}

        /**
         * 审批路径永远不该走到这里：批准之后要执行的是<b>当初入队的那条语句</b>，
         * 再 plan 一次就成了「人批准的」和「实际执行的」两次独立解析。抛异常把它钉死。
         */
        @Override
        public WritePlan plan(String statement) {
            throw new AssertionError("审批执行路径不该再解析一次语句");
        }

        @Override
        public WriteResult execute(String statement, WriteOptions options) {
            executions.incrementAndGet();
            lastStatement = statement;
            lastOptions = options;
            return new WriteResult(3, statement, 12L);
        }
    }

    /** 让 mock 的网关真的把 op 跑起来，顺便记下执行时的 AgentContext。 */
    private FakeSession stubGatewayRunningOp(AtomicReference<AgentRuntimeView> seenAgent) {
        FakeSession session = new FakeSession();
        when(gateway.execute(anyString(), any(Capability.class), anyString(), any())).thenAnswer(inv -> {
            seenAgent.set(AgentContext.get());
            ConnectorGateway.Op<?> op = inv.getArgument(3);
            return op.apply(session);
        });
        return session;
    }

    // ================================================================ 提交

    @Test
    @DisplayName("超长语句在提交时就拒收，绝不落库后截断")
    void 超长语句被拒收() {
        // 批准时【就是照 statement_text 这一列执行】的。截断过的语句要么语法错，
        // 要么 WHERE 少一半变成范围完全不同的更新——所以只能在入口拒，不能截。
        String huge = "UPDATE orders SET memo = '" + "x".repeat(20000) + "' WHERE id = 1";
        ConnectorException e = assertThrows(ConnectorException.class,
                () -> service.submit(500L, "crm", "t1", 7L, "UPDATE", "orders", huge));
        assertEquals(ConnectorErrorCode.CONFIG_ERROR, e.getCode());
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("没有 Agent 身份的写请求不准进队列")
    void 无AgentId被拒收() {
        // 批准时要按「当初那个 Agent」判权限；没有 agentId 就只剩「按点批准的人判」，
        // 那等于超管点一下就绕过了 Agent 授权。
        ConnectorException e = assertThrows(ConnectorException.class,
                () -> service.submit(500L, "crm", "t1", null, "UPDATE", "orders", "UPDATE orders SET a=1 WHERE id=1"));
        assertEquals(ConnectorErrorCode.FORBIDDEN, e.getCode());
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("提交落 PENDING、带过期时间、语句原样保存")
    void 提交落库() {
        String sql = "UPDATE orders SET status = 'PAID' WHERE id = 9";
        service.submit(500L, "crm", "t1", 7L, "UPDATE", "orders", sql);

        ArgumentCaptor<ConnectorPendingWrite> cap = ArgumentCaptor.forClass(ConnectorPendingWrite.class);
        verify(mapper).insert(cap.capture());
        ConnectorPendingWrite saved = cap.getValue();
        assertEquals(PendingWriteService.STATUS_PENDING, saved.getStatus());
        assertEquals("t1", saved.getTenantId());
        assertEquals(7L, saved.getAgentId());
        // 一个字符都不能变：这一列是执行源。
        assertEquals(sql, saved.getStatementText());
        assertNotNull(saved.getExpiresAt());
        assertTrue(saved.getExpiresAt().after(new Date()), "过期时间必须在未来");
    }

    // ================================================================ 并发

    /**
     * ★ 本文件最重要的一条。两个超管同时点批准，绝不能执行两次。
     *
     * <p>抢锁靠的是 {@code UPDATE ... WHERE status='PENDING'} 的返回行数：这里模拟
     * 「另一个人刚抢走」——update 返回 0。此时必须一步都不往下走。
     */
    @Test
    @DisplayName("并发批准：没抢到就绝不执行，也不覆盖别人的决定")
    void 并发批准只执行一次() {
        ConnectorPendingWrite first = pendingRow();
        ConnectorPendingWrite alreadyDone = pendingRow();
        alreadyDone.setStatus(PendingWriteService.STATUS_APPROVED);
        alreadyDone.setAffectedRows(3);
        alreadyDone.setDecidedBy("42");
        when(mapper.selectById(1001L)).thenReturn(first, alreadyDone);
        // 抢锁失败
        when(mapper.update(isNull(), any())).thenReturn(0);

        PendingWriteView view = service.approve(1001L);

        // 一次都没执行
        verify(gateway, never()).execute(anyString(), any(Capability.class), anyString(), any());
        // 返回的是【别人处理后的真实状态】，不是我们以为的结果
        assertEquals(PendingWriteService.STATUS_APPROVED, view.getStatus());
        assertEquals("42", view.getDecidedBy());
        // 只尝试抢了一次，没有反复重试去覆盖
        verify(mapper, times(1)).update(isNull(), any());
    }

    @Test
    @DisplayName("已是终态的记录：再点批准不执行、不覆盖")
    void 已处理的不再执行() {
        ConnectorPendingWrite rejected = pendingRow();
        rejected.setStatus(PendingWriteService.STATUS_REJECTED);
        rejected.setErrorDetail("这条 WHERE 范围太大");
        when(mapper.selectById(1001L)).thenReturn(rejected);

        PendingWriteView view = service.approve(1001L);

        assertEquals(PendingWriteService.STATUS_REJECTED, view.getStatus());
        assertEquals("这条 WHERE 范围太大", view.getErrorDetail());
        verify(gateway, never()).execute(anyString(), any(Capability.class), anyString(), any());
        // 连 update 都不该发：别人的决定一个字段都不能被碰。
        verify(mapper, never()).update(isNull(), any());
    }

    // ================================================================ 过期

    /**
     * 陈年请求不准执行：模型三天前按当时数据算出的 WHERE 条件，今天命中的可能已是另一批行。
     */
    @Test
    @DisplayName("过期的写请求不执行，并落 EXPIRED")
    void 过期不执行() {
        ConnectorPendingWrite stale = pendingRow();
        stale.setExpiresAt(new Date(System.currentTimeMillis() - 1000L));
        when(mapper.selectById(1001L)).thenReturn(stale);
        when(mapper.update(isNull(), any())).thenReturn(1);

        PendingWriteView view = service.approve(1001L);

        verify(gateway, never()).execute(anyString(), any(Capability.class), anyString(), any());
        assertEquals(PendingWriteService.STATUS_EXPIRED, view.getStatus());
        assertNotNull(view.getErrorDetail(), "过期必须给出可展示的原因，否则操作者不知道为什么点了没反应");
        // 过期不是「有人决定的」，不该记审批人
        assertNull(view.getDecidedBy());
    }

    // ================================================================ 执行

    @Test
    @DisplayName("批准成功：按提交时那个 Agent 的身份执行，并回填影响行数")
    void 批准成功() {
        ConnectorPendingWrite row = pendingRow();
        when(mapper.selectById(1001L)).thenReturn(row);
        when(mapper.update(isNull(), any())).thenReturn(1);
        AtomicReference<AgentRuntimeView> seen = new AtomicReference<>();
        FakeSession session = stubGatewayRunningOp(seen);

        PendingWriteView view = service.approve(1001L);

        assertEquals(1, session.executions.get());
        assertEquals(PendingWriteService.STATUS_APPROVED, view.getStatus());
        assertEquals(3, view.getAffectedRows());
        // ★ 权限必须按【提交这条请求的 Agent】判，不是按点批准的人判：
        //   否则超管点一下就等于绕过了 Agent 授权。
        assertNotNull(seen.get(), "网关是 fail-closed 的，AgentContext 为空会被直接拒掉");
        assertEquals(7L, seen.get().getAgentId());
        assertEquals("t1", seen.get().getTenantId());
        // 执行的必须是人批准的那段文本，一个字符都没改
        assertEquals("UPDATE orders SET status = 'PAID' WHERE id = 9", session.lastStatement);
        // 护栏参数由平台强制下发
        assertEquals(properties.getWrite().getMaxAffectedRows(), session.lastOptions.maxAffectedRows());
        // 这是请求线程池的线程，执行完必须还原，否则污染下一个请求
        assertNull(AgentContext.get());
    }

    /**
     * ★ 执行失败落 FAILED，<b>不回退 PENDING</b>。
     *
     * <p>回退会让人以为「还能再点一次」，而那条语句可能已经部分生效（超时、连接断在提交途中），
     * 再点一次就是第二次执行。
     */
    @Test
    @DisplayName("执行失败落 FAILED 并带脱敏原因，不抛给调用方")
    void 执行失败落FAILED() {
        ConnectorPendingWrite row = pendingRow();
        when(mapper.selectById(1001L)).thenReturn(row);
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(gateway.execute(anyString(), any(Capability.class), anyString(), any()))
                .thenThrow(ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                        "这条语句会影响 300 行，超过平台单次上限 200 行。已回滚，数据未被修改"));

        // 不抛：失败是一种要落库的状态，不是一个只有文案的异常
        PendingWriteView view = service.approve(1001L);

        assertEquals(PendingWriteService.STATUS_FAILED, view.getStatus());
        assertFalse(PendingWriteService.STATUS_PENDING.equals(view.getStatus()), "绝不回退 PENDING");
        assertTrue(view.getErrorDetail().contains("已回滚"));
        // 两次 update：抢锁 → APPROVED，失败 → FAILED
        verify(mapper, times(2)).update(isNull(), any());
    }

    @Test
    @DisplayName("网关抛出未归类异常时，原始信息不落进 error_detail")
    void 未归类异常不泄露原始信息() {
        ConnectorPendingWrite row = pendingRow();
        when(mapper.selectById(1001L)).thenReturn(row);
        when(mapper.update(isNull(), any())).thenReturn(1);
        // 原始异常常带完整 SQL、主机名、连接参数，而 error_detail 随审批列表出网。
        when(gateway.execute(anyString(), any(Capability.class), anyString(), any()))
                .thenThrow(new IllegalStateException("jdbc:mysql://10.0.0.7:3306/crm?user=root&password=hunter2"));

        PendingWriteView view = service.approve(1001L);

        assertEquals(PendingWriteService.STATUS_FAILED, view.getStatus());
        assertFalse(view.getErrorDetail().contains("password"));
        assertFalse(view.getErrorDetail().contains("10.0.0.7"));
    }

    // ================================================================ 拒绝

    @Test
    @DisplayName("拒绝：记录原因，且不执行任何写")
    void 拒绝() {
        ConnectorPendingWrite row = pendingRow();
        when(mapper.selectById(1001L)).thenReturn(row);
        when(mapper.update(isNull(), any())).thenReturn(1);

        PendingWriteView view = service.reject(1001L, "WHERE 范围太大，先让它加时间条件");

        assertEquals(PendingWriteService.STATUS_REJECTED, view.getStatus());
        assertEquals("WHERE 范围太大，先让它加时间条件", view.getErrorDetail());
        verify(gateway, never()).execute(anyString(), any(Capability.class), anyString(), any());
    }

    @Test
    @DisplayName("拒绝原因留空时也放行，但补一句默认文案")
    void 拒绝原因可空() {
        ConnectorPendingWrite row = pendingRow();
        when(mapper.selectById(1001L)).thenReturn(row);
        when(mapper.update(isNull(), any())).thenReturn(1);

        PendingWriteView view = service.reject(1001L, "  ");

        assertEquals(PendingWriteService.STATUS_REJECTED, view.getStatus());
        // 列表里出现一排没有任何说明的 REJECTED 比没有原因更难排查
        assertNotNull(view.getErrorDetail());
        assertFalse(view.getErrorDetail().isBlank());
    }

    @Test
    @DisplayName("记录不存在（或不属于本租户）才抛异常")
    void 找不到才抛() {
        when(mapper.selectById(1001L)).thenReturn(null);
        ConnectorException e = assertThrows(ConnectorException.class, () -> service.approve(1001L));
        assertEquals(ConnectorErrorCode.NOT_FOUND, e.getCode());
    }

    // ================================================================ 查询

    @Test
    @DisplayName("分页大小必须夹取：上限 200、下限 1、默认 20")
    void 分页夹取() {
        when(mapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<ConnectorPendingWrite> p = inv.getArgument(0);
            p.setRecords(List.of());
            return p;
        });

        PendingWriteQuery big = new PendingWriteQuery();
        big.setSize(100000);
        // 不夹取的话，这张只增不减的表会被整页拖进内存
        assertEquals(200, service.query(big).getSize());

        PendingWriteQuery zero = new PendingWriteQuery();
        zero.setSize(0);
        zero.setPage(0);
        Page<PendingWriteView> clamped = service.query(zero);
        assertEquals(1, clamped.getSize());
        assertEquals(1, clamped.getCurrent());

        assertEquals(20, service.query(new PendingWriteQuery()).getSize());
        assertEquals(20, service.query(null).getSize(), "条件为 null 也要能查");
    }

    @Test
    @DisplayName("Agent 名批量解析，不退化成逐行查")
    void agentName批量解析() {
        List<ConnectorPendingWrite> rows = new ArrayList<>();
        for (long agentId : new long[]{7L, 8L, 7L}) {
            ConnectorPendingWrite r = pendingRow();
            r.setAgentId(agentId);
            rows.add(r);
        }
        when(mapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<ConnectorPendingWrite> p = inv.getArgument(0);
            p.setRecords(rows);
            return p;
        });
        Agent a7 = new Agent();
        a7.setId(7L);
        a7.setName("订单助手");
        when(agentMapper.selectBatchIds(any())).thenReturn(List.of(a7));

        List<PendingWriteView> out = service.query(new PendingWriteQuery()).getRecords();

        // 一页 200 行就是 200 次查询——这条断言就是为了防它退化。
        verify(agentMapper, times(1)).selectBatchIds(any());
        assertEquals("订单助手", out.get(0).getAgentName());
        // Agent 8 已被删除：名字留空，记录本身不跟着消失
        assertNull(out.get(1).getAgentName());
    }

    @Test
    @DisplayName("视图不带 tenantId：它不该随响应出网")
    void 视图不含租户() {
        long fields = java.util.Arrays.stream(PendingWriteView.class.getDeclaredFields())
                .filter(f -> f.getName().toLowerCase().contains("tenant"))
                .count();
        assertEquals(0, fields);
    }

    // ================================================================ 过期清理

    /**
     * ★ 定时线程上没有 TenantContext。不包 {@code runAsSystem} 的话，租户拦截器会回落到
     * {@code __no_tenant__} 哨兵，这条 UPDATE 一行都扫不到<b>而且不报错</b>——
     * 表现是「定时任务跑得好好的，过期请求却永远躺在待办里」。
     */
    @Test
    @DisplayName("过期清理跑在系统模式下，否则一行都扫不到还不报错")
    void 过期清理用系统模式() {
        AtomicReference<Boolean> systemMode = new AtomicReference<>(false);
        when(mapper.update(isNull(), any())).thenAnswer(inv -> {
            systemMode.set(TenantContext.isSystemMode());
            return 3;
        });

        assertEquals(3, service.expireStale());
        assertTrue(systemMode.get(), "必须用 TenantContext.runAsSystem 包住");
        // 跑完要还原，不能把系统模式泄漏给后续代码
        assertFalse(TenantContext.isSystemMode());
    }

    @Test
    @DisplayName("定时入口吞掉异常：一次失败不影响后续轮次")
    void 定时入口不抛() {
        when(mapper.update(isNull(), any())).thenThrow(new RuntimeException("库连不上"));
        // 不抛就是通过
        service.sweepExpired();
    }
}
