package com.jimeng.dataserver.ai.connector.runtime;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.WritePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接入探测里<b>写策略与只读判定的联动</b>。
 *
 * <p>这张表之所以要一格一格钉死，是因为它的每一个格子出错都<b>不会报错</b>：
 *
 * <ul>
 *   <li>只读策略 + 可写账号如果被放过 → 界面显示「只读」，实际这条连接的账号能改客户的生产数据。</li>
 *   <li>只读策略 + 判不出来如果被放过 → 等于根本没验，而记录上写着「已验证」。</li>
 *   <li>开了写策略但账号只读时若照样声明 WRITE → 模型看到「我能写」，一写就被数据库拒，
 *       而它会把那个错误理解成「这次没成功」并重试。</li>
 * </ul>
 *
 * <p>不连真库：三步探测的编排全在本类里，每一步的实现由 mock 顶替正合适。
 */
class ConnectorProbeServiceTest {

    private ConnectorRegistry registry;
    private Connector connector;
    private ConnectorSession session;
    private ConnectorProbeService probeService;

    @BeforeEach
    void setUp() {
        registry = mock(ConnectorRegistry.class);
        connector = mock(Connector.class);
        session = mock(ConnectorSession.class);
        when(registry.require("MYSQL")).thenReturn(connector);
        when(connector.open(any())).thenReturn(session);
        // 类型层面支持写——把「类型不支持」这个变量固定住，本类只看策略与账号这两维。
        when(connector.declaredCapabilities())
                .thenReturn(Set.of(Capability.QUERY, Capability.DESCRIBE, Capability.WRITE));
        when(session.probeCapabilities()).thenReturn(Set.of(Capability.QUERY, Capability.DESCRIBE));
        probeService = new ConnectorProbeService(registry);
    }

    private static ConnectorInstance inst(WritePolicy policy) {
        return new ConnectorInstance(1L, "t1", "MYSQL", "crm", "CRM 库",
                Map.of("host", "db.example.com"), "pwd", "direct", policy);
    }

    private ConnectorProbeService.ProbeReport probeWith(WritePolicy policy, ReadOnlyVerdict verdict) {
        when(session.verifyReadOnly()).thenReturn(verdict);
        return probeService.probe(inst(policy));
    }

    private static ReadOnlyVerdict confirmed() {
        return ReadOnlyVerdict.confirmed("数据库在表/库级别拒绝了写操作（错误码 1142）");
    }

    private static ReadOnlyVerdict writable() {
        return ReadOnlyVerdict.writable("账号具备该库的写权限");
    }

    private static ReadOnlyVerdict unknown() {
        return ReadOnlyVerdict.unknown("探测返回了无法判定的错误（错误码 9999）");
    }

    // ================================================================ 只读策略

    /**
     * ★ 只读策略下，「确认只读」是唯一放行条件。另外两种都必须拦，而且话术必须不同——
     * 「账号能写」要客户去换账号或改策略，「判不出来」要客户去查权限配置，做的事完全不一样。
     */
    @Nested
    @DisplayName("写策略 = FORBIDDEN")
    class PolicyForbidden {

        @Test
        void 确认只读_通过且不声明写能力() {
            var r = probeWith(WritePolicy.FORBIDDEN, confirmed());
            assertTrue(r.ok());
            assertNull(r.failureReason());
            assertFalse(r.capabilities().contains(Capability.WRITE));
            assertTrue(r.capabilities().contains(Capability.QUERY));
        }

        @Test
        void 确认可写_不通过且提示改用只读账号() {
            var r = probeWith(WritePolicy.FORBIDDEN, writable());
            assertFalse(r.ok(), "只读策略配上一个能写的账号，必须拦在保存之前");
            assertNotNull(r.failureReason());
            assertTrue(r.failureReason().contains("只读账号"),
                    "话术要告诉客户下一步做什么，实际文案: " + r.failureReason());
            assertTrue(r.capabilities().isEmpty());
        }

        @Test
        void 判不出来_不通过且明说无法确认() {
            // 「不知道」不是「只读」。把它放过去，记录上会写着「已验证只读」，
            // 而实际上这个账号的权限从头到尾没人验过。
            var r = probeWith(WritePolicy.FORBIDDEN, unknown());
            assertFalse(r.ok());
            assertTrue(r.failureReason().contains("无法确认"),
                    "实际文案: " + r.failureReason());
            // 两种不通过的话术必须分开，否则客户按「换只读账号」去做，而问题其实在别处。
            assertFalse(r.failureReason().contains("这个账号具备写权限"));
        }
    }

    // ================================================================ 开放写的两档

    @Nested
    @DisplayName("写策略 = AUTO / REQUIRE_APPROVAL")
    class PolicyAllowsWrite {

        @Test
        void AUTO_加可写账号_通过且声明写能力() {
            var r = probeWith(WritePolicy.AUTO, writable());
            assertTrue(r.ok());
            assertTrue(r.capabilities().contains(Capability.WRITE));
        }

        /**
         * ★ 这格最容易写错：平台开了写，但客户给的是只读账号。
         *
         * <p>连接本身可用（查询照跑），所以不拦；但<b>绝不能声明 WRITE</b>——
         * 声明了，模型就会去写，然后在数据库那一层被拒。对模型来说那是一个「上游报错」，
         * 它的默认反应是重试，于是一次配置错误变成一串失败调用。
         */
        @Test
        void AUTO_加只读账号_通过但不声明写能力() {
            var r = probeWith(WritePolicy.AUTO, confirmed());
            assertTrue(r.ok(), "账号只读不影响这条连接查询可用，不该拦");
            assertFalse(r.capabilities().contains(Capability.WRITE),
                    "平台开了写、账号写不了，此时声明「能写」就是在骗界面和模型");
            assertTrue(r.capabilities().contains(Capability.QUERY));
        }

        @Test
        void REQUIRE_APPROVAL_加可写账号_通过且声明写能力() {
            // 审批那一档在平台侧同样是「放行」，只是执行前多一个人。
            // 若把它当成不允许写，能力里就没有 WRITE，审批队列永远收不到请求。
            var r = probeWith(WritePolicy.REQUIRE_APPROVAL, writable());
            assertTrue(r.ok());
            assertTrue(r.capabilities().contains(Capability.WRITE));
        }

        /**
         * 补一格表里没列的：开了写策略，但只读探针判不出来。
         *
         * <p>此处钉的是<b>当前实现的实际行为</b>：写能力的判据是 {@code !verdict.readOnly()}，
         * 而「判不出来」的 {@code readOnly()} 同样是 {@code false}，所以这一格会声明 WRITE。
         *
         * <p>这不是安全漏洞（承重层是客户侧的账号权限，账号真写不了数据库照样会拒），
         * 但它与「三者交集」那句注释里的「账号确实能写」并不完全一致。
         * 把它写成断言是为了：将来若有人把判据收紧成
         * {@code verdict.acceptable() == false && !verdict.undetermined()}，这条会红，
         * 那是<b>提醒去改这条测试</b>，而不是发现了 bug。
         */
        @Test
        void 判不出来时按当前实现仍声明写能力() {
            var r = probeWith(WritePolicy.AUTO, unknown());
            assertTrue(r.ok(), "策略允许写，判不出来不该拦下整条连接");
            assertNotNull(r.readOnly());
            assertTrue(r.readOnly().undetermined());
            assertTrue(r.capabilities().contains(Capability.WRITE));
        }
    }

    // ================================================================ 三步的顺序与容错

    @Nested
    @DisplayName("三步探测的顺序与容错")
    class ProbeSteps {

        @Test
        void ping失败时后两步不跑() {
            // 顺序不是排版：连不上时只读校验的结果没有意义，而能力探测会真的去读客户的库。
            // 反过来写，一个配错的可写账号会在被发现之前先被用一遍。
            doThrow(new ConnectorException(ConnectorErrorCode.UNREACHABLE, null)).when(session).ping();
            var r = probeService.probe(inst(WritePolicy.FORBIDDEN));

            assertFalse(r.ok());
            assertNull(r.readOnly(), "ping 就没过，只读判定应当是「没跑过」而不是某个值");
            assertTrue(r.capabilities().isEmpty());
            verify(session, never()).verifyReadOnly();
            verify(session, never()).probeCapabilities();
        }

        @Test
        void 只读校验抛异常时归到判不出来而不是确认只读() {
            // ★ 探针自己挂了 ≠ 账号是只读的。归成 confirmed 就等于「验证器一坏，全部放行」。
            when(session.verifyReadOnly())
                    .thenThrow(new ConnectorException(ConnectorErrorCode.TIMEOUT, null));
            var r = probeService.probe(inst(WritePolicy.FORBIDDEN));

            assertFalse(r.ok());
            assertNotNull(r.readOnly());
            assertTrue(r.readOnly().undetermined());
            assertFalse(r.readOnly().acceptable());
            assertTrue(r.failureReason().contains("无法确认"), "实际文案: " + r.failureReason());
        }

        @Test
        void 只读校验返回null时归到判不出来() {
            // 实现方忘了返回值，不能被当成通过。
            var r = probeWith(WritePolicy.FORBIDDEN, null);
            assertFalse(r.ok());
            assertTrue(r.readOnly().undetermined());
        }

        @Test
        void 能力探测失败时回退到类型声明而不是判死这条连接() {
            when(session.probeCapabilities())
                    .thenThrow(new ConnectorException(ConnectorErrorCode.TIMEOUT, null));
            var r = probeWith(WritePolicy.FORBIDDEN, confirmed());

            assertTrue(r.ok(), "一次抖动不该让一条本来可用的连接被判死");
            assertTrue(r.capabilities().contains(Capability.QUERY));
            // 回退来源里带着 WRITE（类型声明），但账号是只读的，所以仍然要被摘掉。
            assertFalse(r.capabilities().contains(Capability.WRITE));
        }

        @Test
        void 探测过程中的未归类异常不外泄原始信息() {
            // 这个 failureReason 会直接展示给客户，也可能进审计，不该混进底层异常文本。
            when(session.verifyReadOnly()).thenThrow(new IllegalStateException("jdbc://root:p@db-prod-01/shop"));
            var r = probeService.probe(inst(WritePolicy.FORBIDDEN));

            assertFalse(r.ok());
            assertFalse(r.failureReason().contains("db-prod-01"));
            assertFalse(r.failureReason().contains("root:p"));
        }
    }
}
