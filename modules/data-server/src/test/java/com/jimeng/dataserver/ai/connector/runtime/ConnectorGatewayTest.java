package com.jimeng.dataserver.ai.connector.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.model.WriteOutcome;
import com.jimeng.dataserver.ai.connector.model.WritePlan;
import com.jimeng.dataserver.ai.connector.model.WriteResult;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.service.PendingWriteService;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.cap.WriteCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.WriteOptions;
import com.jimeng.persistence.entity.AgentConnection;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.mapper.AgentConnectionMapper;
import com.jimeng.persistence.mapper.ConnectionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

/**
 * 网关横切层的 <b>fail-closed 行为</b>。
 *
 * <p>这些分支的共同点是：坏掉的时候<b>不会报错</b>，只会「悄悄放行」或「悄悄查不到」。
 * 没有回归保护的话，某次重构把某一处的 {@code throw} 改成 {@code return} 谁都不会发现，
 * 直到某天一次匿名对话碰到了客户的生产库。
 *
 * <p>另外三条（限流、审计、执行）不在这里测：它们坏掉是吵闹的，而且已在真实环境跑通过。
 */
class ConnectorGatewayTest {

    private ConnectionMapper connectionMapper;
    private AgentConnectionMapper agentConnectionMapper;
    private ConnectorProperties properties;
    private ConnectorRegistry registry;
    private ConnectorInstanceLoader loader;
    private PendingWriteService pendingWriteService;
    /**
     * 审计与 Redis 从前是就地 {@code mock(...)} 的。现在提到字段上，是因为「管理面那条路
     * 有没有真的经过限流与审计」本身就是这次要钉住的东西——只有留着引用才验得了。
     * 对已有用例没有任何影响：默认行为与就地 mock 完全一样（{@code opsForValue()} 返回 null，
     * 于是限流那一步走 fail-open 的 catch，与从前一致）。
     */
    private ConnectorAuditService auditService;
    private RedisTemplate<String, Object> redisTemplate;
    private ConnectorGateway gateway;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        connectionMapper = mock(ConnectionMapper.class);
        agentConnectionMapper = mock(AgentConnectionMapper.class);
        properties = new ConnectorProperties();
        registry = mock(ConnectorRegistry.class);
        loader = mock(ConnectorInstanceLoader.class);
        pendingWriteService = mock(PendingWriteService.class);
        auditService = mock(ConnectorAuditService.class);
        redisTemplate = mock(RedisTemplate.class);
        ObjectProvider<PendingWriteService> pendingWrites = mock(ObjectProvider.class);
        when(pendingWrites.getObject()).thenReturn(pendingWriteService);
        gateway = new ConnectorGateway(
                connectionMapper,
                agentConnectionMapper,
                registry,
                loader,
                properties,
                auditService,
                redisTemplate,
                pendingWrites);
        TenantContext.set("t1");
        AgentContext.set(AgentRuntimeView.builder().agentId(7L).tenantId("t1").build());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        AgentContext.clear();
    }

    /** 一条正常可用的连接行：ACTIVE、direct、能力齐。 */
    private Connection row() {
        Connection c = new Connection();
        c.setId(100L);
        c.setTenantId("t1");
        c.setName("crm");
        c.setKind("MYSQL");
        c.setStatus("ACTIVE");
        c.setTransport("direct");
        c.setCapabilityFlags("QUERY,DESCRIBE,HEALTH");
        return c;
    }

    @SuppressWarnings("unchecked")
    private void givenRow(Connection c) {
        when(connectionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(c);
    }

    @SuppressWarnings("unchecked")
    private void givenGranted(boolean granted) {
        when(agentConnectionMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(granted ? 1L : 0L);
    }

    private ConnectorException call() {
        return assertThrows(ConnectorException.class,
                () -> gateway.execute("crm", Capability.QUERY, "conn_query", session -> "never"));
    }

    // ================================================================

    @Test
    @DisplayName("总开关关闭 → 明确报错，不是静默返回空")
    void 总开关关闭时明确报错() {
        properties.setEnabled(false);
        assertEquals(ConnectorErrorCode.CONFIG_ERROR, call().getCode());
    }

    /**
     * ★ 不能依赖 MyBatis 拦截器的 {@code __no_tenant__} 哨兵兜底。
     * 那个兜底的表现是「查不到任何行」，会被上层读成「没有这条连接」，
     * 再被模型转述成「系统里没有这个数据源」——一个线程上下文丢失的 bug，
     * 最后变成一句自信的错误回答。
     */
    @Test
    @DisplayName("租户上下文缺失 → 明确报错，不靠哨兵兜底")
    void 租户上下文缺失时明确报错() {
        TenantContext.clear();
        assertEquals(ConnectorErrorCode.CONFIG_ERROR, call().getCode());
    }

    /**
     * ★ 最关键的一条。直连 {@code /data/claude/messages} 不带 {@code agent_id} 时
     * {@code AgentContext.get()} 就是 null。绝不能照抄
     * {@code SkillRuntimeService.filterByAgentAllowlist} 的 {@code if (agent == null) return packages;}
     * ——那是「不过滤」，用在连接器上等于任何一次匿名对话都能碰客户的生产库。
     */
    @Test
    @DisplayName("AgentContext 为 null → 拒绝（不是不过滤）")
    void 无Agent上下文时拒绝() {
        AgentContext.clear();
        givenRow(row());
        assertEquals(ConnectorErrorCode.FORBIDDEN, call().getCode());
    }

    @Test
    @DisplayName("连接不存在与未被授权 → 同形，避免用报错差异枚举出租户里有哪些连接")
    void 不存在与未授权同形() {
        givenRow(null);
        ConnectorException notFound = call();

        givenRow(row());
        givenGranted(false);
        ConnectorException noGrant = call();

        assertEquals(ConnectorErrorCode.NOT_FOUND, notFound.getCode());
        assertEquals(ConnectorErrorCode.NOT_FOUND, noGrant.getCode());
        assertEquals(notFound.getSafeDetail(), noGrant.getSafeDetail());
    }

    @Test
    @DisplayName("连接已停用 → 拒绝")
    void 停用的连接被拒() {
        Connection c = row();
        c.setStatus("DISABLED");
        givenRow(c);
        givenGranted(true);
        assertEquals(ConnectorErrorCode.CONFIG_ERROR, call().getCode());
    }

    /**
     * ★ 隧道未实现时要<b>明确报错</b>。{@code ConnectionResolver} 对这种行只 warn 后静默跳过，
     * 表现为「连接明明配好了却不生效」，排查成本极高。
     */
    @Test
    @DisplayName("transport=tunnel → 明确报错，不静默跳过")
    void 隧道未实现时明确报错() {
        Connection c = row();
        c.setTransport("tunnel");
        givenRow(c);
        givenGranted(true);
        ConnectorException e = call();
        assertEquals(ConnectorErrorCode.CONFIG_ERROR, e.getCode());
        assertTrue(e.getSafeDetail().contains("隧道"));
    }

    @Test
    @DisplayName("能力不匹配 → 拒绝，并说清实际可用的是什么")
    void 能力不匹配被拒() {
        Connection c = row();
        c.setCapabilityFlags("INVOKE,HEALTH");   // 没有 QUERY
        givenRow(c);
        givenGranted(true);
        ConnectorException e = call();
        assertEquals(ConnectorErrorCode.CONFIG_ERROR, e.getCode());
        assertTrue(e.getSafeDetail().contains("INVOKE"));
    }

    @Test
    @DisplayName("未探测（capability_flags 为空）→ 拒绝并提示去点测试连接")
    void 未探测的连接被拒() {
        Connection c = row();
        c.setCapabilityFlags(null);
        givenRow(c);
        givenGranted(true);
        assertTrue(call().getSafeDetail().contains("测试连接"));
    }

    // ---------------------------------------------------------------- listAuthorized

    @Test
    @DisplayName("listAuthorized：无 Agent 上下文 → 空集")
    void 列表在无Agent时返回空集() {
        AgentContext.clear();
        assertTrue(gateway.listAuthorized().isEmpty());
    }

    @Test
    @DisplayName("listAuthorized：无租户上下文 → 空集（这里返回空而不是抛，见实现注释）")
    void 列表在无租户时返回空集() {
        TenantContext.clear();
        assertTrue(gateway.listAuthorized().isEmpty());
    }

    @Test
    @DisplayName("listAuthorized：一条授权都没有 → 空集，且不去查 connection 表")
    void 列表在无授权时返回空集() {
        when(agentConnectionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        assertTrue(gateway.listAuthorized().isEmpty());
    }

    // ---------------------------------------------------------------- 能力标记解析

    @Test
    @DisplayName("能力标记：无法识别的值跳过而不是整条作废")
    void 能力标记容错() {
        Set<Capability> caps = ConnectorGateway.parseCapabilities("QUERY, SOMETHING_NEW ,DESCRIBE");
        assertEquals(Set.of(Capability.QUERY, Capability.DESCRIBE), caps);
    }

    @Test
    @DisplayName("能力标记：空 → 空集（而不是当成全能力）")
    void 能力标记为空时是空集() {
        assertTrue(ConnectorGateway.parseCapabilities(null).isEmpty());
        assertTrue(ConnectorGateway.parseCapabilities("  ").isEmpty());
    }

    /** 一条正向用例：六道闸全过之后，op 的返回值原样交回调用方。 */
    @Test
    @DisplayName("授权齐全 → 放行，并原样返回 op 的结果")
    void 授权齐全时放行() {
        givenRow(row());
        givenGranted(true);
        ConnectorInstance inst = new ConnectorInstance(
                100L, "t1", "MYSQL", "crm", "CRM", Map.of(), "pwd", "direct");
        when(loader.load(any(Connection.class))).thenReturn(inst);
        Connector connector = mock(Connector.class);
        when(registry.require("MYSQL")).thenReturn(connector);
        when(connector.open(inst)).thenReturn(mock(ConnectorSession.class));

        String out = gateway.execute("crm", Capability.QUERY, "conn_query", session -> "已执行");
        assertEquals("已执行", out);
    }

    /**
     * 网关对外的契约是「只抛 ConnectorException」——工具层靠它保证回灌模型的永远是那九类之一。
     * loader 抛未归类异常时必须被归一，而不是原样穿出去。
     */
    @Test
    @DisplayName("实例加载抛未归类异常 → 归一成 ConnectorException，不穿透")
    void 实例加载异常被归一() {
        givenRow(row());
        givenGranted(true);
        when(loader.load(any(Connection.class))).thenThrow(new IllegalStateException("内部细节 db-prod-01"));
        ConnectorException e = call();
        assertEquals(ConnectorErrorCode.CONFIG_ERROR, e.getCode());
        // 原始异常文本不准出现在对外文案里。
        assertTrue(!e.getMessage().contains("db-prod-01"));
    }

    // ================================================================ 写策略三档
    //
    // ★ 这三条是本类里最贴合「坏掉的时候不吵闹」那句话的用例。
    //   REQUIRE_APPROVAL 这一档曾经<b>整档失效</b>：网关只拦 FORBIDDEN，
    //   其余两档都直接落到执行——界面上写着「写需审批」，实际行为与「写自动」一模一样，
    //   没有任何报错、日志或异常能暴露它。没有回归保护，同一个缺陷会以同样的方式再回来。

    /** 一条可写连接（capability_flags 带 WRITE）+ 会话同时实现 WriteCapable。 */
    private WriteCapable givenWritableConnector(String policy) {
        Connection c = row();
        c.setCapabilityFlags("QUERY,DESCRIBE,WRITE");
        c.setWritePolicy(policy);
        givenRow(c);
        givenGranted(true);
        ConnectorInstance inst = new ConnectorInstance(
                100L, "t1", "MYSQL", "crm", "CRM", Map.of(), "pwd", "direct");
        when(loader.load(any(Connection.class))).thenReturn(inst);
        Connector connector = mock(Connector.class);
        when(registry.require("MYSQL")).thenReturn(connector);
        ConnectorSession session = mock(ConnectorSession.class,
                withSettings().extraInterfaces(WriteCapable.class));
        when(connector.open(inst)).thenReturn(session);
        return (WriteCapable) session;
    }

    private static final WriteOptions OPTS = new WriteOptions(100, 10);
    private static final String SQL = "UPDATE orders SET status='PAID' WHERE id=1";

    @Test
    @DisplayName("写策略=只读 → 拒绝，且连解析都不做")
    void 写策略只读时拒绝() {
        WriteCapable w = givenWritableConnector("FORBIDDEN");

        ConnectorException e = assertThrows(ConnectorException.class,
                () -> gateway.executeWrite("crm", SQL, OPTS, "conn_execute"));

        assertEquals(ConnectorErrorCode.FORBIDDEN, e.getCode());
        // 一行都不该动，也不该进审批队列——「只读」就是「这条路整个不通」。
        verify(w, never()).execute(anyString(), any());
        verify(pendingWriteService, never())
                .submit(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("写策略=写需审批 → 只入队，绝不执行")
    void 写策略需审批时入队不执行() {
        WriteCapable w = givenWritableConnector("REQUIRE_APPROVAL");
        when(w.plan(SQL)).thenReturn(new WritePlan(SQL, "UPDATE", "orders"));
        when(w.estimateAffectedRows(SQL)).thenReturn(37);
        when(pendingWriteService.submit(anyLong(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), any())).thenReturn(999L);

        WriteOutcome outcome = gateway.executeWrite("crm", SQL, OPTS, "conn_execute");

        assertTrue(outcome.pendingApproval());
        assertEquals(999L, outcome.approvalId());
        // ★ 这一条是整组用例的重点：返回了单号，但客户库上一行都没动。
        assertNull(outcome.result());
        verify(w, never()).execute(anyString(), any());
        // 入队记录的必须是<b>护栏解析出来的</b>结论，不是让审批服务自己再解析一遍。
        // 第 8 个参数是提交时预估的影响行数，它必须原样传到入队里——
        // 审批的人光看一条 SQL 判不出它命中 3 行还是 30 万行，这个数是他唯一的范围参考。
        verify(pendingWriteService).submit(100L, "crm", "t1", 7L, "UPDATE", "orders", SQL, 37);
    }

    /**
     * ★ 预估只是给人的参考，不是准入条件。它挂了就该按「估不出来」处理，
     * 而不是把一条本可以进审批队列的写请求挡在门外——那等于用一个可选功能的故障
     * 去否决一个必要功能。
     */
    @Test
    @DisplayName("预估影响行数失败 → 仍然入队，只是行数为空")
    void 预估失败不挡入队() {
        WriteCapable w = givenWritableConnector("REQUIRE_APPROVAL");
        when(w.plan(SQL)).thenReturn(new WritePlan(SQL, "UPDATE", "orders"));
        when(w.estimateAffectedRows(SQL)).thenThrow(new IllegalStateException("连接池炸了"));
        when(pendingWriteService.submit(anyLong(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), any())).thenReturn(1000L);

        WriteOutcome outcome = gateway.executeWrite("crm", SQL, OPTS, "conn_execute");

        assertTrue(outcome.pendingApproval(), "预估失败不该影响入队");
        assertEquals(1000L, outcome.approvalId());
        verify(pendingWriteService).submit(100L, "crm", "t1", 7L, "UPDATE", "orders", SQL, null);
    }

    @Test
    @DisplayName("写策略=写自动 → 直接执行，不入队")
    void 写策略自动时直接执行() {
        WriteCapable w = givenWritableConnector("AUTO");
        when(w.execute(SQL, OPTS)).thenReturn(new WriteResult(3, SQL, 12L));

        WriteOutcome outcome = gateway.executeWrite("crm", SQL, OPTS, "conn_execute");

        assertTrue(!outcome.pendingApproval());
        assertEquals(3, outcome.result().affectedRows());
        verify(pendingWriteService, never())
                .submit(anyLong(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), any());
    }

    /**
     * 写策略列为 null 是存量数据的常态（{@code write_policy} 这一列是后加的，
     * 老行在 DDL 补上默认值之前可能是 NULL）。此时必须按最严的那档走。
     */
    @Test
    @DisplayName("写策略为 null（存量行）→ 按只读拒绝，不是按放行兜底")
    void 写策略为空时按只读处理() {
        givenWritableConnector(null);
        ConnectorException e = assertThrows(ConnectorException.class,
                () -> gateway.executeWrite("crm", SQL, OPTS, "conn_execute"));
        assertEquals(ConnectorErrorCode.FORBIDDEN, e.getCode());
    }

    // ================================================================ 管理面入口
    //
    // ★ 这一组守的是一条很容易被写反的边界：管理面<b>没有 Agent</b>，所以第 4 步判不了；
    //   但「没有 Agent」绝不等于「没有身份」。在此之前管理面的唯一先例
    //   （ConnectorSchemaService.refresh）是自己 open() 绕过整个网关的——限流、并发闸、
    //   审计全都没有，而它一次要往客户的生产库打上百条查询。
    //   把那条路接进网关的同时，最怕的就是顺手把租户隔离也一起跳过去。

    @Nested
    @DisplayName("管理面入口 executeAsPlatform")
    class AsPlatform {

        private static final String OP = ConnectorAuditService.OP_SCHEMA_REFRESH;

        /** {@link #givenOpenable()} 建出来的连接器实现，验「一次都没 open」时要用。 */
        private Connector connector;

        /** 按 id 寻址（管理面不像模型那样用名字）。 */
        private void givenRowById(Connection c) {
            when(connectionMapper.selectById(100L)).thenReturn(c);
        }

        /** 六道闸全过的一条连接：实例、实现、会话都就绪。 */
        private ConnectorInstance givenOpenable() {
            ConnectorInstance inst = new ConnectorInstance(
                    100L, "t1", "MYSQL", "crm", "CRM", Map.of(), "pwd", "direct");
            when(loader.load(any(Connection.class))).thenReturn(inst);
            connector = mock(Connector.class);
            when(registry.require("MYSQL")).thenReturn(connector);
            when(connector.open(inst)).thenReturn(mock(ConnectorSession.class));
            return inst;
        }

        private ConnectorException callPlatform() {
            return assertThrows(ConnectorException.class,
                    () -> gateway.executeAsPlatform(100L, Capability.QUERY, OP, session -> "never"));
        }

        // ------------------------------------------------------------ 跳过的只有第 4 步

        /**
         * 管理面没有 Agent 身份，所以第 4 步无从判起——但也<b>只</b>跳过这一步。
         * 顺带钉住它不去查 {@code agent_connection}：查了就说明有人想用「平台 Agent」
         * 之类的东西补上这一步，那是另一个设计，不该悄悄长出来。
         */
        @Test
        @DisplayName("没有 AgentContext 照样放行（跳过的只有第 4 步）")
        void 无Agent上下文也放行() {
            AgentContext.clear();
            givenRowById(row());
            givenOpenable();

            String out = gateway.executeAsPlatform(100L, Capability.QUERY, OP, session -> "已执行");

            assertEquals("已执行", out);
            verify(agentConnectionMapper, never()).selectCount(any(LambdaQueryWrapper.class));
        }

        // ------------------------------------------------------------ 但租户隔离一点没松

        /**
         * ★ 最关键的一条：跳过 Agent 授权 ≠ 跳过租户隔离。
         * 后台任务要碰客户的生产库，就必须说清楚碰的是<b>哪一个</b>客户的库。
         */
        @Test
        @DisplayName("没有租户上下文 → 拒绝（后台任务必须自己 set 一个真租户）")
        void 无租户上下文时拒绝() {
            TenantContext.clear();
            givenRowById(row());
            assertEquals(ConnectorErrorCode.CONFIG_ERROR, callPlatform().getCode());
            verify(connectionMapper, never()).selectById(any());
        }

        /**
         * ★ {@code runAsSystem} 不是租户身份。它只把 SYSTEM_MODE 打开、让 MyBatis 拦截器
         * 别注入租户条件，{@code CURRENT_TENANT} 原样为空——这恰恰是最危险的组合：
         * 拦截器不管了，而调用方以为自己「以系统身份」拿到了通行证。
         */
        @Test
        @DisplayName("TenantContext.runAsSystem 不算数 → 照样拒绝")
        void runAsSystem不算租户身份() {
            TenantContext.clear();
            givenRowById(row());
            // 写成带 return 的块体，是为了避开 runAsSystem(Supplier) / runAsSystem(Runnable)
            // 这一对重载的歧义——表达式体的 lambda 两边都匹配，编译不过。
            ConnectorException e = TenantContext.runAsSystem(() -> {
                return callPlatform();
            });
            assertEquals(ConnectorErrorCode.CONFIG_ERROR, e.getCode());
        }

        /**
         * ★ 这一句显式的归属校验专治上面那种情形：系统模式下 {@code selectById} 捞得到
         * 别的租户的行。没有它，一个「顺手包了 runAsSystem」的后台任务就是跨租户访问
         * 客户生产库的入口，而且不报任何错。
         */
        @Test
        @DisplayName("连接属于别的租户 → 按「不存在」拒，且与真不存在同形")
        void 别的租户的连接取不到() {
            Connection other = row();
            other.setTenantId("t2");
            givenRowById(other);
            ConnectorException crossTenant = callPlatform();

            when(connectionMapper.selectById(100L)).thenReturn(null);
            ConnectorException missing = callPlatform();

            assertEquals(ConnectorErrorCode.NOT_FOUND, crossTenant.getCode());
            assertEquals(ConnectorErrorCode.NOT_FOUND, missing.getCode());
            // 同形：否则可以拿报错差异去枚举别的租户有哪些连接 id。
            assertEquals(missing.getSafeDetail(), crossTenant.getSafeDetail());
        }

        // ------------------------------------------------------------ 其余八步一步不少

        @Test
        @DisplayName("总开关关闭 → 拒绝（与 Agent 面同一段代码）")
        void 总开关对管理面同样有效() {
            properties.setEnabled(false);
            assertEquals(ConnectorErrorCode.CONFIG_ERROR, callPlatform().getCode());
        }

        @Test
        @DisplayName("能力不匹配 → 拒绝")
        void 能力校验对管理面同样有效() {
            Connection c = row();
            c.setCapabilityFlags("INVOKE,HEALTH");
            givenRowById(c);
            assertEquals(ConnectorErrorCode.CONFIG_ERROR, callPlatform().getCode());
        }

        @Test
        @DisplayName("连接已停用 → 拒绝")
        void 停用的连接对管理面同样拒绝() {
            Connection c = row();
            c.setStatus("DISABLED");
            givenRowById(c);
            assertEquals(ConnectorErrorCode.CONFIG_ERROR, callPlatform().getCode());
        }

        /**
         * ★ 限流这一步是整件事的由头：推导会往客户的生产库打几百条查询。
         * 管理面绕过网关的那些年，这一步对它压根不存在。
         */
        @Test
        @DisplayName("每租户速率对管理面同样生效")
        void 租户限流对管理面同样有效() {
            properties.getLimit().setPerTenantPerMinute(1);
            @SuppressWarnings("unchecked")
            ValueOperations<String, Object> ops = mock(ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(ops);
            when(ops.increment(anyString())).thenReturn(99L);
            givenRowById(row());
            givenOpenable();

            assertEquals(ConnectorErrorCode.RATE_LIMITED, callPlatform().getCode());
        }

        // ------------------------------------------------------------ 审计

        /**
         * ★ 设计里这条是硬要求：<b>客户的 DBA 要分得清哪些查询是平台在剖析、
         * 哪些是 Agent 在问数</b>。所以管理面的 operation 必须带 {@code platform.} 前缀，
         * 而 agentId 必须是 null——随手填个 0 或 -1 会在审计表里造出一个查无此人的 Agent。
         */
        @Test
        @DisplayName("审计落在 platform.* 名下，且 agentId 为空")
        void 审计与Agent面分得开() {
            givenRowById(row());
            givenOpenable();
            gateway.executeAsPlatform(100L, Capability.QUERY, OP, session -> "已执行");

            ArgumentCaptor<String> op = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Long> agentId = ArgumentCaptor.forClass(Long.class);
            verify(auditService).record(any(), agentId.capture(), any(), op.capture(),
                    any(), any(), anyLong(), anyBoolean(), any(), any());

            assertTrue(op.getValue().startsWith(ConnectorAuditService.PLATFORM_OP_PREFIX));
            assertNull(agentId.getValue(), "管理面没有 Agent，这一列就该是空的");
        }

        /** 另一半：Agent 面的名字与 agentId 原样不动，两条路在审计里天然分得开。 */
        @Test
        @DisplayName("Agent 面的审计名与 agentId 不受影响")
        void Agent面审计不受影响() {
            givenRow(row());
            givenGranted(true);
            givenOpenable();
            gateway.execute("crm", Capability.QUERY, "conn_query", session -> "已执行");

            ArgumentCaptor<String> op = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Long> agentId = ArgumentCaptor.forClass(Long.class);
            verify(auditService).record(any(), agentId.capture(), any(), op.capture(),
                    any(), any(), anyLong(), anyBoolean(), any(), any());

            assertEquals("conn_query", op.getValue());
            assertEquals(7L, agentId.getValue());
            assertFalse(op.getValue().startsWith(ConnectorAuditService.PLATFORM_OP_PREFIX));
            assertNotEquals(ConnectorAuditService.OP_SCHEMA_REFRESH, op.getValue());
        }

        /**
         * ★ 动作名传错要<b>当场炸</b>，而不是记一行 warn 然后照常执行。
         * 一次混进 Agent 命名空间的平台查询，事后没有任何办法从审计里择出来——
         * 「事后择不出来」正是这条约束存在的全部理由。
         *
         * <p>抛的是 {@code IllegalArgumentException} 而不是 {@code ConnectorException}：
         * 这是接错线，不是运行期故障。归成 ConnectorException 会被上层那些
         * 「失败只记日志」的 catch 顺手吞掉，于是又变成一次静默。
         */
        @Test
        @DisplayName("动作名不是 platform.* → 当场拒绝，且一个字节都没打到客户库")
        void 动作名必须是平台命名空间() {
            givenRowById(row());
            givenOpenable();

            assertThrows(IllegalArgumentException.class,
                    () -> gateway.executeAsPlatform(100L, Capability.QUERY, "conn_catalog", s -> "never"));
            assertThrows(IllegalArgumentException.class,
                    () -> gateway.executeAsPlatform(100L, Capability.QUERY, null, s -> "never"));

            verify(connector, never()).open(any());
            verify(auditService, never()).record(any(), any(), any(), any(),
                    any(), any(), anyLong(), anyBoolean(), any(), any());
        }
    }
}
