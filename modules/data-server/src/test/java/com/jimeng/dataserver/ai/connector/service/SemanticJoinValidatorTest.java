package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.guard.ReadOnlySqlGuard;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.QueryResult;
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
        validator = new SemanticJoinValidator(gateway, connectionMapper);
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
            SemanticJoinValidator v = new SemanticJoinValidator(gateway, connectionMapper);
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
