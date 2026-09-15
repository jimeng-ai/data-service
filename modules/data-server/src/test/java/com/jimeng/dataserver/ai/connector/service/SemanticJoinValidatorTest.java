package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.guard.ReadOnlySqlGuard;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.model.QueryResult;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorGateway;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryOptions;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.mapper.ConnectionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S3 采样验证。
 *
 * <p>这里钉的每一条都是「坏掉的时候不会报错」的那种——采样验证的产出会被下游当成
 * 「这条关系能不能直接拿去 join」的依据，而它错的方式全是安静的：
 *
 * <ul>
 *   <li>档位不允许时返回空列表，下游读成「验过了，没问题」；</li>
 *   <li>超时被判成 REJECTED，一条正确的关系被一次慢查询悄悄删掉；</li>
 *   <li>整批探查包在一次 {@code executeAsPlatform} 里，客户的模型在整段剖析期间问不了数；</li>
 *   <li>在数值列上加了空串过滤，把 0 这个合法取值整批剔掉，包含率莫名其妙。</li>
 * </ul>
 */
class SemanticJoinValidatorTest {

    private ConnectorGateway gateway;
    private ConnectionMapper connectionMapper;
    private SemanticJoinValidator validator;
    private FakeSession session;

    /** 每次 {@code executeAsPlatform} 的审计动作名，按顺序记下来。 */
    private List<String> auditOps;

    @BeforeEach
    void setUp() {
        gateway = mock(ConnectorGateway.class);
        connectionMapper = mock(ConnectionMapper.class);
        validator = new SemanticJoinValidator(gateway, connectionMapper, new PiiFilter());
        // 不走 @PostConstruct，直接用声明处的默认值；只把节流调到 1 毫秒，
        // 否则默认 30 次/分钟意味着每条用例要睡好几秒。
        validator.probesPerMinute = 60_000;
        session = new FakeSession();
        auditOps = new ArrayList<>();

        when(gateway.executeAsPlatform(any(), any(), any(), any())).thenAnswer(inv -> {
            auditOps.add(inv.getArgument(2));
            ConnectorGateway.Op<?> op = inv.getArgument(3);
            return op.apply(session);
        });

        TenantContext.set("t1");
        givenConnection(SemanticDataTier.DERIVED_STATS.name());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ================================================================ 夹具

    private void givenConnection(String tier) {
        Connection c = new Connection();
        c.setId(100L);
        c.setTenantId("t1");
        c.setName("crm");
        c.setSemanticDataTier(tier);
        when(connectionMapper.selectById(100L)).thenReturn(c);
    }

    /** orders.user_id（数值外键） → users.id（数值主键），外加一个字符型列备用。 */
    private Map<String, Map<String, FieldDetail>> snapshot() {
        Map<String, Map<String, FieldDetail>> out = new LinkedHashMap<>();
        Map<String, FieldDetail> orders = new LinkedHashMap<>();
        orders.put("user_id", new FieldDetail("user_id", "bigint(20)", true, "下单人", null));
        orders.put("out_trade_no", new FieldDetail("out_trade_no", "varchar(64)", true, "外部单号", null));
        out.put("orders", orders);
        Map<String, FieldDetail> users = new LinkedHashMap<>();
        users.put("id", new FieldDetail("id", "bigint(20)", false, "主键", "主键"));
        users.put("open_id", new FieldDetail("open_id", "varchar(64)", true, "微信 openid", null));
        out.put("users", users);
        return out;
    }

    private static SemanticJoinValidator.JoinCandidate candidate() {
        return SemanticJoinValidator.JoinCandidate.of("orders", "user_id", "users", "id");
    }

    private static QueryResult counts(String a, long av, String b, long bv) {
        List<Object> row = new ArrayList<>();
        row.add(av);
        row.add(bv);
        return new QueryResult(List.of(a, b), List.of(row), false, null, "已执行", 3L);
    }

    /** 包含率探查的返回。 */
    private static QueryResult containment(long sampleN, long matchN) {
        return counts("sample_n", sampleN, "match_n", matchN);
    }

    /** 唯一性侧写的返回。 */
    private static QueryResult profile(long rows, long distinct) {
        return counts("row_n", rows, "distinct_n", distinct);
    }

    private SemanticJoinValidator.JoinValidationResult validateOne() {
        return validator.validate(100L, List.of(candidate()), snapshot());
    }

    // ================================================================ 档位闸

    @Nested
    @DisplayName("★ 档位闸：未启用派生统计时说「没查」，不是返回空")
    class TierGate {

        /**
         * ★ 整个组件最容易被写错的一处。包含率、基数、distinct 数<b>都是</b>派生统计，
         * 第 1 档不许它们出库。此时返回空列表是灾难性的：下游拿到「一条问题都没有」，
         * 会把一批从没被验过的关系当成验过的用。
         */
        @Test
        @DisplayName("第 1 档 → TIER_BLOCKED，每条候选都有一条「没查」的结论，且一个字节都没打出去")
        void 纯元数据档不探查也不返回空() {
            givenConnection(SemanticDataTier.METADATA_ONLY.name());

            SemanticJoinValidator.JoinValidationResult r = validateOne();

            assertEquals(SemanticJoinValidator.OUT_TIER_BLOCKED, r.getOutcome());
            assertFalse(r.getVerdicts().isEmpty(), "不能返回空结果——空等于「验过了没问题」");
            assertEquals(1, r.getVerdicts().size());
            assertTrue(r.getNote().contains("未启用派生统计"), r.getNote());

            SemanticJoinValidator.JoinVerdict v = r.getVerdicts().get(0);
            assertEquals(ConnectorSemanticService.V_NONE, v.getVerified(), "没查就该是 NONE，不是 UNDECIDABLE");
            assertFalse(v.isProbed());
            assertFalse(v.isAutoJoinable());
            assertTrue(v.getReason().contains("未启用派生统计"), v.getReason());

            verify(gateway, never()).executeAsPlatform(any(), any(), any(), any());
            assertEquals(0, r.getProbeCount());
        }

        /**
         * 库里那一格是 NULL 表示客户<b>没选过</b>档位，不表示他选了最严的那档。
         * {@code SemanticDataTier.DEFAULT} 是第 2 档，所以这种连接照常验。
         */
        @Test
        @DisplayName("档位为空 → 按默认档（第 2 档）跑，不被当成第 1 档")
        void 档位为空按默认档() {
            givenConnection(null);
            session.answers.add(containment(1000, 970));
            session.answers.add(profile(1000, 50));
            session.answers.add(profile(1000, 1000));

            SemanticJoinValidator.JoinValidationResult r = validateOne();

            assertEquals(SemanticJoinValidator.OUT_RAN, r.getOutcome());
        }

        /** 认不出来的脏值落到最严档——与 {@code SemanticDataTier} 的兜底方向一致。 */
        @Test
        @DisplayName("档位是认不出来的脏值 → 按最严档处理，不探查")
        void 脏档位按最严处理() {
            givenConnection("SUPER_OPEN");
            assertEquals(SemanticJoinValidator.OUT_TIER_BLOCKED, validateOne().getOutcome());
            verify(gateway, never()).executeAsPlatform(any(), any(), any(), any());
        }
    }

    // ================================================================ 判定

    @Nested
    @DisplayName("包含率判定")
    class Verdicts {

        @Test
        @DisplayName("包含率 97% → CONFIRMED，并把采样量与命中数亮出来")
        void 高包含率确认() {
            session.answers.add(containment(1000, 970));
            session.answers.add(profile(1000, 50));      // orders.user_id 不唯一
            session.answers.add(profile(1000, 1000));    // users.id 唯一

            SemanticJoinValidator.JoinVerdict v = validateOne().getVerdicts().get(0);

            assertEquals(ConnectorSemanticService.V_CONFIRMED, v.getVerified());
            assertTrue(v.isProbed());
            assertEquals(1000, v.getSampleN());
            assertEquals(970, v.getMatchN());
            assertEquals(0.97d, v.getContainment(), 1e-9);
            assertEquals(SemanticJoinValidator.CARD_MANY_ONE, v.getCardinality());
            assertTrue(v.isAutoJoinable(), "右侧唯一，N:1 可以自动 join");
            // 设计要求把「采样多少、命中多少」原样端给模型看，光给一个结论没法被质疑。
            assertTrue(v.getBasis().contains("采样 1000"), v.getBasis());
            assertTrue(v.getBasis().contains("命中 970"), v.getBasis());
            assertTrue(v.getBasis().contains("97.0%"), v.getBasis());
        }

        @Test
        @DisplayName("包含率 62% → WEAK，并明说「用之前先自己核一次」")
        void 中等包含率弱支持() {
            session.answers.add(containment(1000, 620));
            session.answers.add(profile(1000, 50));
            session.answers.add(profile(1000, 1000));

            SemanticJoinValidator.JoinVerdict v = validateOne().getVerdicts().get(0);

            assertEquals(ConnectorSemanticService.V_WEAK, v.getVerified());
            assertTrue(v.getBasis().contains("先自己核一次"), v.getBasis());
        }

        /**
         * 唯一一条会否定关系的路径，而且只可能来自一次真实测量。
         * 顺带钉住「被否掉就不再为它花钱测基数」——被拒的关系没人会拿去 join。
         */
        @Test
        @DisplayName("包含率 12% → REJECTED，且不再为它做基数侧写")
        void 低包含率拒绝() {
            session.answers.add(containment(1000, 120));

            SemanticJoinValidator.JoinValidationResult r = validateOne();
            SemanticJoinValidator.JoinVerdict v = r.getVerdicts().get(0);

            assertEquals(ConnectorSemanticService.V_REJECTED, v.getVerified());
            assertEquals(1, r.getProbeCount(), "被否掉之后不该再打侧写");
            assertNull(v.getCardinality());
        }

        /**
         * ★ 0/0 不是 0%。一张几乎空的表判不出任何东西，而「表太空」与「关系是错的」
         * 是两件事——把前者写成 REJECTED，等于拿一张空表删掉一条可能完全正确的关系。
         */
        @Test
        @DisplayName("★ 左侧只有 3 个非空取值、一个都没命中 → UNDECIDABLE，不是 REJECTED")
        void 样本太少判不出来() {
            session.answers.add(containment(3, 0));

            SemanticJoinValidator.JoinVerdict v = validateOne().getVerdicts().get(0);

            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, v.getVerified());
            assertNotEquals(ConnectorSemanticService.V_REJECTED, v.getVerified());
            assertTrue(v.isProbed(), "查过了，只是判不出来");
            assertNull(v.getContainment(), "样本不足时不该给出一个会被当真的包含率");
            assertTrue(v.getReason().contains("不等于这条关系是错的"), v.getReason());
        }

        @Test
        @DisplayName("恰好 90% → CONFIRMED；恰好 50% → WEAK（边界都取闭区间）")
        void 阈值边界() {
            session.answers.add(containment(100, 90));
            session.answers.add(profile(100, 10));
            session.answers.add(profile(100, 100));
            assertEquals(ConnectorSemanticService.V_CONFIRMED, validateOne().getVerdicts().get(0).getVerified());

            session.answers.clear();
            session.answers.add(containment(100, 50));
            session.answers.add(profile(100, 10));
            session.answers.add(profile(100, 100));
            assertEquals(ConnectorSemanticService.V_WEAK, validateOne().getVerdicts().get(0).getVerified());
        }
    }

    // ================================================================ 失败一律不判错

    @Nested
    @DisplayName("★ 失败绝不翻成 REJECTED")
    class FailuresNeverReject {

        /**
         * ★ 这一条是整个组件最要紧的不变式。对抗审查在本仓库实测过：高基数列上的聚合能跑到
         * 24 秒，而查询默认超时是 15 秒。超时回来是一个通用失败，它和「这条关系是错的」
         * 在返回值上长得一模一样——按 REJECTED 处理就是用一次慢查询静默删掉一条正确的关系。
         */
        @Test
        @DisplayName("超时 → UNDECIDABLE，并说明「不代表这条关系是错的」")
        void 超时判不出来而不是拒绝() {
            session.answers.add(ConnectorException.of(ConnectorErrorCode.TIMEOUT, null));

            SemanticJoinValidator.JoinVerdict v = validateOne().getVerdicts().get(0);

            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, v.getVerified());
            assertNotEquals(ConnectorSemanticService.V_REJECTED, v.getVerified());
            assertTrue(v.getBasis().contains("超时"), v.getBasis());
            assertTrue(v.getBasis().contains("不代表这条关系是错的"), v.getBasis());
            assertFalse(v.isAutoJoinable());
        }

        @Test
        @DisplayName("权限不足 / 表不存在 / 未归类异常，全部 UNDECIDABLE")
        void 各类失败都不判错() {
            for (Object failure : List.of(
                    ConnectorException.of(ConnectorErrorCode.FORBIDDEN, "这个数据库账号没有访问该对象的权限"),
                    ConnectorException.of(ConnectorErrorCode.NOT_FOUND, "指定的表或库不存在"),
                    ConnectorException.of(ConnectorErrorCode.UPSTREAM_ERROR, "数据库返回了错误"),
                    new IllegalStateException("连接池炸了：jdbc:mysql://10.0.0.7:3306/crm?user=root"))) {
                session.answers.clear();
                session.answers.add(failure);
                SemanticJoinValidator.JoinVerdict v = validateOne().getVerdicts().get(0);
                assertEquals(ConnectorSemanticService.V_UNDECIDABLE, v.getVerified(), failure.toString());
            }
        }

        /**
         * ★ 未归类异常的 message 里常有完整 SQL、主机名、连接参数，而这些结论会随
         * detail_json 一路流进模型上下文并落库。原文只准进日志。
         */
        @Test
        @DisplayName("★ 未归类异常的原文一个字都不准出现在结论里")
        void 不泄露底层异常原文() {
            session.answers.add(new IllegalStateException(
                    "HikariPool-9 - Connection is not available, jdbc:mysql://10.0.0.7:3306/crm?user=root&password=s3cret"));

            SemanticJoinValidator.JoinVerdict v = validateOne().getVerdicts().get(0);

            String all = v.getBasis() + "|" + v.getReason() + "|" + v.detailPatch();
            assertFalse(all.contains("jdbc:"), all);
            assertFalse(all.contains("10.0.0.7"), all);
            assertFalse(all.contains("s3cret"), all);
            assertFalse(all.contains("HikariPool"), all);
        }

        /** 限流说明我们自己跑太快了，继续往下打只会更糟。整轮停，剩下的如实标「没验」。 */
        @Test
        @DisplayName("被限流 → 本条按「没查」处理，并中止整轮")
        void 限流中止整轮() {
            session.answers.add(ConnectorException.of(ConnectorErrorCode.RATE_LIMITED, "上限"));

            SemanticJoinValidator.JoinValidationResult r = validator.validate(100L,
                    List.of(candidate(), SemanticJoinValidator.JoinCandidate.of("orders", "out_trade_no", "users", "open_id")),
                    snapshot());

            assertEquals(SemanticJoinValidator.OUT_ABORTED, r.getOutcome());
            assertEquals(2, r.getVerdicts().size(), "中止也要给每条候选一个结论");
            assertEquals(ConnectorSemanticService.V_NONE, r.getVerdicts().get(0).getVerified());
            assertEquals(ConnectorSemanticService.V_NONE, r.getVerdicts().get(1).getVerified());
            assertEquals(1, r.getProbeCount(), "中止之后不该再打第二条");
        }
    }

    // ================================================================ 不能饿死客户的 Agent

    @Nested
    @DisplayName("★ 一次探查 = 一次 executeAsPlatform")
    class OneProbeOneCall {

        /**
         * ★ {@code executeAsPlatform} 全程占着该实例的一个并发许可（默认总共 2 个）。
         * 把整批包进一次调用，那条连接在几百条探查跑完之前对模型就是不可用的——
         * 客户问一句数就撞「并发已达上限」，而他那边什么都没做错。
         */
        @Test
        @DisplayName("两条候选 → 多次独立调用，许可在两次之间归还")
        void 一次探查一次调用() {
            session.answers.add(containment(1000, 970));
            session.answers.add(profile(1000, 50));
            session.answers.add(profile(1000, 1000));
            session.answers.add(containment(1000, 960));
            session.answers.add(profile(1000, 40));
            // users.open_id 也要单独侧写一次：唯一性缓存的键是 (表, 列)，open_id 与 id 不是同一个键。
            session.answers.add(profile(1000, 1000));

            SemanticJoinValidator.JoinValidationResult r = validator.validate(100L,
                    List.of(candidate(),
                            SemanticJoinValidator.JoinCandidate.of("orders", "out_trade_no", "users", "open_id")),
                    snapshot());

            // 2 条包含率 + 3 条侧写（orders.user_id / users.id / orders.out_trade_no）
            // + 1 条 users.open_id 的侧写 = 6，而不是 1。
            assertTrue(r.getProbeCount() >= 4, "实际探查次数=" + r.getProbeCount());
            assertEquals(r.getProbeCount(), auditOps.size(), "探查次数必须等于网关调用次数");
        }

        /**
         * 客户的 DBA 拿着自己的数据库审计来问「这些查询是谁发的」时，这一批必须能和模型的查询、
         * 也和只读元数据的结构刷新择得开。
         */
        @Test
        @DisplayName("审计动作名固定是 platform.semantic_probe")
        void 审计动作名与模型查询分得开() {
            session.answers.add(containment(1000, 120));

            validateOne();

            assertFalse(auditOps.isEmpty());
            for (String op : auditOps) {
                assertEquals(ConnectorAuditService.OP_SEMANTIC_PROBE, op);
                assertTrue(op.startsWith(ConnectorAuditService.PLATFORM_OP_PREFIX));
                assertNotEquals(ConnectorAuditService.OP_SCHEMA_REFRESH, op);
            }
        }

        /**
         * ★ 15 秒是<b>模型写的查询</b>的超时。探查是平台自己发起的、可有可无的剖析，
         * 拖住客户库的连接和我们的并发闸远比放弃一条判定贵。
         */
        @Test
        @DisplayName("★ 探查有自己的短超时，不是查询默认的 15 秒")
        void 探查用自己的短超时() {
            session.answers.add(containment(1000, 120));

            validateOne();

            assertEquals(5, session.lastOptions.timeoutSec());
            assertNotEquals(15, session.lastOptions.timeoutSec());
        }

        /** 重复候选只探一次——重复探查花的是客户的钱。 */
        @Test
        @DisplayName("同一条候选出现两次只探一次，结论共用")
        void 重复候选只探一次() {
            session.answers.add(containment(1000, 120));

            SemanticJoinValidator.JoinValidationResult r =
                    validator.validate(100L, List.of(candidate(), candidate()), snapshot());

            assertEquals(2, r.getVerdicts().size());
            assertSame(r.getVerdicts().get(0), r.getVerdicts().get(1));
            assertEquals(1, r.getProbeCount());
        }

        /** 节流是自己给自己定的速度——跑得比网关那个管理面桶慢得多，免得「太快」变成「判不出来」。 */
        @Test
        @DisplayName("探查之间按 probes-per-minute 节流")
        void 探查之间有节流() {
            validator.probesPerMinute = 600;      // 100ms 一条
            session.answers.add(containment(1000, 120));
            session.answers.add(containment(1000, 120));

            long start = System.currentTimeMillis();
            validator.validate(100L,
                    List.of(candidate(),
                            SemanticJoinValidator.JoinCandidate.of("orders", "out_trade_no", "users", "open_id")),
                    snapshot());
            long elapsed = System.currentTimeMillis() - start;

            // 第一条不等，第二条要等满一个间隔。
            assertTrue(elapsed >= 90, "两条探查之间没有节流，实际耗时 " + elapsed + "ms");
        }
    }

    // ================================================================ SQL

    @Nested
    @DisplayName("★ 探查 SQL 必须过只读护栏，标识符必须验过")
    class ProbeSql {

        private final ReadOnlySqlGuard guard = new ReadOnlySqlGuard();

        /**
         * ★ 探查 SQL 是本类拼出来的，但执行它的是 {@code MySqlSession.query}，
         * 也就是说它要先过 {@code ReadOnlySqlGuard}。护栏拒绝时的表现不是编译失败，
         * 是每一条关系都变成「判不出来」——一次完全静默的功能失效。
         */
        @Test
        @DisplayName("包含率 SQL 与侧写 SQL 都被只读护栏接受")
        void 生成的SQL能过护栏() {
            ReadOnlySqlGuard.Verdict a = guard.check(validator.containmentSql(candidate(), false), 10);
            ReadOnlySqlGuard.Verdict b = guard.check(
                    validator.containmentSql(
                            SemanticJoinValidator.JoinCandidate.of("orders", "out_trade_no", "users", "open_id"), true),
                    10);
            ReadOnlySqlGuard.Verdict c = guard.check(validator.profileSql("users", "id"), 10);

            assertTrue(a.effectiveSql().toUpperCase().startsWith("SELECT"));
            assertTrue(b.effectiveSql().toUpperCase().startsWith("SELECT"));
            assertTrue(c.effectiveSql().toUpperCase().startsWith("SELECT"));
        }

        /**
         * ★ 在数值列上写 {@code x <> ''}，MySQL 会把 {@code ''} 隐式转成 0，
         * 于是把 0 这个完全合法的取值整批剔掉。这个错不会报，只会让包含率莫名其妙。
         */
        @Test
        @DisplayName("★ 空串过滤只加在字符型列上，数值列绝不加")
        void 空串过滤只加在字符列() {
            String numeric = validator.containmentSql(candidate(), false);
            String text = validator.containmentSql(
                    SemanticJoinValidator.JoinCandidate.of("orders", "out_trade_no", "users", "open_id"), true);

            assertFalse(numeric.contains("<> ''"), numeric);
            assertTrue(numeric.contains("IS NOT NULL"), numeric);
            assertTrue(text.contains("<> ''"), text);
        }

        @Test
        @DisplayName("类型判断：varchar/text/json 算字符型，bigint/decimal 不算；取不到类型按非字符型")
        void 字符型判断() {
            assertTrue(SemanticJoinValidator.isTextLike(new FieldDetail("a", "varchar(64)", true, null, null)));
            assertTrue(SemanticJoinValidator.isTextLike(new FieldDetail("a", "TEXT", true, null, null)));
            assertTrue(SemanticJoinValidator.isTextLike(new FieldDetail("a", "json", true, null, null)));
            assertFalse(SemanticJoinValidator.isTextLike(new FieldDetail("a", "bigint(20)", true, null, null)));
            assertFalse(SemanticJoinValidator.isTextLike(new FieldDetail("a", "decimal(10,2)", true, null, null)));
            assertFalse(SemanticJoinValidator.isTextLike(new FieldDetail("a", null, true, null, null)));
            assertFalse(SemanticJoinValidator.isTextLike(null));
        }

        /** 左表采的是<b>去重后的</b>取值：一个取值一票，热点键不该把包含率带偏。 */
        @Test
        @DisplayName("左侧按 DISTINCT 取值采样，且计数全部是 COUNT(DISTINCT ...)")
        void 按取值而不是按行() {
            String sql = validator.containmentSql(candidate(), false);
            assertTrue(sql.contains("SELECT DISTINCT"), sql);
            assertTrue(sql.contains("COUNT(DISTINCT"), sql);
            assertFalse(sql.contains("COUNT(*) AS sample_n"), sql);
        }

        /**
         * ★ 表名列名混着<b>模型的自由输出</b>。一段带反引号的字符串拼进 SQL 就是注入，
         * 所以形状过不了白名单的一律不探查——而不是拼进去让客户的库报错。
         */
        @Test
        @DisplayName("★ 标识符含反引号 / 空格 → 一个字节都不打出去")
        void 非法标识符不探查() {
            SemanticJoinValidator.JoinValidationResult r = validator.validate(100L, List.of(
                    SemanticJoinValidator.JoinCandidate.of("orders`; DROP TABLE x --", "user_id", "users", "id"),
                    SemanticJoinValidator.JoinCandidate.of("orders", "user id", "users", "id")), snapshot());

            assertEquals(2, r.getVerdicts().size());
            for (SemanticJoinValidator.JoinVerdict v : r.getVerdicts()) {
                assertEquals(ConnectorSemanticService.V_NONE, v.getVerified());
                assertFalse(v.isProbed());
            }
            verify(gateway, never()).executeAsPlatform(any(), any(), any(), any());
        }

        /** 模型能编出一张不存在的表。那种名字拼进 SQL，代价是一次真实的连接换一个本地就能得出的结论。 */
        @Test
        @DisplayName("快照里没有的表 / 列 → 不探查，并如实说明原因")
        void 快照对不上就不探查() {
            SemanticJoinValidator.JoinValidationResult r = validator.validate(100L, List.of(
                    SemanticJoinValidator.JoinCandidate.of("order_items", "user_id", "users", "id"),
                    SemanticJoinValidator.JoinCandidate.of("orders", "buyer_id", "users", "id")), snapshot());

            for (SemanticJoinValidator.JoinVerdict v : r.getVerdicts()) {
                assertEquals(ConnectorSemanticService.V_NONE, v.getVerified());
                assertFalse(v.isProbed());
                assertTrue(v.getReason().contains("结构快照里没有"), v.getReason());
            }
            verify(gateway, never()).executeAsPlatform(any(), any(), any(), any());
        }
    }

    // ================================================================ 基数与 fan-out

    @Nested
    @DisplayName("基数与 fan-out 禁令")
    class Cardinality {

        @Test
        @DisplayName("两侧都不唯一 → N:N，只标注不自动 join")
        void N对N不自动join() {
            session.answers.add(containment(1000, 950));
            session.answers.add(profile(1000, 40));      // 左不唯一
            session.answers.add(profile(1000, 30));      // 右也不唯一

            SemanticJoinValidator.JoinVerdict v = validateOne().getVerdicts().get(0);

            assertEquals(ConnectorSemanticService.V_CONFIRMED, v.getVerified(), "关系本身成立");
            assertEquals(SemanticJoinValidator.CARD_MANY_MANY, v.getCardinality());
            assertFalse(v.isAutoJoinable(), "N:N 一律只标注");
            assertTrue(v.getBasis().contains("只标注"), v.getBasis());
        }

        /** 右侧不唯一就会 fan-out，SUM 出来的金额会凭空变大而不报任何错。 */
        @Test
        @DisplayName("左唯一右不唯一（1:N）同样不自动 join")
        void 一对多也不自动join() {
            session.answers.add(containment(1000, 950));
            session.answers.add(profile(1000, 1000));    // 左唯一
            session.answers.add(profile(1000, 30));      // 右不唯一

            SemanticJoinValidator.JoinVerdict v = validateOne().getVerdicts().get(0);

            assertEquals(SemanticJoinValidator.CARD_ONE_MANY, v.getCardinality());
            assertFalse(v.isAutoJoinable());
        }

        /** 0.95 是设计给的主键判定线：混进几条脏数据不该把一张真主键表打成非唯一。 */
        @Test
        @DisplayName("distinct/count 恰好 0.95 算唯一，0.94 不算")
        void 主键判定线() {
            assertEquals(SemanticJoinValidator.CARD_ONE_ONE,
                    SemanticJoinValidator.cardinality(true, true));
            assertEquals(SemanticJoinValidator.CARD_MANY_ONE,
                    SemanticJoinValidator.cardinality(false, true));

            session.answers.add(containment(1000, 1000));
            session.answers.add(profile(1000, 950));     // 恰好 0.95 → 唯一
            session.answers.add(profile(1000, 940));     // 0.94 → 不唯一
            SemanticJoinValidator.JoinVerdict v = validateOne().getVerdicts().get(0);
            assertEquals(SemanticJoinValidator.CARD_ONE_MANY, v.getCardinality());
        }

        /**
         * 任一侧测不出来就整体留空。编一个基数出来比留空危险得多——
         * 下游正是拿它决定要不要自动 join。
         */
        @Test
        @DisplayName("侧写测不出来 → 基数留空，且不自动 join，但不推翻已测准的包含率")
        void 基数判不出时留空() {
            session.answers.add(containment(1000, 970));
            session.answers.add(ConnectorException.of(ConnectorErrorCode.TIMEOUT, null));  // 左侧写超时
            session.answers.add(profile(1000, 1000));

            SemanticJoinValidator.JoinVerdict v = validateOne().getVerdicts().get(0);

            assertEquals(ConnectorSemanticService.V_CONFIRMED, v.getVerified(), "包含率已经测准了，不该被侧写失败推翻");
            assertNull(v.getCardinality());
            assertTrue(v.isAutoJoinable(), "右侧确实唯一，fan-out 风险与基数标签是两件事");
        }

        @Test
        @DisplayName("cardinality 判定表")
        void 基数判定表() {
            assertEquals(SemanticJoinValidator.CARD_ONE_ONE, SemanticJoinValidator.cardinality(true, true));
            assertEquals(SemanticJoinValidator.CARD_MANY_ONE, SemanticJoinValidator.cardinality(false, true));
            assertEquals(SemanticJoinValidator.CARD_ONE_MANY, SemanticJoinValidator.cardinality(true, false));
            assertEquals(SemanticJoinValidator.CARD_MANY_MANY, SemanticJoinValidator.cardinality(false, false));
            assertNull(SemanticJoinValidator.cardinality(null, true));
            assertNull(SemanticJoinValidator.cardinality(true, null));
        }
    }

    // ================================================================ 可续跑 / 可中止

    @Nested
    @DisplayName("可续跑与可中止")
    class Resumable {

        /** 推导会被部署重启打断，而探查花的是客户的钱——已决的一条都不能丢。 */
        @Test
        @DisplayName("回调返回 false → 立刻停，已决的结论保留，剩下的如实标「没轮到」")
        void 回调可中止() {
            session.answers.add(containment(1000, 120));
            session.answers.add(containment(1000, 120));

            List<String> seen = new ArrayList<>();
            SemanticJoinValidator.JoinValidationResult r = validator.validate(100L,
                    List.of(candidate(),
                            SemanticJoinValidator.JoinCandidate.of("orders", "out_trade_no", "users", "open_id")),
                    snapshot(),
                    (v, decided, total) -> {
                        seen.add(v.getFromColumn());
                        return false;   // 第一条之后就喊停
                    });

            assertEquals(SemanticJoinValidator.OUT_ABORTED, r.getOutcome());
            assertEquals(1, seen.size());
            assertEquals(2, r.getVerdicts().size());
            assertEquals(ConnectorSemanticService.V_REJECTED, r.getVerdicts().get(0).getVerified());
            assertEquals(ConnectorSemanticService.V_NONE, r.getVerdicts().get(1).getVerified());
            assertEquals(1, r.getProbeCount());
        }

        /** 回调是一条一条给的，接线方可以逐条落库——这正是「续跑」要的东西。 */
        @Test
        @DisplayName("每决完一条回调一次，带上进度")
        void 逐条回调() {
            session.answers.add(containment(1000, 120));
            session.answers.add(containment(1000, 120));

            List<String> progress = new ArrayList<>();
            validator.validate(100L,
                    List.of(candidate(),
                            SemanticJoinValidator.JoinCandidate.of("orders", "out_trade_no", "users", "open_id")),
                    snapshot(),
                    (v, decided, total) -> {
                        progress.add(decided + "/" + total);
                        return true;
                    });

            assertEquals(List.of("1/2", "2/2"), progress);
        }

        /**
         * 10^6 是设计给的收敛点：join score 过了它就不再变化，再采样只剩客户那一侧的成本。
         * 所以预算用尽不是失败，是「继续查纯属浪费」。
         */
        @Test
        @DisplayName("采样预算用尽 → 中止，剩下的标「没轮到」而不是判错")
        void 预算用尽就停() {
            validator.rowBudget = 500;
            session.answers.add(containment(1000, 120));   // 一条就把 500 的预算吃穿

            SemanticJoinValidator.JoinValidationResult r = validator.validate(100L,
                    List.of(candidate(),
                            SemanticJoinValidator.JoinCandidate.of("orders", "out_trade_no", "users", "open_id")),
                    snapshot());

            assertEquals(SemanticJoinValidator.OUT_ABORTED, r.getOutcome());
            assertEquals(ConnectorSemanticService.V_NONE, r.getVerdicts().get(1).getVerified());
            assertTrue(r.getVerdicts().get(1).getReason().contains("预算"), r.getVerdicts().get(1).getReason());
            assertEquals(1000L, r.getSampledRows());
        }

        @Test
        @DisplayName("候选数超过上限 → 多出来的标「没轮到」，不是被判错")
        void 候选数封顶() {
            validator.maxCandidates = 1;
            session.answers.add(containment(1000, 120));

            SemanticJoinValidator.JoinValidationResult r = validator.validate(100L,
                    List.of(candidate(),
                            SemanticJoinValidator.JoinCandidate.of("orders", "out_trade_no", "users", "open_id")),
                    snapshot());

            assertEquals(ConnectorSemanticService.V_REJECTED, r.getVerdicts().get(0).getVerified());
            assertEquals(ConnectorSemanticService.V_NONE, r.getVerdicts().get(1).getVerified());
            assertTrue(r.getVerdicts().get(1).getReason().contains("没轮到"), r.getVerdicts().get(1).getReason());
        }
    }

    // ================================================================ 前置条件

    @Nested
    @DisplayName("前置条件：租户与开关")
    class Preconditions {

        /**
         * {@code executeAsPlatform} 要的是一个<b>真实</b>的租户上下文。这里先拦一道，
         * 是为了让日志说清楚「后台任务漏了 TenantContext.set」，而不是回一句面向模型的通用文案。
         */
        @Test
        @DisplayName("没有租户上下文 → 不探查，并如实标「没查」")
        void 无租户上下文不探查() {
            TenantContext.clear();

            SemanticJoinValidator.JoinValidationResult r = validateOne();

            assertEquals(SemanticJoinValidator.OUT_ABORTED, r.getOutcome());
            assertEquals(ConnectorSemanticService.V_NONE, r.getVerdicts().get(0).getVerified());
            verify(gateway, never()).executeAsPlatform(any(), any(), any(), any());
        }

        /** 网关也会拒，但档位是在网关之前读的——这一句挡的是「读了别人租户那一格档位」。 */
        @Test
        @DisplayName("连接属于别的租户 → 不探查（与不存在同形）")
        void 别的租户的连接不探查() {
            Connection other = new Connection();
            other.setId(100L);
            other.setTenantId("t2");
            other.setSemanticDataTier(SemanticDataTier.SAMPLE_VALUES.name());
            when(connectionMapper.selectById(100L)).thenReturn(other);

            SemanticJoinValidator.JoinValidationResult r = validateOne();

            assertEquals(SemanticJoinValidator.OUT_ABORTED, r.getOutcome());
            verify(gateway, never()).executeAsPlatform(any(), any(), any(), any());
        }

        @Test
        @DisplayName("开关关闭 → 每条都标「采样验证未启用」，不是空结果")
        void 开关关闭也要给结论() {
            validator.enabled = false;

            SemanticJoinValidator.JoinValidationResult r = validateOne();

            assertEquals(SemanticJoinValidator.OUT_DISABLED, r.getOutcome());
            assertEquals(1, r.getVerdicts().size());
            assertEquals(ConnectorSemanticService.V_NONE, r.getVerdicts().get(0).getVerified());
            verify(gateway, never()).executeAsPlatform(any(), any(), any(), any());
        }

        @Test
        @DisplayName("没有候选 → NOTHING_TO_DO，不去碰库")
        void 没有候选() {
            assertEquals(SemanticJoinValidator.OUT_NOTHING_TO_DO,
                    validator.validate(100L, List.of(), snapshot()).getOutcome());
            assertEquals(SemanticJoinValidator.OUT_NOTHING_TO_DO,
                    validator.validate(100L, null, snapshot()).getOutcome());
            verify(gateway, never()).executeAsPlatform(any(), any(), any(), any());
        }

        /**
         * 夹紧而不是启动失败（语义层是叠加增强），但<b>节流不允许被关掉</b>：
         * 配 0 的人想说「不限」，而「不限」在这里等于允许一轮推导把客户的生产库当压测目标。
         */
        @Test
        @DisplayName("配置夹紧：节流下限是 1，不允许配成 0")
        void 配置夹紧() {
            SemanticJoinValidator v = new SemanticJoinValidator(gateway, connectionMapper, new PiiFilter());
            v.configuredEnabled = true;
            v.configuredProbesPerMinute = 0;
            v.configuredTimeoutSeconds = 999;
            v.configuredSampleValues = 1;
            v.configuredMaxCandidates = 0;
            v.configuredRowBudget = -1;
            v.resolveLimits();

            assertEquals(1, v.probesPerMinute);
            assertEquals(30, v.timeoutSeconds);
            assertEquals(SemanticJoinValidator.MIN_SAMPLE, v.sampleValues);
            assertEquals(1, v.maxCandidates);
            assertEquals(1_000_000L, v.rowBudget);
        }
    }

    // ================================================================ 结论的形状

    @Nested
    @DisplayName("结论摊平进 detail_json 的形状")
    class DetailShape {

        /** 注入层要的那几样：sample_n / match_n / containment / cardinality + 一句人话。 */
        @Test
        @DisplayName("detailPatch 带齐设计要求的证据字段")
        void 证据字段齐全() {
            session.answers.add(containment(1000, 970));
            session.answers.add(profile(1000, 50));
            session.answers.add(profile(1000, 1000));

            Map<String, Object> d = validateOne().getVerdicts().get(0).detailPatch();

            assertEquals("users", d.get("to_object"));
            assertEquals("id", d.get("to_column"));
            assertEquals(SemanticJoinValidator.CARD_MANY_ONE, d.get("cardinality"));
            assertEquals(1000, d.get("sample_n"));
            assertEquals(970, d.get("match_n"));
            assertEquals(0.97d, (Double) d.get("containment"), 1e-9);
            assertEquals(Boolean.TRUE, d.get("auto_joinable"));
            assertTrue(String.valueOf(d.get("basis")).contains("命中 970"));
            // S2 写死的那句「未经数据验证」必须被覆盖掉，否则模型读到的仍是旧话。
            assertNotEquals("未经数据验证", d.get("basis"));
        }

        /** 没探查过的那一条仍然要给出 basis，且必须是「未经数据验证」而不是一个空字符串。 */
        @Test
        @DisplayName("没探查过的一条：basis 回到「未经数据验证」")
        void 没探查过的形状() {
            givenConnection(SemanticDataTier.METADATA_ONLY.name());

            Map<String, Object> d = validateOne().getVerdicts().get(0).detailPatch();

            assertEquals("未经数据验证", d.get("basis"));
            assertEquals(Boolean.FALSE, d.get("auto_joinable"));
            assertFalse(d.containsKey("sample_n"));
            assertTrue(String.valueOf(d.get("verify_note")).contains("未启用派生统计"));
        }
    }

    // ================================================================ 多态外键 / 组合键：夹具

    private static FieldDetail col(String name, String type, String comment) {
        return new FieldDetail(name, type, true, comment, null);
    }

    private static void table(Map<String, Map<String, FieldDetail>> snap, String name, FieldDetail... cols) {
        Map<String, FieldDetail> m = new LinkedHashMap<>();
        for (FieldDetail f : cols) {
            m.put(f.name(), f);
        }
        snap.put(name, m);
    }

    /** 在 {@link #snapshot()} 之上补几张表：多态外键（真判别列 / 列名疑似 PII 的判别列）、组合键（左表有 / 没有 tenant_id）。 */
    private Map<String, Map<String, FieldDetail>> richSnapshot() {
        Map<String, Map<String, FieldDetail>> s = snapshot();
        table(s, "sys_role_resource", col("id", "bigint(20)", "主键"), col("role_id", "bigint(20)", null),
                col("resource_type", "varchar(32)", "MENU | AGENT | KNOWLEDGE_BASE"),
                col("resource_id", "bigint(20)", null));
        table(s, "agent", col("id", "bigint(20)", "主键"));
        table(s, "comments", col("commentable_type", "varchar(64)", null), col("commentable_id", "bigint(20)", null));
        table(s, "posts", col("id", "bigint(20)", null));
        table(s, "tags", col("id", "bigint(20)", null));
        table(s, "contact_log", col("contact_type", "varchar(16)", null), col("contact_id", "bigint(20)", null));
        table(s, "contacts", col("id", "bigint(20)", null));
        table(s, "members", col("id", "bigint(20)", null));
        table(s, "t_ref", col("tenant_id", "varchar(64)", null), col("sku_code", "varchar(32)", null));
        table(s, "t_ref_nt", col("sku_code", "varchar(32)", null));
        table(s, "t_sku", col("id", "bigint(20)", null), col("tenant_id", "varchar(64)", null),
                col("code", "varchar(32)", null));
        return s;
    }

    /** 设计文档里那个多态外键：sys_role_resource.resource_id → agent.id，需 resource_type 条件。 */
    private static SemanticJoinValidator.JoinCandidate poly() {
        return SemanticJoinValidator.JoinCandidate.of("sys_role_resource", "resource_id", "agent", "id");
    }

    /** 分组探查的返回：每三个一组（判别值, 采样取值数, 命中数）。 */
    private static QueryResult groups(boolean truncated, Object... flat) {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i + 2 < flat.length; i += 3) {
            List<Object> r = new ArrayList<>();
            r.add(flat[i]);
            r.add(((Number) flat[i + 1]).longValue());
            r.add(((Number) flat[i + 2]).longValue());
            rows.add(r);
        }
        return new QueryResult(List.of("disc_v", "sample_n", "match_n"), rows, truncated, null, "已执行", 3L);
    }

    private SemanticJoinValidator.JoinVerdict validatePoly() {
        return validator.validate(100L, List.of(poly()), richSnapshot()).getVerdicts().get(0);
    }

    /** t_sku：id 是单列主键，(tenant_id, code) 是组合唯一键。 */
    private static Map<String, List<List<String>>> skuKeys() {
        return Map.of("t_sku", List.of(List.of("id"), List.of("tenant_id", "code")));
    }

    // ================================================================ 结构判定

    @Nested
    @DisplayName("结构判定：只看名字与索引，不碰客户库")
    class StructureDetection {

        private Map<String, FieldDetail> cols(FieldDetail... fs) {
            Map<String, FieldDetail> m = new LinkedHashMap<>();
            for (FieldDetail f : fs) {
                m.put(f.name(), f);
            }
            return m;
        }

        @Test
        @DisplayName("resource_type + resource_id → 多态外键，判别列就是 resource_type")
        void 设计里那个多态外键() {
            SemanticJoinValidator.Structure st = SemanticJoinValidator.structure(poly(), richSnapshot(), null);
            assertEquals(SemanticJoinValidator.KIND_POLYMORPHIC, st.kind());
            assertEquals("resource_type", st.discriminatorColumn());
        }

        @Test
        @DisplayName("其它常见写法：*_kind / 驼峰 / Odoo 的 res_model / Django 的 content_type_id")
        void 常见写法都认得() {
            assertEquals("target_kind", SemanticJoinValidator.discriminatorFor(
                    cols(col("target_kind", "varchar(16)", null), col("target_id", "bigint", null)), "target_id"));
            assertEquals("resourceType", SemanticJoinValidator.discriminatorFor(
                    cols(col("resourceType", "varchar(16)", null), col("resourceId", "bigint", null)), "resourceId"));
            assertEquals("res_model", SemanticJoinValidator.discriminatorFor(
                    cols(col("res_model", "varchar(64)", null), col("res_id", "int", null)), "res_id"));
            assertEquals("content_type_id", SemanticJoinValidator.discriminatorFor(
                    cols(col("content_type_id", "int", null), col("object_id", "varchar(255)", null)), "object_id"));
        }

        @Test
        @DisplayName("不乱认：没有配对列 / 没有前缀 / 列名不是 id 形状 / 判别列是时间类型")
        void 不乱认() {
            Map<String, FieldDetail> orders = cols(col("user_id", "bigint", null), col("type", "tinyint", null),
                    col("id", "bigint", null), col("paid", "tinyint", null), col("paid_type", "varchar(8)", null),
                    col("ship_id", "bigint", null), col("ship_type", "datetime", null));
            assertNull(SemanticJoinValidator.discriminatorFor(orders, "user_id"), "没有 user_type 列");
            assertNull(SemanticJoinValidator.discriminatorFor(orders, "id"), "type + id 没有前缀，全库每张表都会被误认");
            assertNull(SemanticJoinValidator.discriminatorFor(orders, "paid"), "paid 不是 xx_id 形状");
            assertNull(SemanticJoinValidator.discriminatorFor(orders, "ship_id"), "时间列不可能是判别列");
        }

        @Test
        @DisplayName("组合键：目标列只在多列唯一键里 → 完整键；它自己单列唯一 → 不是组合键；不知道唯一键 → 不猜")
        void 组合键判定() {
            assertEquals(List.of("tenant_id", "code"), SemanticJoinValidator.compositeKeyFor(
                    List.of(List.of("id"), List.of("tenant_id", "code")), "code"));
            assertEquals(List.of("tenant_id", "code"), SemanticJoinValidator.compositeKeyFor(
                    List.of(List.of("tenant_id", "code")), "CODE"), "列名不分大小写");
            assertNull(SemanticJoinValidator.compositeKeyFor(
                    List.of(List.of("tenant_id", "id"), List.of("id")), "id"), "单列就唯一，是普通关系");
            assertNull(SemanticJoinValidator.compositeKeyFor(List.of(List.of("tenant_id", "code")), "name"));
            assertNull(SemanticJoinValidator.compositeKeyFor(null, "code"), "不知道唯一键时不能凭空说是组合键");
            assertEquals(List.of("tenant_id", "code"), SemanticJoinValidator.compositeKeyFor(
                    List.of(List.of("tenant_id", "code", "version"), List.of("tenant_id", "code")), "code"),
                    "属于多个组合键时取列数最少的");
        }

        @Test
        @DisplayName("从快照行解唯一键：旧快照（没有 unique_keys）不出现在结果里，与「没有唯一键」分开")
        void 从快照解唯一键() throws Exception {
            Map<String, Object> withKeys = new LinkedHashMap<>();
            withKeys.put(SemanticJoinValidator.DETAIL_UNIQUE_KEYS, List.of(
                    Map.of("name", "PRIMARY", "primary", true, "columns", List.of("id")),
                    Map.of("name", "uk_tenant_code", "primary", false, "columns", List.of("tenant_id", "code"))));
            Map<String, Object> noKeys = new LinkedHashMap<>();
            noKeys.put(SemanticJoinValidator.DETAIL_UNIQUE_KEYS, List.of());
            ConnectorSchema broken = new ConnectorSchema();
            broken.setObjectName("t_broken");
            broken.setDetailJson("{not json");

            Map<String, List<List<String>>> keys = SemanticJoinValidator.uniqueKeysByObject(List.of(
                    schemaRow(new ObjectDetail("t_sku", "TABLE", null, List.of(), withKeys)),
                    schemaRow(new ObjectDetail("t_log", "TABLE", null, List.of(), noKeys)),
                    schemaRow(new ObjectDetail("t_old", "TABLE", null, List.of(), Map.of("database", "shop"))),
                    broken));

            assertEquals(List.of(List.of("id"), List.of("tenant_id", "code")), keys.get("t_sku"));
            assertEquals(List.of(), keys.get("t_log"));
            assertFalse(keys.containsKey("t_old"), "旧快照没有这个键：不知道，不是没有");
            assertFalse(keys.containsKey("t_broken"));
        }

        /** 形状走 ConnectorSchemaService.toDetailMap —— 与线上写进 connector_schema.detail_json 的是同一条路。 */
        private ConnectorSchema schemaRow(ObjectDetail d) throws Exception {
            ConnectorSchema r = new ConnectorSchema();
            r.setObjectName(d.name());
            r.setDetailJson(CommonUtil.getObjectMapper().writeValueAsString(ConnectorSchemaService.toDetailMap(d)));
            return r;
        }
    }

    // ================================================================ 多态外键

    @Nested
    @DisplayName("★ 多态外键：档位决定能不能看判别值")
    class PolymorphicKeys {

        /** 第 1 档不许任何派生统计出库，但「列名配对」只用列名——结构标记照样给，一个字节不打出去。 */
        @Test
        @DisplayName("第 1 档：不探查，结论里带着 POLYMORPHIC 与判别列名，没有判别值")
        void 第一档只做结构标记() {
            givenConnection(SemanticDataTier.METADATA_ONLY.name());

            SemanticJoinValidator.JoinVerdict v = validatePoly();

            verify(gateway, never()).executeAsPlatform(any(), any(), any(), any());
            assertEquals(ConnectorSemanticService.V_NONE, v.getVerified());
            assertEquals(SemanticJoinValidator.KIND_POLYMORPHIC, v.getJoinKind());
            assertEquals("resource_type", v.getDiscriminatorColumn());
            assertNull(v.getDiscriminatorValue());
            Map<String, Object> sp = v.structuralPatch();
            assertEquals("POLYMORPHIC", sp.get("join_kind"));
            assertTrue(String.valueOf(sp.get("care_reason")).contains("resource_type"), String.valueOf(sp));
            assertFalse(sp.containsKey("basis"), "structuralPatch 不能碰 basis：覆盖它会抹掉 S1 留下的来源线索");
            assertFalse(sp.containsKey("discriminator_value"));
        }

        @Test
        @DisplayName("连这条连接是谁的都没确认（没有租户上下文）时，结构判定也不给")
        void 没确认归属不给结构判定() {
            TenantContext.clear();
            assertNull(validatePoly().getJoinKind());
        }

        /**
         * ★ 第 2 档：不分条件的包含率判不了多态外键——几张目标表的自增 id 重叠时它能高到 97%，
         * 按 CONFIRMED 放进 joins 就是一条静默出错数的 join。
         */
        @Test
        @DisplayName("★ 第 2 档：包含率 97% 也只判 UNDECIDABLE，不发分组探查、不读判别值")
        void 第二档高包含率也判不了() {
            session.answers.add(containment(1000, 970));

            SemanticJoinValidator.JoinValidationResult r = validator.validate(100L, List.of(poly()), richSnapshot());
            SemanticJoinValidator.JoinVerdict v = r.getVerdicts().get(0);

            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, v.getVerified());
            assertTrue(v.isProbed());
            assertFalse(v.isAutoJoinable());
            assertNull(v.getContainment(), "不分条件的包含率不是这条关系的包含率，不给一个会被当真的数");
            assertNull(v.getDiscriminatorValue());
            assertEquals(1, r.getProbeCount(), "判不了就不再为它打唯一性侧写");
            assertEquals(List.of(ConnectorAuditService.OP_SEMANTIC_PROBE), auditOps);
            assertFalse(session.sqls.get(0).toUpperCase().contains("GROUP BY"),
                    "第 2 档连分组探查都不许发：结果行里带着真实判别值");
            assertTrue(v.getReason().contains("第 3 档"), v.getReason());
        }

        /** 被别的类型的行拉低到 12% 不等于这条关系是错的——REJECTED 只能来自能判定它的那种测量。 */
        @Test
        @DisplayName("★ 第 2 档：包含率 12% 也不判 REJECTED")
        void 第二档低包含率不拒绝() {
            session.answers.add(containment(1000, 120));

            SemanticJoinValidator.JoinVerdict v = validatePoly();

            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, v.getVerified());
            assertNotEquals(ConnectorSemanticService.V_REJECTED, v.getVerified());
        }

        @Test
        @DisplayName("第 3 档：按判别值分组，唯一达到确认线的取值记成 discriminator_value，审计记为读取值")
        void 第三档找出判别值() {
            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
            session.answers.add(groups(false, "AGENT", 100, 97, "MENU", 50, 2));

            SemanticJoinValidator.JoinValidationResult r = validator.validate(100L, List.of(poly()), richSnapshot());
            SemanticJoinValidator.JoinVerdict v = r.getVerdicts().get(0);

            assertEquals(ConnectorSemanticService.V_CONFIRMED, v.getVerified());
            assertEquals(SemanticJoinValidator.KIND_POLYMORPHIC, v.getJoinKind());
            assertEquals("AGENT", v.getDiscriminatorValue());
            assertEquals(0.97d, v.getContainment(), 1e-9);
            assertFalse(v.isAutoJoinable(), "多态外键只标注，不自动 join");
            assertTrue(v.getCareReason().contains("sys_role_resource.resource_type = 'AGENT'"), v.getCareReason());
            assertEquals("AGENT", v.detailPatch().get("discriminator_value"));
            assertEquals(1, r.getProbeCount(), "一次分组探查 = 一次 executeAsPlatform");
            assertEquals(List.of(ConnectorAuditService.OP_SEMANTIC_VALUES), auditOps,
                    "结果里带着真实判别值，客户 DBA 按动作名过滤时必须看得出来");
            assertTrue(session.sqls.get(0).contains("GROUP BY"), session.sqls.get(0));
            assertEquals(SemanticJoinValidator.POLY_MAX_GROUPS, session.lastOptions.maxRows());
            assertEquals(150L, r.getSampledRows());
        }

        @Test
        @DisplayName("★ 分组探查 SQL 过只读护栏；外键列去 NULL，判别列一个值都不滤（NULL / 空串各自成组）")
        void 分组SQL能过护栏() {
            String sql = validator.polymorphicSql(poly(), "resource_type", false);
            ReadOnlySqlGuard.Verdict g = new ReadOnlySqlGuard().check(sql, SemanticJoinValidator.POLY_MAX_GROUPS);

            assertTrue(g.effectiveSql().toUpperCase().startsWith("SELECT"), g.effectiveSql());
            assertTrue(sql.contains("`resource_id` IS NOT NULL"), sql);
            assertFalse(sql.contains("`resource_type` IS NOT NULL"), "滤掉 NULL 类型的行，按默认类型写入的历史行就整批看不见：" + sql);
            assertFalse(sql.contains("`resource_type` <> ''"), sql);
            assertFalse(sql.contains("`resource_id` <> ''"), "数值外键列上加空串过滤会把 0 整批剔掉");
            assertTrue(validator.polymorphicSql(poly(), "resource_type", true).contains("`resource_id` <> ''"),
                    "字符型外键列照旧去空串");
        }

        /** ★ 判别值落库、进模型上下文之前必须过取值形状那道 PII 闸，而且拦下时哪儿都不能留下原值。 */
        @Test
        @DisplayName("★ 第 3 档：带回的取值命中 PII 判据 → 不记判别值，原值不出现在结论的任何地方")
        void 第三档判别值过PII() {
            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
            session.answers.add(groups(false, "ops@example.com", 100, 97, "MENU", 50, 2));

            SemanticJoinValidator.JoinVerdict v = validatePoly();

            assertEquals(ConnectorSemanticService.V_CONFIRMED, v.getVerified(), "测量本身成立");
            assertNull(v.getDiscriminatorValue());
            String all = v.getBasis() + "|" + v.getReason() + "|" + v.getCareReason() + "|" + v.detailPatch();
            assertFalse(all.contains("ops@example.com"), all);
            assertFalse(all.contains("MENU"), all);
            assertTrue(v.getCareReason().contains("个人信息"), v.getCareReason());
        }

        /** 列名那一道在查询之前：一列疑似个人信息的判别列，它的取值压根不该被查出来。 */
        @Test
        @DisplayName("★ 第 3 档：判别列名本身疑似 PII → 不发分组探查，退回第 2 档的做法")
        void 第三档判别列名过PII() {
            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
            session.answers.add(containment(1000, 970));

            // 目标刻意不是 contacts：contact_id → contacts 前缀念得出目标表，按本轮的结构规则是「分类列 + 普通外键」，
            // 根本不会走到判别列那一步。指向 members 时 contact_type 才可能决定目标表。
            SemanticJoinValidator.JoinVerdict v = validator.validate(100L,
                    List.of(SemanticJoinValidator.JoinCandidate.of("contact_log", "contact_id", "members", "id")),
                    richSnapshot()).getVerdicts().get(0);

            assertEquals(SemanticJoinValidator.KIND_POLYMORPHIC, v.getJoinKind());
            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, v.getVerified());
            assertNull(v.getDiscriminatorValue());
            assertFalse(session.sqls.get(0).contains("GROUP BY"), session.sqls.get(0));
            assertEquals(List.of(ConnectorAuditService.OP_SEMANTIC_PROBE), auditOps);
        }

        /** 自增 id 重叠时两个类型都「命中」：数据分不出来就说分不出来，不挑一个，也不把取值写进去。 */
        @Test
        @DisplayName("第 3 档：两个判别值都达到确认线、名字也对不上目标表 → UNDECIDABLE，不记取值")
        void 第三档分不出来() {
            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
            session.answers.add(groups(false, "Post", 100, 100, "Video", 80, 80));

            SemanticJoinValidator.JoinVerdict v = validator.validate(100L,
                    List.of(SemanticJoinValidator.JoinCandidate.of("comments", "commentable_id", "tags", "id")),
                    richSnapshot()).getVerdicts().get(0);

            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, v.getVerified());
            assertNull(v.getDiscriminatorValue());
            assertFalse(v.detailPatch().toString().contains("Video"), v.detailPatch().toString());
        }

        @Test
        @DisplayName("第 3 档：两个判别值都达到确认线，恰好一个的名字对得上目标表 → 选它")
        void 第三档按表名打破平局() {
            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
            session.answers.add(groups(false, "App\\Models\\Post", 100, 100, "App\\Models\\Video", 80, 80));

            SemanticJoinValidator.JoinVerdict v = validator.validate(100L,
                    List.of(SemanticJoinValidator.JoinCandidate.of("comments", "commentable_id", "posts", "id")),
                    richSnapshot()).getVerdicts().get(0);

            assertEquals(ConnectorSemanticService.V_CONFIRMED, v.getVerified());
            assertEquals("App\\Models\\Post", v.getDiscriminatorValue());
        }

        @Test
        @DisplayName("★ 第 3 档：分组探查超时 → UNDECIDABLE，绝不 REJECTED")
        void 第三档超时不拒绝() {
            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
            session.answers.add(ConnectorException.of(ConnectorErrorCode.TIMEOUT, null));

            SemanticJoinValidator.JoinVerdict v = validatePoly();

            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, v.getVerified());
            assertTrue(v.isProbed());
            assertNull(v.getDiscriminatorValue());
            assertEquals(SemanticJoinValidator.KIND_POLYMORPHIC, v.getJoinKind(), "探查失败不影响结构判定");
        }

        /**
         * REJECTED 只在「左表每一种判别值都采全了、没有一个对得上」时才下：样本按物理顺序取，
         * 没触到上限才说明没有哪一类行被漏采。
         */
        @Test
        @DisplayName("第 3 档：全部判别值都采全了且都对不上 → REJECTED；结果被截断 → UNDECIDABLE")
        void 第三档拒绝要采全() {
            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
            session.answers.add(groups(false, "AGENT", 40, 3, "MENU", 30, 1));
            assertEquals(ConnectorSemanticService.V_REJECTED, validatePoly().getVerified());

            session.answers.add(groups(true, "AGENT", 40, 3, "MENU", 30, 1));
            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, validatePoly().getVerified());
        }

        @Test
        @DisplayName("分组判定表：样本不足 / 只达弱支持 / 没采全")
        void 分组判定表() {
            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, SemanticJoinValidator.decidePolymorphic(
                    List.of(new SemanticJoinValidator.Group("AGENT", 5, 5)), true, true, "agent").verified(),
                    "5 个取值判不出任何东西，哪怕全中");
            SemanticJoinValidator.PolyDecision weak = SemanticJoinValidator.decidePolymorphic(List.of(
                    new SemanticJoinValidator.Group("AGENT", 100, 70),
                    new SemanticJoinValidator.Group("MENU", 50, 5)), true, true, "agent");
            assertEquals(ConnectorSemanticService.V_WEAK, weak.verified());
            assertFalse(weak.recordValue(), "弱支持不记判别值");
            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, SemanticJoinValidator.decidePolymorphic(
                    List.of(new SemanticJoinValidator.Group("AGENT", 100, 3)), true, false, "agent").verified(),
                    "没采全时，没对上的可能只是没采到");
        }

        @Test
        @DisplayName("判别值与表名的对应：只用来打破平局，短码不猜")
        void 取值与表名的对应() {
            assertTrue(SemanticJoinValidator.valueNamesTable("KNOWLEDGE_BASE", "knowledge_base"));
            assertTrue(SemanticJoinValidator.valueNamesTable("App\\Models\\Post", "posts"));
            assertTrue(SemanticJoinValidator.valueNamesTable("Category", "categories"));
            assertTrue(SemanticJoinValidator.valueNamesTable("AGENT", "ai_agent"));
            assertFalse(SemanticJoinValidator.valueNamesTable("A", "a"), "单字母短码对不上任何东西");
            assertFalse(SemanticJoinValidator.valueNamesTable("MEMBER", "users"));
        }
    }

    // ================================================================ 组合键

    @Nested
    @DisplayName("★ 组合键：按索引判，不按前 1000 行的样本判")
    class CompositeKeys {

        private SemanticJoinValidator.JoinCandidate skuRef() {
            return SemanticJoinValidator.JoinCandidate.of("t_ref", "sku_code", "t_sku", "code");
        }

        /**
         * ★ 前 1000 行的唯一性侧写在这里只可能说错：InnoDB 按主键物理排序，前 1000 行多半全属于同一个租户，
         * code 在那一段里恰好唯一，于是一条跨租户会一连多行的 join 被判成 N:1、可自动 join。
         * 所以索引说它只是组合键的一部分，就不再为它打侧写。
         */
        @Test
        @DisplayName("★ 目标列只是 (tenant_id, code) 的一部分 → COMPOSITE，不自动 join，不打唯一性侧写")
        void 组合键不信样本() {
            session.answers.add(containment(1000, 970));

            SemanticJoinValidator.JoinValidationResult r =
                    validator.validate(100L, List.of(skuRef()), richSnapshot(), skuKeys(), null);
            SemanticJoinValidator.JoinVerdict v = r.getVerdicts().get(0);

            assertEquals(ConnectorSemanticService.V_CONFIRMED, v.getVerified(), "单列包含率仍是必要条件，照测");
            assertEquals(SemanticJoinValidator.KIND_COMPOSITE, v.getJoinKind());
            assertEquals(List.of("tenant_id", "code"), v.getCompositeColumns());
            assertFalse(v.isAutoJoinable());
            assertNull(v.getCardinality(), "右侧没测唯一性，不编一个基数");
            assertEquals(1, r.getProbeCount());
            // ★ 只写已知的那一对；左表碰巧有同名的 tenant_id 也不替它写成条件（反例见 CompositeCareText 的分区表主键）。
            assertTrue(v.getCareReason().contains("t_ref.sku_code = t_sku.code"), v.getCareReason());
            assertTrue(v.getCareReason().contains("(tenant_id, code)"), v.getCareReason());
            assertFalse(v.getCareReason().contains("t_ref.tenant_id = t_sku.tenant_id"), v.getCareReason());
            Map<String, Object> d = v.detailPatch();
            assertEquals("COMPOSITE", d.get("join_kind"));
            assertEquals(List.of("tenant_id", "code"), d.get("composite_columns"));
            assertFalse(d.containsKey("discriminator_column"));
        }

        @Test
        @DisplayName("其余键列在左表上对应哪一列：如实说「要先确认」，不编一个条件")
        void 左表缺对应列() {
            session.answers.add(containment(1000, 970));

            SemanticJoinValidator.JoinVerdict v = validator.validate(100L,
                    List.of(SemanticJoinValidator.JoinCandidate.of("t_ref_nt", "sku_code", "t_sku", "code")),
                    richSnapshot(), skuKeys(), null).getVerdicts().get(0);

            assertTrue(v.getCareReason().contains("tenant_id 在 t_ref_nt 上对应哪一列"), v.getCareReason());
            assertFalse(v.getCareReason().contains("t_ref_nt.tenant_id"), v.getCareReason());
        }

        @Test
        @DisplayName("组合键上单列包含率只有 12% → 仍然 REJECTED（必要条件都不满足）")
        void 组合键也能被否() {
            session.answers.add(containment(1000, 120));
            assertEquals(ConnectorSemanticService.V_REJECTED, validator.validate(100L, List.of(skuRef()),
                    richSnapshot(), skuKeys(), null).getVerdicts().get(0).getVerified());
        }

        /** 旧入口不带唯一键：认不出组合键，行为与引入这项判定之前完全一样。 */
        @Test
        @DisplayName("不传唯一键（旧入口）→ SIMPLE，照旧测唯一性与基数")
        void 旧入口行为不变() {
            session.answers.add(containment(1000, 970));
            session.answers.add(profile(1000, 50));
            session.answers.add(profile(1000, 1000));

            SemanticJoinValidator.JoinValidationResult r =
                    validator.validate(100L, List.of(skuRef()), richSnapshot(), null);
            SemanticJoinValidator.JoinVerdict v = r.getVerdicts().get(0);

            assertEquals(SemanticJoinValidator.KIND_SIMPLE, v.getJoinKind());
            assertEquals(SemanticJoinValidator.CARD_MANY_ONE, v.getCardinality());
            assertEquals(3, r.getProbeCount());
        }

        @Test
        @DisplayName("第 1 档：组合键标记照样给，一个字节不打出去")
        void 第一档也标组合键() {
            givenConnection(SemanticDataTier.METADATA_ONLY.name());

            SemanticJoinValidator.JoinVerdict v = validator.validate(100L, List.of(skuRef()),
                    richSnapshot(), skuKeys(), null).getVerdicts().get(0);

            verify(gateway, never()).executeAsPlatform(any(), any(), any(), any());
            assertEquals(SemanticJoinValidator.KIND_COMPOSITE, v.getJoinKind());
            assertEquals(List.of("tenant_id", "code"), v.structuralPatch().get("composite_columns"));
        }
    }

    // ================================================================ join_kind 的形状

    @Nested
    @DisplayName("join_kind 的形状")
    class JoinKindShape {

        @Test
        @DisplayName("普通关系：detail 里写明 SIMPLE，不带任何「需要当心」的键")
        void 普通关系的形状() {
            session.answers.add(containment(1000, 970));
            session.answers.add(profile(1000, 50));
            session.answers.add(profile(1000, 1000));

            Map<String, Object> d = validateOne().getVerdicts().get(0).detailPatch();

            assertEquals("SIMPLE", d.get("join_kind"));
            assertFalse(d.containsKey("care_reason"));
            assertFalse(d.containsKey("discriminator_column"));
            assertFalse(d.containsKey("composite_columns"));
        }

        /** 不是本类造出来的结论（接线方 / 测试手搭的）没有 join_kind：两个 patch 都不凭空加这个键，注入层按 SIMPLE 读。 */
        @Test
        @DisplayName("手搭的结论没有 join_kind：structuralPatch 为空，detailPatch 不凭空加键")
        void 手搭结论不加键() {
            SemanticJoinValidator.JoinVerdict v = SemanticJoinValidator.JoinVerdict.builder()
                    .toObject("users").toColumn("id").verified(ConnectorSemanticService.V_CONFIRMED)
                    .basis("x").build();
            assertTrue(v.structuralPatch().isEmpty());
            assertFalse(v.detailPatch().containsKey("join_kind"));
        }
    }

    // ================================================================ 本轮修复：夹具

    private static FieldDetail notNull(String name, String type) {
        return new FieldDetail(name, type, false, null, null);
    }

    /** 一次分组探查的返回里放 n 组：可以单独指定第一组，其余组 (G1, G2 ...) 用同一对计数。 */
    private static QueryResult manyGroups(int n, Object[] first, long otherSample, long otherMatch) {
        List<Object> flat = new ArrayList<>();
        if (first != null) {
            flat.addAll(List.of(first));
        }
        for (int i = flat.size() / 3; i < n; i++) {
            flat.add("G" + i);
            flat.add(otherSample);
            flat.add(otherMatch);
        }
        return groups(false, flat.toArray());
    }

    // ================================================================ 分组被截断

    @Nested
    @DisplayName("★ 分组被 LIMIT 截断时不能下 REJECTED")
    class TruncatedGroups {

        @BeforeEach
        void tier3() {
            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
        }

        /**
         * ★ SQL 自己带着 LIMIT 20，第 21 组根本回不到 Java，结果集上也没有截断标记（本地 MySQL 实测外层 LIMIT 1 时第二组直接消失）。
         * 指向目标表的那一类排在 20 组之后（命中数少，或行在表的物理末尾），看得到的 20 组全都对不上——
         * 旧逻辑据此判 REJECTED，一条正确的关系就被静默删掉了。
         */
        @Test
        @DisplayName("★ 带回满 20 组、都对不上 → UNDECIDABLE，不是 REJECTED")
        void 满组不拒绝() {
            session.answers.add(manyGroups(SemanticJoinValidator.POLY_MAX_GROUPS, null, 40, 3));

            SemanticJoinValidator.JoinValidationResult r = validator.validate(100L, List.of(poly()), richSnapshot());
            SemanticJoinValidator.JoinVerdict v = r.getVerdicts().get(0);

            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, v.getVerified());
            assertNotEquals(ConnectorSemanticService.V_REJECTED, v.getVerified());
            assertTrue(v.getReason().contains("前 " + SemanticJoinValidator.POLY_MAX_GROUPS + " 组"), v.getReason());
            assertNull(v.getDiscriminatorValue());
            assertEquals(1000L, r.getSampledRows(), "看不到的组也花了预算：按采满记账");
        }

        @Test
        @DisplayName("19 组、都对不上、内层样本没触到 LIMIT → 仍然 REJECTED（带全了才能否定）")
        void 带全了才拒绝() {
            session.answers.add(manyGroups(SemanticJoinValidator.POLY_MAX_GROUPS - 1, null, 40, 3));

            assertEquals(ConnectorSemanticService.V_REJECTED, validatePoly().getVerified());
        }

        /** Σsample_n 等于内层 LIMIT：派生表采满了，没对上的那一类可能只是没被采到。 */
        @Test
        @DisplayName("★ 组没满、但 Σsample_n 触到内层 LIMIT → UNDECIDABLE；差一行 → REJECTED")
        void 内层LIMIT触顶不拒绝() {
            session.answers.add(groups(false, "AGENT", 600, 3, "MENU", 400, 1));
            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, validatePoly().getVerified());

            session.answers.add(groups(false, "AGENT", 599, 3, "MENU", 400, 1));
            assertEquals(ConnectorSemanticService.V_REJECTED, validatePoly().getVerified());
        }

        /**
         * 看不到的组命中数不超过看得到的最小值（SQL 按 match_n 降序后才 LIMIT）。最小值已经够到达标下界时，
         * 看不到的组里可能还有一个达标的——「恰好一个达标」这句话就不成立了，不能记判别值。
         */
        @Test
        @DisplayName("★ 满 20 组、恰好一个达标、但看得到的最小命中数够到达标下界 → UNDECIDABLE，不记判别值")
        void 满组时唯一达标也不确认() {
            session.answers.add(manyGroups(SemanticJoinValidator.POLY_MAX_GROUPS,
                    new Object[]{"AGENT", 100, 97}, 40, SemanticJoinValidator.MIN_CONFIRM_MATCH));

            SemanticJoinValidator.JoinVerdict v = validatePoly();

            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, v.getVerified());
            assertNull(v.getDiscriminatorValue());
            assertFalse(v.detailPatch().toString().contains("AGENT"), v.detailPatch().toString());
        }

        @Test
        @DisplayName("满 20 组、但看得到的最小命中数低于达标下界 → 看不到的组不可能达标，唯一达标的照样 CONFIRMED")
        void 满组但看不到的不可能达标() {
            session.answers.add(manyGroups(SemanticJoinValidator.POLY_MAX_GROUPS,
                    new Object[]{"AGENT", 100, 97}, 40, SemanticJoinValidator.MIN_CONFIRM_MATCH - 1));

            SemanticJoinValidator.JoinVerdict v = validatePoly();

            assertEquals(ConnectorSemanticService.V_CONFIRMED, v.getVerified());
            assertEquals("AGENT", v.getDiscriminatorValue());
        }

        @Test
        @DisplayName("纯函数自己也守：调用方说带全了、但组数到了上限 → 不 REJECTED")
        void 纯函数自守() {
            List<SemanticJoinValidator.Group> twenty = new ArrayList<>();
            for (int i = 0; i < SemanticJoinValidator.POLY_MAX_GROUPS; i++) {
                twenty.add(new SemanticJoinValidator.Group("G" + i, 40, 3));
            }
            SemanticJoinValidator.PolyDecision d = SemanticJoinValidator.decidePolymorphic(twenty, true, true, "agent");

            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, d.verified());
            assertTrue(d.groupsCapped());
        }

        @Test
        @DisplayName("达标下界：10 个样本命中 9 个恰好达标；看不到的组可能达标 ⇔ 看得到的最小命中数 ≥ 它")
        void 达标下界() {
            assertEquals(9, SemanticJoinValidator.MIN_CONFIRM_MATCH);
            assertTrue(new SemanticJoinValidator.Group("x", SemanticJoinValidator.MIN_SAMPLE, 9).containment()
                    >= SemanticJoinValidator.TH_CONFIRMED);
            assertFalse(new SemanticJoinValidator.Group("x", SemanticJoinValidator.MIN_SAMPLE, 8).containment()
                    >= SemanticJoinValidator.TH_CONFIRMED);
            assertFalse(SemanticJoinValidator.hiddenGroupMayConfirm(List.of(
                    new SemanticJoinValidator.Group("a", 100, 90), new SemanticJoinValidator.Group("b", 40, 8))));
            assertTrue(SemanticJoinValidator.hiddenGroupMayConfirm(List.of(
                    new SemanticJoinValidator.Group("a", 100, 90), new SemanticJoinValidator.Group("b", 40, 9))));
        }

        /** 上面那条下界推理依赖 SQL 先按 match_n 降序再 LIMIT：把排序键和 LIMIT 的位置钉住。 */
        @Test
        @DisplayName("分组 SQL 先按 match_n 降序、最后才 LIMIT 20")
        void 分组SQL的排序键() {
            String sql = validator.polymorphicSql(poly(), "resource_type", false);

            assertTrue(sql.contains("ORDER BY match_n DESC"), sql);
            assertTrue(sql.endsWith("LIMIT " + SemanticJoinValidator.POLY_MAX_GROUPS), sql);
        }
    }

    // ================================================================ 运维急停

    @Nested
    @DisplayName("★ 运维急停：value-profile-enabled=false 时第 3 档也不读取值")
    class ValuesKillSwitch {

        @Test
        @DisplayName("★ 第 3 档 + 急停关闭 → 不发分组探查、不读判别值，按第 2 档判不出来")
        void 急停关闭不发分组探查() {
            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
            validator.valueProfileEnabled = false;
            session.answers.add(containment(1000, 970));

            SemanticJoinValidator.JoinValidationResult r = validator.validate(100L, List.of(poly()), richSnapshot());
            SemanticJoinValidator.JoinVerdict v = r.getVerdicts().get(0);

            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, v.getVerified());
            assertEquals(SemanticJoinValidator.KIND_POLYMORPHIC, v.getJoinKind());
            assertNull(v.getDiscriminatorValue());
            assertEquals(List.of(ConnectorAuditService.OP_SEMANTIC_PROBE), auditOps, "一条 semantic_values 都不许有");
            assertFalse(session.sqls.get(0).contains("GROUP BY"), session.sqls.get(0));
            assertFalse(v.isGroupedProbeRan(), "急停时分组探查没发，不能记成读过取值");
        }

        /**
         * ★ care_reason 与 verify_note 原样进模型上下文。内部配置键名对模型没有用：它只会把实现细节复述给客户，
         * 或者照着它去建议「打开开关」。说清「平台暂停读取真实取值」就够了，配置键只进日志。
         */
        @Test
        @DisplayName("★ 急停时给模型的每一句话都不带内部配置键名，只说平台暂停读取真实取值")
        void 急停文案不带配置键() {
            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
            validator.valueProfileEnabled = false;
            session.answers.add(containment(1000, 970));

            SemanticJoinValidator.JoinVerdict v = validatePoly();

            String all = v.getReason() + "|" + v.getCareReason() + "|" + v.getBasis() + "|" + v.detailPatch();
            assertFalse(all.contains("value-profile-enabled"), all);
            assertFalse(all.contains("connector.semantic"), all);
            assertTrue(v.getCareReason().contains("暂停读取真实取值"), v.getCareReason());
            assertTrue(v.getReason().contains("暂停读取真实取值"), v.getReason());
            assertFalse(SemanticJoinValidator.VALUES_SWITCHED_OFF.contains("connector."), SemanticJoinValidator.VALUES_SWITCHED_OFF);
        }

        @Test
        @DisplayName("配置接线：默认开着；resolveLimits 把配置原值带到生效值上")
        void 配置接线() {
            SemanticJoinValidator v = new SemanticJoinValidator(gateway, connectionMapper, new PiiFilter());
            assertTrue(v.valueProfileEnabled, "单测里没有容器，字段初始值必须与 @Value 的默认值一致");
            assertTrue(v.configuredValueProfileEnabled);

            v.configuredValueProfileEnabled = false;
            v.resolveLimits();

            assertFalse(v.valueProfileEnabled);
        }

        /** 急停之所以叫急停，是一个键关掉平台所有读真实取值的路径。两边键名写岔了，关掉的就只是其中一条，而且不报错。 */
        @Test
        @DisplayName("★ 与 SemanticValueProfiler 用的是同一个配置键、同一个默认值")
        void 与值域剖析同一个键() throws Exception {
            String here = SemanticJoinValidator.class.getDeclaredField("configuredValueProfileEnabled")
                    .getAnnotation(Value.class).value();
            String profiler = SemanticValueProfiler.class.getDeclaredField("valueProfileEnabled")
                    .getAnnotation(Value.class).value();
            assertEquals(profiler, here);
        }
    }

    // ================================================================ 判别列为空的行

    /**
     * 判别列为 NULL / 空串的行。
     *
     * <p>这里钉的都是「错了不报错」的：SQL 滤掉这些行，返回的组看着是齐的，一条真关系被 REJECTED；
     * 把 NULL 当成一个判别值记下来，模型照抄 {@code = 'null'} 一行都连不上；只看带类型的组确认一个取值，
     * 模型照着 {@code = 'KB'} 去连 agent；说「只有在 = 'AGENT' 时才指向」，模型把按默认类型写入的历史行整批排除在外。
     */
    @Nested
    @DisplayName("★ 判别列为空的行：自成一组、算进采全、从不记成判别值、不凭过没过半否定")
    class BlankDiscriminatorGroups {

        @BeforeEach
        void tier3() {
            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
        }

        /** 本地 slice_data 的形状：attachment.owner_type 为 NULL 的是加类型列之前的历史行，按默认类型指向 orders。 */
        private Map<String, Map<String, FieldDetail>> attachmentSnapshot() {
            Map<String, Map<String, FieldDetail>> s = new LinkedHashMap<>();
            table(s, "attachment", col("id", "bigint", null), col("owner_type", "varchar(16)", null),
                    col("owner_id", "bigint", null));
            table(s, "orders", col("id", "bigint", null));
            table(s, "users", col("id", "bigint", null));
            table(s, "ct_attach", col("id", "bigint", null), col("content_type_id", "int", null),
                    col("object_id", "bigint", null));
            return s;
        }

        private SemanticJoinValidator.JoinCandidate attachmentToOrders() {
            return SemanticJoinValidator.JoinCandidate.of("attachment", "owner_id", "orders", "id");
        }

        private SemanticJoinValidator.JoinVerdict validateAttachment() {
            return validator.validate(100L, List.of(attachmentToOrders()), attachmentSnapshot()).getVerdicts().get(0);
        }

        /** 与本地 MySQL 8.0.46 上跑过的那条 SQL 逐字一致：判别列上一个过滤条件都没有。 */
        @Test
        @DisplayName("★ SQL 就是本地 MySQL 上实测过的那一条：只滤外键列的 NULL，判别列不滤")
        void SQL逐字钉住() {
            assertEquals("SELECT jm_s.jm_d AS disc_v, COUNT(DISTINCT jm_s.jm_k) AS sample_n,"
                            + " COUNT(DISTINCT CASE WHEN jm_r.`id` IS NULL THEN NULL ELSE jm_s.jm_k END) AS match_n"
                            + " FROM (SELECT DISTINCT `owner_type` AS jm_d, `owner_id` AS jm_k FROM `attachment`"
                            + " WHERE `owner_id` IS NOT NULL LIMIT 1000) jm_s"
                            + " LEFT JOIN `orders` jm_r ON jm_r.`id` = jm_s.jm_k"
                            + " GROUP BY jm_s.jm_d ORDER BY match_n DESC, sample_n DESC, disc_v ASC LIMIT 20",
                    validator.polymorphicSql(attachmentToOrders(), "owner_type", false));
        }

        /**
         * ★ 本轮修的那个错。本地 MySQL 实测（slice_data.attachment.owner_id → orders.id）：
         * 旧 SQL 只带回 {@code USER 15/0}，新 SQL 带回 {@code NULL 25/25、'' 3/3、USER 15/0}。
         */
        @Test
        @DisplayName("★ 实测数据：旧 SQL 的 USER 15/0 会判 REJECTED；新 SQL 带回 NULL 25/25 → CONFIRMED，条件是 IS NULL，不记任何取值")
        void 历史行为空的真关系不再被否定() {
            // 旧 SQL 带回的就是这一组：看起来带全了、样本也没触顶——判定本身没有错，错在它看不见为空的那一类。
            session.answers.add(groups(false, "USER", 15, 0));
            assertEquals(ConnectorSemanticService.V_REJECTED, validateAttachment().getVerified());
            auditOps.clear();

            session.answers.add(groups(false, null, 25, 25, "", 3, 3, "USER", 15, 0));
            SemanticJoinValidator.JoinValidationResult r =
                    validator.validate(100L, List.of(attachmentToOrders()), attachmentSnapshot());
            SemanticJoinValidator.JoinVerdict v = r.getVerdicts().get(0);

            assertEquals(ConnectorSemanticService.V_CONFIRMED, v.getVerified());
            assertEquals(SemanticJoinValidator.KIND_POLYMORPHIC, v.getJoinKind());
            assertNull(v.getDiscriminatorValue(), "判别列为空的那一类没有取值可记");
            assertFalse(v.detailPatch().containsKey("discriminator_value"));
            assertEquals(25, v.getSampleN());
            assertEquals(25, v.getMatchN());
            assertFalse(v.isAutoJoinable());
            assertTrue(v.isGroupedProbeRan());
            assertTrue(v.getCareReason().contains("attachment.owner_type IS NULL"), v.getCareReason());
            assertTrue(v.getCareReason().contains("TRIM(attachment.owner_type) = ''"),
                    "空串那一类 3/3 也可能指向 orders，要说出来：" + v.getCareReason());
            assertFalse(v.getCareReason().contains("= NULL"), "= NULL 永远不为真，照抄就一行都连不上");
            String all = v.getBasis() + "|" + v.getReason() + "|" + v.getCareReason() + "|" + v.detailPatch();
            assertFalse(all.contains("USER"), "没被选中的取值哪儿都不留：" + all);
            assertEquals(43L, r.getSampledRows(), "为空的那几组同样计入采样量");
            assertEquals(List.of(ConnectorAuditService.OP_SEMANTIC_VALUES), auditOps);
        }

        @Test
        @DisplayName("★ 带类型的 AGENT 唯一达标、为 NULL 的行也大半能找到：记 AGENT，但不说「只有」，并说出 IS NULL 那一类")
        void 记下取值但说出为空的那一类() {
            session.answers.add(groups(false, "AGENT", 100, 97, null, 30, 20, "MENU", 50, 2));

            SemanticJoinValidator.JoinVerdict v = validatePoly();

            assertEquals(ConnectorSemanticService.V_CONFIRMED, v.getVerified());
            assertEquals("AGENT", v.getDiscriminatorValue());
            String care = v.getCareReason();
            assertTrue(care.contains("sys_role_resource.resource_type = 'AGENT'"), care);
            assertFalse(care.contains("只有在"), "为 NULL 的行也许同样指向 agent，「只有」是假话：" + care);
            assertTrue(care.contains("sys_role_resource.resource_type IS NULL"), care);
            assertTrue(care.contains("OR"), care);
            assertTrue(care.contains("采样 30 个取值，有 20 个"), care);
        }

        @Test
        @DisplayName("为 NULL 的行只是大样本里零星几个命中（2/50）→ 照常记 AGENT，说「只有」，不加为空那一段")
        void 零星命中不算() {
            session.answers.add(groups(false, "AGENT", 100, 97, "MENU", 50, 2, null, 50, 2));

            SemanticJoinValidator.JoinVerdict v = validatePoly();

            assertEquals("AGENT", v.getDiscriminatorValue(), "PII 判定跳过 NULL，不因为它出错");
            assertTrue(v.getCareReason().contains("只有在"), v.getCareReason());
            assertFalse(v.getCareReason().contains("IS NULL"), v.getCareReason());
        }

        /**
         * ★ KB 与为 NULL 的行都全中 agent：常见是自增 id 重叠，而为空的历史行恰恰更可能才是指向 agent 的那一类。
         * 只看带类型的组，KB 是「唯一达标」——记下它，模型就照着 {@code resource_type = 'KB'} 去连 agent。
         */
        @Test
        @DisplayName("★ KB 与为 NULL 的行都达标、KB 念不出 agent → UNDECIDABLE，不记 KB")
        void 为空的一类也算进多组达标() {
            session.answers.add(groups(false, "KB", 40, 40, null, 30, 30));

            SemanticJoinValidator.JoinVerdict v = validatePoly();

            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, v.getVerified());
            assertNull(v.getDiscriminatorValue());
            assertFalse(v.detailPatch().toString().contains("KB"), v.detailPatch().toString());
            assertTrue(v.getBasis().contains("resource_type 为空的那一类"), v.getBasis());
        }

        @Test
        @DisplayName("AGENT 念得出 agent、为 NULL 的行也达标 → 按表名选 AGENT，并说出 IS NULL 那一类")
        void 按表名打破平局时仍说出为空的那一类() {
            session.answers.add(groups(false, "AGENT", 40, 40, null, 30, 30));

            SemanticJoinValidator.JoinVerdict v = validatePoly();

            assertEquals(ConnectorSemanticService.V_CONFIRMED, v.getVerified());
            assertEquals("AGENT", v.getDiscriminatorValue());
            assertTrue(v.getCareReason().contains("IS NULL"), v.getCareReason());
        }

        /**
         * ★ 为空的那一类不是一种类型：指向目标表的那一部分会被混在一起的行摊薄。
         * 12/200 只有 6%，但 12 个命中足以藏下一个自己够样本、自己达标的子类，不能凭「不过半」否定。
         */
        @Test
        @DisplayName("★ 带类型的都对不上、为 NULL 的 200 个取值命中 12 个 → UNDECIDABLE；命中 5 个 → REJECTED；为 NULL 的只有 5 个取值 → UNDECIDABLE")
        void 为空的一类挡住否定() {
            session.answers.add(groups(false, null, 200, 12, "USER", 40, 3, "MENU", 30, 1));
            SemanticJoinValidator.JoinVerdict blocked = validatePoly();
            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, blocked.getVerified());
            assertTrue(blocked.getBasis().contains("resource_type 为 NULL"), blocked.getBasis());
            assertTrue(blocked.getReason().contains("摊薄"), blocked.getReason());
            assertNull(blocked.getDiscriminatorValue());

            session.answers.add(groups(false, null, 200, 5, "USER", 40, 3, "MENU", 30, 1));
            SemanticJoinValidator.JoinVerdict rejected = validatePoly();
            assertEquals(ConnectorSemanticService.V_REJECTED, rejected.getVerified());
            assertTrue(rejected.getBasis().contains("含 resource_type 为空的 1 类"), rejected.getBasis());

            session.answers.add(groups(false, "USER", 40, 3, "MENU", 30, 1, null, 5, 0));
            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, validatePoly().getVerified(),
                    "为空的那一类样本不足，与任何一组样本不足一样挡住否定");
        }

        @Test
        @DisplayName("为 NULL 的那一类包含率最高、只到弱支持线 → WEAK，basis 说是为 NULL 的那一类，不记取值")
        void 为空的一类弱支持() {
            session.answers.add(groups(false, null, 100, 70, "MENU", 50, 5));

            SemanticJoinValidator.JoinVerdict v = validatePoly();

            assertEquals(ConnectorSemanticService.V_WEAK, v.getVerified());
            assertNull(v.getDiscriminatorValue());
            assertTrue(v.getBasis().contains("resource_type 为 NULL 的那一类行"), v.getBasis());
        }

        /** 为 NULL 的那一行同样占 LIMIT 名额：带回满 20 行（含它）就是没带全。 */
        @Test
        @DisplayName("★ 带回的 20 行里有一行判别值为 NULL → 仍按满 20 组算没带全，不 REJECTED")
        void 为空的一行占LIMIT名额() {
            List<Object> flat = new ArrayList<>();
            flat.add(null);
            flat.add(40L);
            flat.add(3L);
            for (int i = 1; i < SemanticJoinValidator.POLY_MAX_GROUPS; i++) {
                flat.add("G" + i);
                flat.add(40L);
                flat.add(3L);
            }
            session.answers.add(groups(false, flat.toArray()));

            SemanticJoinValidator.JoinVerdict v = validatePoly();

            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, v.getVerified());
            assertTrue(v.getReason().contains("前 " + SemanticJoinValidator.POLY_MAX_GROUPS + " 组"), v.getReason());
        }

        /** 数值判别列上 0 是一个真实取值（本地 MySQL 实测 0 与 NULL 各是一组），不能被当成「为空」。 */
        @Test
        @DisplayName("数值判别列：0 是取值、照常记下；NULL 才是为空的那一类")
        void 数值判别列的0不是空() {
            session.answers.add(groups(false, "0", 30, 29, null, 20, 0));

            SemanticJoinValidator.JoinVerdict v = validator.validate(100L,
                    List.of(SemanticJoinValidator.JoinCandidate.of("ct_attach", "object_id", "orders", "id")),
                    attachmentSnapshot()).getVerdicts().get(0);

            assertEquals(ConnectorSemanticService.V_CONFIRMED, v.getVerified());
            assertEquals("0", v.getDiscriminatorValue());
            assertTrue(v.getCareReason().contains("ct_attach.content_type_id = '0'"), v.getCareReason());
        }

        @Test
        @DisplayName("判定表：为空的判定、为空的那一类可能指向目标表的判定、只有为空的那一类达标时不记取值")
        void 判定表() {
            assertTrue(new SemanticJoinValidator.Group(null, 1, 0).blank());
            assertTrue(new SemanticJoinValidator.Group("", 1, 0).blank());
            assertTrue(new SemanticJoinValidator.Group("   ", 1, 0).blank());
            assertFalse(new SemanticJoinValidator.Group("0", 1, 0).blank());
            assertTrue(new SemanticJoinValidator.Group(null, 1, 0).isNull());
            assertFalse(new SemanticJoinValidator.Group("", 1, 0).isNull());

            // 绝对数够藏下一个达标的子类，或者比例过了弱支持线，才算可能。
            assertTrue(SemanticJoinValidator.blankGroupMayPointToTarget(
                    new SemanticJoinValidator.Group(null, 200, SemanticJoinValidator.MIN_CONFIRM_MATCH)));
            assertFalse(SemanticJoinValidator.blankGroupMayPointToTarget(
                    new SemanticJoinValidator.Group(null, 200, SemanticJoinValidator.MIN_CONFIRM_MATCH - 1)));
            assertTrue(SemanticJoinValidator.blankGroupMayPointToTarget(new SemanticJoinValidator.Group("", 5, 5)));
            assertTrue(SemanticJoinValidator.blankGroupMayPointToTarget(new SemanticJoinValidator.Group("  ", 12, 8)));
            assertFalse(SemanticJoinValidator.blankGroupMayPointToTarget(new SemanticJoinValidator.Group(null, 40, 3)));
            assertFalse(SemanticJoinValidator.blankGroupMayPointToTarget(new SemanticJoinValidator.Group(null, 100, 0)));
            assertFalse(SemanticJoinValidator.blankGroupMayPointToTarget(new SemanticJoinValidator.Group("USER", 100, 100)),
                    "带类型取值的组不走这条判据");

            SemanticJoinValidator.PolyDecision onlyBlank = SemanticJoinValidator.decidePolymorphic(List.of(
                    new SemanticJoinValidator.Group(null, 25, 25),
                    new SemanticJoinValidator.Group("USER", 15, 0)), true, true, "orders");
            assertEquals(ConnectorSemanticService.V_CONFIRMED, onlyBlank.verified());
            assertFalse(onlyBlank.recordValue(), "为空的那一类永远不记成判别值");
            assertTrue(onlyBlank.best().blank());
        }
    }

    // ================================================================ K-2：分组探查真的跑了

    /**
     * {@code isGroupedProbeRan()}：下游据此认定「这一轮读过取值了、判别值的有无出自一次分组测量」。
     * 拿「档位允许」代替它，一次超时会被记成「探过了、没有判别值」，这条关系从此不再被重探，而且不报错。
     */
    @Nested
    @DisplayName("★ K-2：isGroupedProbeRan 只在分组探查真的带着结果回来时为 true")
    class GroupedProbeRan {

        @Test
        @DisplayName("第 3 档分组探查带回结果 → true")
        void 分组探查回来了() {
            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
            session.answers.add(groups(false, "AGENT", 100, 97, "MENU", 50, 2));
            assertTrue(validatePoly().isGroupedProbeRan());
        }

        @Test
        @DisplayName("★ 第 3 档分组探查超时 / 权限不足 → false（查过了，但判别值的有无不出自测量）")
        void 分组探查没回来() {
            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
            for (RuntimeException failure : List.of(ConnectorException.of(ConnectorErrorCode.TIMEOUT, null),
                    ConnectorException.of(ConnectorErrorCode.FORBIDDEN, "无权限"))) {
                session.answers.add(failure);
                SemanticJoinValidator.JoinVerdict v = validatePoly();
                assertTrue(v.isProbed(), failure.toString());
                assertFalse(v.isGroupedProbeRan(), failure.toString());
            }
        }

        @Test
        @DisplayName("第 3 档限流 → 没打出去，false")
        void 限流() {
            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
            session.answers.add(ConnectorException.of(ConnectorErrorCode.RATE_LIMITED, "上限"));
            SemanticJoinValidator.JoinVerdict v = validatePoly();
            assertFalse(v.isProbed());
            assertFalse(v.isGroupedProbeRan());
        }

        @Test
        @DisplayName("★ 档位允许但没发（判别列名疑似 PII）→ false，哪怕不分条件的包含率探查照常跑了")
        void 判别列名没过PII() {
            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
            session.answers.add(containment(1000, 970));
            SemanticJoinValidator.JoinVerdict v = validator.validate(100L,
                    List.of(SemanticJoinValidator.JoinCandidate.of("contact_log", "contact_id", "members", "id")),
                    richSnapshot()).getVerdicts().get(0);
            assertTrue(v.isProbed());
            assertFalse(v.isGroupedProbeRan());
        }

        @Test
        @DisplayName("第 2 档多态外键（只跑了不分条件的包含率）/ 第 1 档 / 第 3 档的普通关系 → false")
        void 其余情况() {
            session.answers.add(containment(1000, 970));
            assertFalse(validatePoly().isGroupedProbeRan(), "第 2 档");

            givenConnection(SemanticDataTier.METADATA_ONLY.name());
            assertFalse(validatePoly().isGroupedProbeRan(), "第 1 档");

            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
            session.answers.add(containment(1000, 970));
            session.answers.add(profile(1000, 50));
            session.answers.add(profile(1000, 1000));
            assertFalse(validateOne().getVerdicts().get(0).isGroupedProbeRan(), "第 3 档的普通关系不发分组探查");
        }

        @Test
        @DisplayName("结果回来却解析不了 → true：取值已经离开客户库，重探只会再读一遍同样解析不了的结果")
        void 解析不了仍算跑过() {
            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
            List<Object> row = new ArrayList<>();
            row.add("AGENT");
            row.add("不是数字");
            row.add("也不是");
            List<List<Object>> rows = new ArrayList<>();
            rows.add(row);
            session.answers.add(new QueryResult(List.of("disc_v", "sample_n", "match_n"), rows, false, null, "已执行", 3L));

            SemanticJoinValidator.JoinVerdict v = validatePoly();

            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, v.getVerified());
            assertTrue(v.isGroupedProbeRan());
        }

        @Test
        @DisplayName("它不进 detailPatch / structuralPatch：说的是这一轮发生了什么，不是关系本身的属性")
        void 不进patch() {
            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
            session.answers.add(groups(false, "AGENT", 100, 97, "MENU", 50, 2));
            SemanticJoinValidator.JoinVerdict v = validatePoly();
            assertTrue(v.isGroupedProbeRan());
            assertFalse(v.detailPatch().toString().toLowerCase().contains("grouped"), v.detailPatch().toString());
            assertFalse(v.structuralPatch().toString().toLowerCase().contains("grouped"), v.structuralPatch().toString());
        }
    }

    // ================================================================ 结构判定的误报

    @Nested
    @DisplayName("★ 结构判定：前缀念得出目标表时是分类列 + 普通外键")
    class CategoryBesideForeignKey {

        private Map<String, Map<String, FieldDetail>> snap() {
            Map<String, Map<String, FieldDetail>> s = new LinkedHashMap<>();
            table(s, "orders", col("id", "bigint", null), col("user_type", "tinyint", "1 个人 2 企业"),
                    col("user_id", "bigint", null));
            table(s, "users", col("id", "bigint", null));
            table(s, "admins", col("id", "bigint", null));
            table(s, "sys_user", col("id", "bigint", null));
            table(s, "sys_role_resource", col("id", "bigint", null), col("resource_type", "varchar(32)", null),
                    col("resource_id", "bigint", null));
            table(s, "sys_resource", col("id", "bigint", null));
            table(s, "agent", col("id", "bigint", null));
            table(s, "ai_trace", col("id", "bigint", null), col("biz_type", "varchar(32)", null),
                    col("biz_id", "bigint", null));
            table(s, "ir_attachment", col("res_model", "varchar(64)", null), col("res_id", "int", null));
            table(s, "res_partner", col("id", "int", null));
            return s;
        }

        private String kind(String from, String column, String to) {
            return SemanticJoinValidator.structure(
                    SemanticJoinValidator.JoinCandidate.of(from, column, to, "id"), snap(), null).kind();
        }

        @Test
        @DisplayName("★ orders.user_type + user_id → users.id / sys_user.id：SIMPLE")
        void 分类列加普通外键() {
            assertEquals(SemanticJoinValidator.KIND_SIMPLE, kind("orders", "user_id", "users"));
            assertEquals(SemanticJoinValidator.KIND_SIMPLE, kind("orders", "user_id", "sys_user"));
        }

        @Test
        @DisplayName("★ 真多态外键不受影响：sys_role_resource → agent、ai_trace.biz_id、Odoo 的 res_id 仍是 POLYMORPHIC")
        void 真多态外键不受影响() {
            assertEquals(SemanticJoinValidator.KIND_POLYMORPHIC, kind("sys_role_resource", "resource_id", "agent"),
                    "库里另有一张 sys_resource 也一样：候选指向的是 agent");
            assertEquals(SemanticJoinValidator.KIND_POLYMORPHIC, kind("ai_trace", "biz_id", "agent"));
            assertEquals(SemanticJoinValidator.KIND_POLYMORPHIC, kind("ir_attachment", "res_id", "res_partner"),
                    "res 在表名头部，不算念出了目标表");
        }

        @Test
        @DisplayName("前缀念出的是另一张表（users），候选却指向 admins → 仍是 POLYMORPHIC")
        void 念出的不是目标表() {
            assertEquals(SemanticJoinValidator.KIND_POLYMORPHIC, kind("orders", "user_id", "admins"));
        }

        @Test
        @DisplayName("prefixNamesTable：只比表名尾部，认复数与业务前缀，不认带后缀的表名")
        void 前缀与表名的对应() {
            assertTrue(SemanticJoinValidator.prefixNamesTable("user_id", "user"));
            assertTrue(SemanticJoinValidator.prefixNamesTable("user_id", "users"));
            assertTrue(SemanticJoinValidator.prefixNamesTable("USER_ID", "T_USER"));
            assertTrue(SemanticJoinValidator.prefixNamesTable("userId", "SysUser"));
            assertTrue(SemanticJoinValidator.prefixNamesTable("order_item_id", "t_order_items"));
            assertTrue(SemanticJoinValidator.prefixNamesTable("category_id", "categories"));
            assertTrue(SemanticJoinValidator.prefixNamesTable("box_id", "boxes"));
            assertFalse(SemanticJoinValidator.prefixNamesTable("user_id", "user_info"), "带后缀的表名刻意不认");
            assertFalse(SemanticJoinValidator.prefixNamesTable("order_item_id", "t_item"), "多词前缀要整段对上");
            assertFalse(SemanticJoinValidator.prefixNamesTable("res_id", "res_partner"));
            assertFalse(SemanticJoinValidator.prefixNamesTable("biz_id", "agent"));
            assertFalse(SemanticJoinValidator.prefixNamesTable("paid", "users"), "不是 xx_id 形状");
        }

        /**
         * ★ 第 3 档仍靠「取值念得出表名」打破平局。本地 MySQL 实测 semval.role_resource.resource_id → agent.id：
         * 自增 id 1..3 与 1..2 重叠，AGENT 与 KB 两组都是全中——光看数据分不出来。
         */
        @Test
        @DisplayName("★ 第 3 档：自增 id 重叠、两组都全中 → 按取值与表名的对应选 AGENT；短码 KB 不猜 → 判不出来")
        void 第三档自增id重叠靠表名打破平局() {
            givenConnection(SemanticDataTier.SAMPLE_VALUES.name());
            Map<String, Map<String, FieldDetail>> s = new LinkedHashMap<>();
            table(s, "role_resource", col("id", "bigint", null), col("resource_type", "varchar(20)", null),
                    col("resource_id", "bigint", null));
            table(s, "agent", col("id", "bigint", null), col("name", "varchar(32)", null));
            table(s, "kb", col("id", "bigint", null), col("name", "varchar(32)", null));

            session.answers.add(groups(false, "AGENT", 12, 12, "KB", 12, 12));
            SemanticJoinValidator.JoinVerdict toAgent = validator.validate(100L,
                    List.of(SemanticJoinValidator.JoinCandidate.of("role_resource", "resource_id", "agent", "id")), s)
                    .getVerdicts().get(0);
            assertEquals(ConnectorSemanticService.V_CONFIRMED, toAgent.getVerified());
            assertEquals("AGENT", toAgent.getDiscriminatorValue());

            session.answers.add(groups(false, "AGENT", 12, 12, "KB", 12, 12));
            SemanticJoinValidator.JoinVerdict toKb = validator.validate(100L,
                    List.of(SemanticJoinValidator.JoinCandidate.of("role_resource", "resource_id", "kb", "id")), s)
                    .getVerdicts().get(0);
            assertEquals(ConnectorSemanticService.V_UNDECIDABLE, toKb.getVerified());
            assertNull(toKb.getDiscriminatorValue());
        }
    }

    // ================================================================ 组合键的 care_reason

    @Nested
    @DisplayName("★ 组合键的 care_reason：不替左表猜对应列，键列可空时说清 NULL 的两个坑")
    class CompositeCareText {

        /** semval.orders_p 的形状：按时间分区的表，MySQL 要求分区列进每个唯一键，于是主键是 (id, create_time)。 */
        private Map<String, Map<String, FieldDetail>> partitioned() {
            Map<String, Map<String, FieldDetail>> s = new LinkedHashMap<>();
            table(s, "order_item", col("id", "bigint", null), col("order_id", "bigint", null),
                    col("create_time", "datetime", "明细创建时间"));
            table(s, "orders_p", notNull("id", "bigint"), notNull("create_time", "datetime"));
            return s;
        }

        @Test
        @DisplayName("★ 主键 (id, create_time)：只写已知的那一对，不生成 order_item.create_time = orders_p.create_time")
        void 分区表主键不按同名列配对() {
            Map<String, Object> p = validator.structureOnly(
                    SemanticJoinValidator.JoinCandidate.of("order_item", "order_id", "orders_p", "id"),
                    partitioned(), Map.of("orders_p", List.of(List.of("id", "create_time"))));

            assertEquals("COMPOSITE", p.get("join_kind"));
            assertEquals(List.of("id", "create_time"), p.get("composite_columns"));
            String care = String.valueOf(p.get("care_reason"));
            assertTrue(care.contains("(id, create_time)"), care);
            assertTrue(care.contains("order_item.order_id = orders_p.id"), care);
            assertFalse(care.contains("order_item.create_time = orders_p.create_time"), care);
            assertFalse(care.contains("create_time = "), "除了已知那一对，一个等值条件都不许编：" + care);
            assertTrue(care.contains("create_time 在 order_item 上对应哪一列"), care);
            assertFalse(care.contains("NULL"), "主键列不可能为 NULL，不该有 NULL 那一句：" + care);
        }

        @Test
        @DisplayName("★ UNIQUE(shop_id 可空, code)：NULL 行用 = 连不上；重复的 NULL 组合用 <=> 会一行连出多行")
        void 可空键列() {
            Map<String, Map<String, FieldDetail>> s = new LinkedHashMap<>();
            table(s, "sku_ref", col("id", "bigint", null), col("sku_code", "varchar(20)", null));
            table(s, "sku", col("shop_id", "bigint", null), notNull("code", "varchar(20)"),
                    col("price", "decimal(10,2)", null));

            Map<String, Object> p = validator.structureOnly(
                    SemanticJoinValidator.JoinCandidate.of("sku_ref", "sku_code", "sku", "code"),
                    s, Map.of("sku", List.of(List.of("shop_id", "code"))));

            String care = String.valueOf(p.get("care_reason"));
            assertTrue(care.contains("sku 的 shop_id 允许为 NULL"), care);
            assertFalse(care.contains("shop_id, code 允许为 NULL"), "code 不可空，不该列进去：" + care);
            assertTrue(care.contains("NULL = NULL 不为真"), care);
            assertTrue(care.contains("<=>"), care);
            assertTrue(care.contains("照样会一行连出多行"), care);
        }
    }

    // ================================================================ structureOnly

    @Nested
    @DisplayName("structureOnly：只做结构判定，不碰库、不给判别值")
    class StructureOnly {

        @Test
        @DisplayName("★ 多态外键：给 join_kind / discriminator_column / care_reason，没有 discriminator_value；网关与连接表一次都不碰")
        void 多态外键() {
            TenantContext.clear();   // 不需要租户上下文

            Map<String, Object> p = validator.structureOnly(poly(), richSnapshot(), null);

            assertEquals("POLYMORPHIC", p.get("join_kind"));
            assertEquals("resource_type", p.get("discriminator_column"));
            assertTrue(String.valueOf(p.get("care_reason")).contains("resource_type"), String.valueOf(p));
            assertFalse(p.containsKey("discriminator_value"));
            assertFalse(p.containsKey("basis"), "只并结构键，不碰 S1 留下的来源线索");
            assertFalse(p.containsKey("verified"));
            verify(gateway, never()).executeAsPlatform(any(), any(), any(), any());
            verify(connectionMapper, never()).selectById(any());
        }

        @Test
        @DisplayName("普通关系只有 join_kind=SIMPLE；组合键带 composite_columns 与 care_reason")
        void 普通与组合键() {
            assertEquals(Map.of("join_kind", "SIMPLE"), validator.structureOnly(candidate(), snapshot(), null));

            Map<String, Object> p = validator.structureOnly(
                    SemanticJoinValidator.JoinCandidate.of("t_ref", "sku_code", "t_sku", "code"), richSnapshot(), skuKeys());

            assertEquals("COMPOSITE", p.get("join_kind"));
            assertEquals(List.of("tenant_id", "code"), p.get("composite_columns"));
            assertTrue(p.containsKey("care_reason"));
        }

        @Test
        @DisplayName("标识符对不上快照 / 形状非法 / 入参为空 → 空 Map（「没判」不是「判成普通关系」）")
        void 对不上就是空() {
            assertTrue(validator.structureOnly(
                    SemanticJoinValidator.JoinCandidate.of("order_items", "user_id", "users", "id"), snapshot(), null)
                    .isEmpty());
            assertTrue(validator.structureOnly(
                    SemanticJoinValidator.JoinCandidate.of("orders`x", "user_id", "users", "id"), snapshot(), null)
                    .isEmpty());
            assertTrue(validator.structureOnly(null, snapshot(), null).isEmpty());
            assertTrue(validator.structureOnly(candidate(), null, null).isEmpty());
        }
    }

    // ================================================================ 假会话

    /**
     * 一个只会按队列吐结果的会话。队列里放 {@link QueryResult} 就正常返回，
     * 放 {@link RuntimeException} 就抛出去——用来模拟超时、权限不足、以及没归过类的失败。
     */
    private static final class FakeSession implements ConnectorSession, QueryCapable {

        final Deque<Object> answers = new ArrayDeque<>();
        final List<String> sqls = new ArrayList<>();
        QueryOptions lastOptions;

        @Override
        public QueryResult query(String statement, QueryOptions options) {
            sqls.add(statement);
            lastOptions = options;
            Object a = answers.poll();
            if (a instanceof RuntimeException re) {
                throw re;
            }
            if (a == null) {
                throw new IllegalStateException("测试没有为这次探查准备返回值，已发出的 SQL: " + sqls.size());
            }
            return (QueryResult) a;
        }

        @Override
        public void ping() {
        }

        @Override
        public ReadOnlyVerdict verifyReadOnly() {
            return ReadOnlyVerdict.confirmed("测试");
        }

        @Override
        public Set<Capability> probeCapabilities() {
            return Set.of(Capability.QUERY);
        }

        @Override
        public void close() {
        }
    }
}
