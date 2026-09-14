package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.model.QueryResult;
import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorGateway;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryOptions;
import com.jimeng.persistence.entity.ConnectorSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S4 值域剖析。
 *
 * <h3>测法：假会话 + 真网关签名</h3>
 * 本仓库没有 testcontainers / H2，连不了真库。所以 {@link ConnectorGateway#executeAsPlatform}
 * 被 mock 成「把 Op 原样跑在一个假会话上」——这样<b>语句文本、语句条数、语句顺序</b>
 * 三件事全部可断言，而它们恰好就是这个类的全部行为。
 *
 * <h3>这个类真正在钉的东西</h3>
 * 值域剖析的每一种失效都是<b>静默</b>的，且方向一致：产出一个看起来完整的残缺集合。
 * <ul>
 *   <li>档位没开却返回空集合 → 模型读成「这列没有枚举值」，放心写死条件；</li>
 *   <li>基数闸没先跑、或者高基数时退而求其次取前 N 个 → 部分集合冒充全集；</li>
 *   <li>取值里混进一个手机号 → 客户的 PII 进我们的库、进提示词、进 ai_model_call_content；</li>
 *   <li>把过长的取值截断存下来 → 造出一个库里根本不存在的取值。</li>
 * </ul>
 * 下面每一条测试对应的都是一种「跑起来不报错、结果却是错的」情形。
 */
class SemanticValueProfilerTest {

    private static final Long CONNECTOR_ID = 7L;

    private ConnectorGateway gateway;
    private ConnectorService connectorService;
    private ConnectorSchemaService schemaService;
    private SemanticValueProfiler profiler;
    private FakeSession session;

    @BeforeEach
    void setUp() {
        gateway = mock(ConnectorGateway.class);
        connectorService = mock(ConnectorService.class);
        schemaService = mock(ConnectorSchemaService.class);
        session = new FakeSession();
        // PiiFilter 用真对象：它的判定本身就是被测行为的一部分，mock 掉等于把最要紧的那道闸测没了。
        profiler = new SemanticValueProfiler(gateway, connectorService, schemaService, new PiiFilter());

        // 节流在单测里没有被测价值，只会让每条用例多睡几秒。真正要钉的是「节流存在且可配」，
        // 那由 ConfigClamp 那一组直接测。
        ReflectionTestUtils.setField(profiler, "statementsPerMinute", 60_000);

        when(connectorService.dataTier(CONNECTOR_ID)).thenReturn(SemanticDataTier.SAMPLE_VALUES);
        when(gateway.executeAsPlatform(any(), any(), any(), any())).thenAnswer(inv -> {
            session.connectorIds.add(inv.getArgument(0));
            session.capabilities.add(inv.getArgument(1));
            session.operations.add(inv.getArgument(2));
            ConnectorGateway.Op<?> op = inv.getArgument(3);
            return op.apply(session);
        });
    }

    // ================================================================ 档位

    @Nested
    @DisplayName("数据出库档位")
    class Tier {

        @Test
        @DisplayName("★ 档位没开：返回「未启用样本值采集」，不是空集合，而且一条语句都不发")
        void tierDisabledSaysSoAndTouchesNothing() {
            when(connectorService.dataTier(CONNECTOR_ID)).thenReturn(SemanticDataTier.DERIVED_STATS);

            SemanticValueProfiler.ValueProfile p = profiler.profile(CONNECTOR_ID, List.of(table()));

            assertFalse(p.isTierAllowed());
            assertEquals(SemanticValueProfiler.NOT_ENABLED_NOTE, p.getTierStatement());
            assertTrue(p.getNotes().contains(SemanticValueProfiler.NOT_ENABLED_NOTE));
            assertTrue(p.getDomains().isEmpty());
            // 「没去查」必须不花任何代价，也不留任何审计。
            verify(gateway, never()).executeAsPlatform(any(), any(), any(), any());
            assertEquals(0, p.getStatements());
        }

        @Test
        @DisplayName("第 1 档同样不允许——只有显式的第 3 档才放行")
        void metadataOnlyAlsoBlocked() {
            when(connectorService.dataTier(CONNECTOR_ID)).thenReturn(SemanticDataTier.METADATA_ONLY);
            assertFalse(profiler.profile(CONNECTOR_ID, List.of(table())).isTierAllowed());
        }

        @Test
        @DisplayName("★ 空集合与「没去查」必须给出不同的说法")
        void notEnabledFragmentNeverLooksLikeAnEmptyDomain() {
            Map<String, Object> f = SemanticValueProfiler.notEnabledFragment();
            assertEquals(false, f.get("complete"));
            assertFalse(f.containsKey("values"), "没查过的列绝不能带 values 键——空列表会被读成「这列没有取值」");
            assertEquals(SemanticValueProfiler.NOT_ENABLED_NOTE, f.get("note"));
        }
    }

    // ================================================================ 不采样

    @Nested
    @DisplayName("★ 不采样：基数闸先跑，过不了就明说放弃")
    class NoSampling {

        @Test
        @DisplayName("先 COUNT(DISTINCT) 再 SELECT DISTINCT，顺序不可颠倒")
        void cardinalityGuardRunsFirst() {
            session.plan("st", 4L, List.of("2", "0", "3", "1"));

            SemanticValueProfiler.ValueProfile p = profiler.profile(CONNECTOR_ID, List.of(table()));

            assertEquals(2, session.statements.size());
            assertTrue(session.statements.get(0).startsWith("SELECT COUNT(DISTINCT `st`)"), session.statements.get(0));
            assertTrue(session.statements.get(1).startsWith("SELECT DISTINCT `st`"), session.statements.get(1));
            assertEquals(2, p.getStatements());
        }

        @Test
        @DisplayName("低基数：拿到完整集合，并且是排好序的（同一份数据两次剖析要产出同一个 JSON）")
        void lowCardinalityGivesTheCompleteSet() {
            session.plan("st", 4L, List.of("2", "0", "3", "1"));

            SemanticValueProfiler.ColumnValueDomain d = only(profiler.profile(CONNECTOR_ID, List.of(table())));

            assertEquals(SemanticValueProfiler.Outcome.ENUMERATED, d.getOutcome());
            assertTrue(d.hasCompleteValueSet());
            assertEquals(List.of("0", "1", "2", "3"), d.getValues());
            assertEquals(Long.valueOf(4L), d.getDistinctCount());
        }

        @Test
        @DisplayName("★ 高基数：放弃并明说，绝不退而求其次取前 N 个")
        void highCardinalityGivesUpLoudly() {
            ReflectionTestUtils.setField(profiler, "maxDistinct", 3);
            session.plan("st", 9999L, List.of("不该被取到"));

            SemanticValueProfiler.ColumnValueDomain d = only(profiler.profile(CONNECTOR_ID, List.of(table())));

            assertEquals(SemanticValueProfiler.Outcome.HIGH_CARDINALITY, d.getOutcome());
            // 一个「部分集合 + truncated=false」比没有值域更危险——所以第二条语句根本不能发出去。
            assertEquals(1, session.statements.size());
            assertNull(d.getValues());
            assertFalse(d.hasCompleteValueSet());
            assertTrue(d.getNote().contains("不要假设"));
        }

        @Test
        @DisplayName("★ 取值查询要多要一行：LIMIT 被撑满就说明期间变多了，宁可整列不要")
        void limitIsDistinctPlusOneAndAFullLimitMeansIncomplete() {
            // 基数闸说 2 种，取回来却有 3 行（LIMIT = 2 + 1 被撑满）——集合在采集期间变大了。
            session.plan("st", 2L, List.of("a", "b", "c"));

            SemanticValueProfiler.ColumnValueDomain d = only(profiler.profile(CONNECTOR_ID, List.of(table())));

            assertTrue(session.statements.get(1).endsWith("LIMIT 3"), session.statements.get(1));
            assertEquals(SemanticValueProfiler.Outcome.INCOMPLETE, d.getOutcome());
            assertNull(d.getValues());
        }

        @Test
        @DisplayName("命中字节上限也算不完整——truncated 必须当真")
        void truncatedMeansIncomplete() {
            session.planTruncated("st", 2L, List.of("a"));
            SemanticValueProfiler.ColumnValueDomain d = only(profiler.profile(CONNECTOR_ID, List.of(table())));
            assertEquals(SemanticValueProfiler.Outcome.INCOMPLETE, d.getOutcome());
            assertNull(d.getValues());
        }

        @Test
        @DisplayName("★ 基数为 0 是「这列现在是空的」，不是「这列没有枚举值」")
        void emptyIsNotTheSameAsNoDomain() {
            session.plan("st", 0L, List.of());

            SemanticValueProfiler.ColumnValueDomain d = only(profiler.profile(CONNECTOR_ID, List.of(table())));

            assertEquals(SemanticValueProfiler.Outcome.EMPTY, d.getOutcome());
            assertEquals(1, session.statements.size(), "基数是 0 就没必要再发第二条语句");
            assertNull(d.getValues());
            assertTrue(d.getNote().contains("这不等于它没有枚举值"));
        }

        @Test
        @DisplayName("语句形状：IS NOT NULL 是为了和基数闸数同一件事")
        void distinctStatementShape() {
            session.plan("st", 2L, List.of("a", "b"));
            profiler.profile(CONNECTOR_ID, List.of(table()));
            assertEquals("SELECT DISTINCT `st` FROM `t_order` WHERE `st` IS NOT NULL LIMIT 3",
                    session.statements.get(1));
        }
    }

    // ================================================================ 基数闸的超时

    @Nested
    @DisplayName("★ 基数闸的专用短超时")
    class CardinalityTimeout {

        @Test
        @DisplayName("COUNT(DISTINCT) 用的是比查询默认 15 秒更短的超时")
        void usesItsOwnShorterTimeout() {
            session.plan("st", 2L, List.of("a", "b"));
            profiler.profile(CONNECTOR_ID, List.of(table()));

            assertEquals(5, session.options.get(0).timeoutSec(), "基数闸必须用自己的短超时");
            assertEquals(10, session.options.get(1).timeoutSec());
            assertTrue(session.options.get(0).timeoutSec() < session.options.get(1).timeoutSec());
        }

        @Test
        @DisplayName("★ 超时按高基数处理：跳过这一列并记一句，而不是让整次剖析失败")
        void timeoutIsTreatedAsHighCardinalityNotAsFailure() {
            session.planCountFailure("st", ConnectorException.of(ConnectorErrorCode.TIMEOUT, "查询超时"));
            session.plan("region", 2L, List.of("华东", "华北"));

            SemanticValueProfiler.ValueProfile p = profiler.profile(CONNECTOR_ID, List.of(twoColumnTable()));

            SemanticValueProfiler.ColumnValueDomain st = domainOf(p, "st");
            assertEquals(SemanticValueProfiler.Outcome.CARDINALITY_UNKNOWN, st.getOutcome());
            assertNull(st.getValues());
            assertTrue(st.getNote().contains("不要假设"));
            // 后面的列照跑完——一列超时不该把整次剖析带走。
            assertEquals(SemanticValueProfiler.Outcome.ENUMERATED, domainOf(p, "region").getOutcome());
        }
    }

    // ================================================================ PII

    @Nested
    @DisplayName("★ PII 过滤在第 3 档上不是可选项")
    class Pii {

        @Test
        @DisplayName("列名判定的列压根不去查——不是查回来再丢")
        void piiColumnIsNeverQueried() {
            SemanticValueProfiler.ValueProfile p = profiler.profile(CONNECTOR_ID, List.of(phoneTable()));

            SemanticValueProfiler.ColumnValueDomain d = only(p);
            assertEquals(SemanticValueProfiler.Outcome.PII_BLOCKED, d.getOutcome());
            assertEquals(0, session.statements.size(), "手机号列不该被查一遍再丢");
            assertNull(d.getValues());
        }

        @Test
        @DisplayName("列名看不出来时，取回来的取值还要再过一道；命中即整列丢")
        void piiInValuesDropsTheWholeColumn() {
            session.plan("f1", 3L, List.of("华东", "13800138000", "华北"));

            SemanticValueProfiler.ColumnValueDomain d =
                    only(profiler.profile(CONNECTOR_ID, List.of(opaqueColumnTable())));

            assertEquals(SemanticValueProfiler.Outcome.PII_BLOCKED, d.getOutcome());
            assertNull(d.getValues(), "不能把剩下两个「干净」的值留下来——那是一个冒充全集的残缺集合");
        }

        @Test
        @DisplayName("★ 维值列不能被误杀：华东 / 华北 必须活下来")
        void dimensionValuesSurvive() {
            session.plan("region", 4L, List.of("华东", "华北", "华南", "西南"));

            SemanticValueProfiler.ColumnValueDomain d =
                    only(profiler.profile(CONNECTOR_ID, List.of(regionTable())));

            assertEquals(SemanticValueProfiler.Outcome.ENUMERATED, d.getOutcome());
            assertEquals(4, d.getValues().size());
            assertTrue(d.getValues().contains("华东"));
        }
    }

    // ================================================================ 选列

    @Nested
    @DisplayName("选列：按类型和快照元数据判，不按名字猜")
    class Eligibility {

        @Test
        @DisplayName("自由文本 / 二进制 / 时间 / 小数一律不查")
        void ineligibleTypesAreNeverQueried() {
            ConnectorSchema t = schema("t_x", "BASE TABLE", List.of(
                    col("memo", "text", null),
                    col("photo", "blob", null),
                    col("created_at", "datetime", null),
                    col("amount", "decimal(10,2)", null),
                    col("payload", "json", null)));

            SemanticValueProfiler.ValueProfile p = profiler.profile(CONNECTOR_ID, List.of(t));

            assertEquals(0, session.statements.size());
            assertEquals(0, p.getEligibleColumns());
            assertEquals(5, p.getExaminedColumns());
            // 噪音不进 domains：几千列里把「这是个 datetime」也写成一条，会把要紧的那几条淹掉。
            assertTrue(p.getDomains().isEmpty());
        }

        @Test
        @DisplayName("自增 / 唯一 / 单列主键：元数据已经写明它必然高基数，不必再付一次全表 COUNT")
        void uniqueByMetadataIsSkippedWithoutAQuery() {
            ConnectorSchema t = schema("t_x", "BASE TABLE", List.of(
                    colExtra("id", "bigint(20)", "主键 / auto_increment"),
                    colExtra("order_no", "varchar(32)", "唯一")));

            profiler.profile(CONNECTOR_ID, List.of(t));

            assertEquals(0, session.statements.size());
        }

        @Test
        @DisplayName("★ 复合主键的成员列要留下——按地区分区的联合主键正是低基数维度")
        void compositePrimaryKeyMembersStayEligible() {
            ConnectorSchema t = schema("t_x", "BASE TABLE", List.of(
                    colExtra("region", "varchar(16)", "主键"),
                    colExtra("day", "varchar(8)", "主键")));
            session.plan("region", 2L, List.of("华东", "华北"));
            session.plan("day", 2L, List.of("周一", "周二"));

            SemanticValueProfiler.ValueProfile p = profiler.profile(CONNECTOR_ID, List.of(t));

            assertEquals(2, p.getEligibleColumns());
        }

        @Test
        @DisplayName("视图跳过：它背后可能是一串 join，成本不可预估")
        void viewsAreSkipped() {
            ConnectorSchema v = schema("v_summary", "VIEW", List.of(col("st", "tinyint(4)", null)));
            SemanticValueProfiler.ValueProfile p = profiler.profile(CONNECTOR_ID, List.of(v));

            assertEquals(0, session.statements.size());
            assertTrue(p.getNotes().stream().anyMatch(n -> n.contains("视图")));
        }

        @Test
        @DisplayName("★ varchar(255) 不能被当成自由文本排掉——中文库里维度列大半都声明成 255")
        void varchar255IsStillACandidate() {
            ConnectorSchema t = schema("t_x", "BASE TABLE", List.of(col("region", "varchar(255)", null)));
            session.plan("region", 2L, List.of("华东", "华北"));

            assertEquals(SemanticValueProfiler.Outcome.ENUMERATED,
                    only(profiler.profile(CONNECTOR_ID, List.of(t))).getOutcome());
        }

        @Test
        @DisplayName("enum('a','b') 括号里不是长度，别把最该采的列解析崩了")
        void enumTypeParsesAsEligible() {
            assertNull(SemanticValueProfiler.declaredLength("enum('a','b')"));
            assertEquals("enum", SemanticValueProfiler.baseType("enum('a','b')"));
            assertEquals(255, SemanticValueProfiler.declaredLength("varchar(255)"));
            assertNull(SemanticValueProfiler.declaredLength("decimal(10,2)"));
            assertEquals("int", SemanticValueProfiler.baseType("int(11) unsigned"));
        }

        @Test
        @DisplayName("★ 标识符验不过就不拼语句：少一个值域，换本类永远拼不出奇怪的 SQL")
        void weirdIdentifierNeverReachesSql() {
            ConnectorSchema t = schema("t_x", "BASE TABLE", List.of(col("bad name", "varchar(8)", null)));
            profiler.profile(CONNECTOR_ID, List.of(t));
            assertEquals(0, session.statements.size());
        }
    }

    // ================================================================ 节奏、审计、失败处置

    @Nested
    @DisplayName("节奏与审计")
    class PacingAndAudit {

        @Test
        @DisplayName("★ 一条语句一次网关调用，动作名是 platform. 开头的平台动作")
        void oneGatewayCallPerStatement() {
            session.plan("st", 2L, List.of("a", "b"));
            session.plan("region", 2L, List.of("华东", "华北"));

            profiler.profile(CONNECTOR_ID, List.of(twoColumnTable()));

            // 4 条语句 = 4 次调用。攒成一次的话，网关只会把最后一条语句写进审计，
            // 而客户 DBA 在他自己的审计里看到的是 4 条——两边对不上的审计等于没有审计。
            assertEquals(4, session.statements.size());
            assertEquals(4, session.operations.size());
            for (String op : session.operations) {
                // ★ 必须是 OP_SEMANTIC_VALUES，不能是关系采样验证的 OP_SEMANTIC_PROBE。
                // 本类读的是客户的【真实取值】（第 3 档），关系验证只读聚合数（第 2 档），
                // 两者敏感度差一个量级。客户 DBA 在 connector_audit 里最常做的事就是按 operation
                // 过滤——分名之后他不必读语句原文就能回答「平台到底有没有读过我的业务数据值」。
                // 合成一个名字，这个问题就只能靠逐条看 SQL 回答，而那正是审计表存在的意义。
                assertEquals(ConnectorAuditService.OP_SEMANTIC_VALUES, op);
                assertNotEquals(ConnectorAuditService.OP_SEMANTIC_PROBE, op);
                assertTrue(op.startsWith(ConnectorAuditService.PLATFORM_OP_PREFIX));
            }
            for (Capability cap : session.capabilities) {
                assertEquals(Capability.QUERY, cap);
            }
            for (Object id : session.connectorIds) {
                assertEquals(CONNECTOR_ID, id);
            }
        }

        @Test
        @DisplayName("★ 连不上这类错在每一列都会重演，中止整次剖析而不是刷几百条一样的失败")
        void fatalErrorsAbortTheWholeRun() {
            session.planCountFailure("st", ConnectorException.of(ConnectorErrorCode.UNREACHABLE, null));
            session.plan("region", 2L, List.of("华东", "华北"));

            SemanticValueProfiler.ValueProfile p = profiler.profile(CONNECTOR_ID, List.of(twoColumnTable()));

            assertEquals(1, session.statements.size(), "第一列就连不上，不该继续打第二列");
            assertTrue(p.getNotes().stream().anyMatch(n -> n.contains("剖析中止")));
        }

        @Test
        @DisplayName("单表授权不足只影响这一列，后面的列照跑")
        void perTableErrorsDoNotAbort() {
            session.planCountFailure("st", ConnectorException.of(ConnectorErrorCode.FORBIDDEN, "这个账号没有访问该对象的权限"));
            session.plan("region", 2L, List.of("华东", "华北"));

            SemanticValueProfiler.ValueProfile p = profiler.profile(CONNECTOR_ID, List.of(twoColumnTable()));

            assertEquals(SemanticValueProfiler.Outcome.FAILED, domainOf(p, "st").getOutcome());
            assertEquals(SemanticValueProfiler.Outcome.ENUMERATED, domainOf(p, "region").getOutcome());
        }

        @Test
        @DisplayName("★ 失败说明里只能出现已脱敏的那套文案")
        void failureNoteCarriesOnlySafeText() {
            session.planCountFailure("st",
                    ConnectorException.of(ConnectorErrorCode.UPSTREAM_ERROR, "目标系统返回了错误"));

            SemanticValueProfiler.ColumnValueDomain d = only(profiler.profile(CONNECTOR_ID, List.of(table())));

            assertTrue(d.getNote().contains("对方报错"));
            assertFalse(d.getNote().toLowerCase().contains("jdbc"));
            assertFalse(d.getNote().contains("`t_order`"));
        }

        @Test
        @DisplayName("超过列数上限时明说，不静默丢列")
        void columnCapIsAnnounced() {
            ReflectionTestUtils.setField(profiler, "maxColumns", 1);
            session.plan("st", 2L, List.of("a", "b"));

            SemanticValueProfiler.ValueProfile p = profiler.profile(CONNECTOR_ID, List.of(twoColumnTable()));

            assertEquals(1, p.getAttemptedColumns());
            assertTrue(p.getNotes().stream().anyMatch(n -> n.contains("超过单次上限")));
            assertTrue(p.getNotes().stream().anyMatch(n -> n.contains("以后都不会被采集")),
                    "列数上限是永久天花板，必须说出来：" + p.getNotes());
        }

        @Test
        @DisplayName("墙钟预算：抽成纯函数才测得到，留在循环里就只能靠真的跑满")
        void budget() {
            assertFalse(SemanticValueProfiler.budgetExhausted(0L, 179_000L, 180));
            assertTrue(SemanticValueProfiler.budgetExhausted(0L, 180_000L, 180));
            assertFalse(SemanticValueProfiler.budgetExhausted(0L, Long.MAX_VALUE / 2, 0), "0 表示不设预算");
        }

        @Test
        @DisplayName("★ 自己节流：不节流不是慢一点，是每一轮都在同一个地方撞上平台限流然后中止")
        void throttleExistsAndIsSpacedOut() {
            SemanticValueProfiler fresh = new SemanticValueProfiler(
                    mock(ConnectorGateway.class), mock(ConnectorService.class),
                    mock(ConnectorSchemaService.class), new PiiFilter());
            // 默认 20 条/分钟：与关系探查的 30 条/分钟共用网关那一个平台桶（默认 60/分钟）
            assertEquals(20, ReflectionTestUtils.getField(fresh, "statementsPerMinute"));
            assertTrue(SemanticValueProfiler.paceWaitMs(1_000L, 400L) > 0);
            assertTrue(SemanticValueProfiler.paceWaitMs(1_000L, 1_000L) <= 0, "到点了就不该再等");
        }

        @Test
        @DisplayName("★ 排序在截断之前：被永久切掉的必须是最没价值的尾巴")
        void theCapCutsTheLeastValuableTail() {
            ReflectionTestUtils.setField(profiler, "maxColumns", 1);
            // 快照里 bigint 排在前面，但状态码列才是这个功能的原型案例，必须是它活下来。
            ConnectorSchema t = schema("t_x", "BASE TABLE", List.of(
                    col("ref_id", "bigint(20)", null),
                    col("st", "tinyint(4)", null)));
            session.plan("st", 2L, List.of("0", "1"));

            SemanticValueProfiler.ValueProfile p = profiler.profile(CONNECTOR_ID, List.of(t));

            assertEquals("st", only(p).getFieldName(), "按快照原序截断会让 t_z 开头的表永远没有值域");
        }
    }

    @Nested
    @DisplayName("启动期配置校验")
    class ConfigClamp {

        @Test
        @DisplayName("超出区间的配置夹回来并告警，不让服务起不来——值域剖析是叠加增强")
        void outOfRangeValuesAreClampedNotFatal() {
            SemanticValueProfiler v = new SemanticValueProfiler(
                    mock(ConnectorGateway.class), mock(ConnectorService.class),
                    mock(ConnectorSchemaService.class), new PiiFilter());
            ReflectionTestUtils.setField(v, "statementsPerMinute", 0);
            ReflectionTestUtils.setField(v, "maxDistinct", -1);
            ReflectionTestUtils.setField(v, "cardinalityTimeoutSeconds", 9999);

            v.clampConfig();

            // 0 会被读成「不限速」，而不限速在这里等于拿客户的生产库当压测目标。
            assertEquals(1, ReflectionTestUtils.getField(v, "statementsPerMinute"));
            assertEquals(1, ReflectionTestUtils.getField(v, "maxDistinct"));
            assertEquals(60, ReflectionTestUtils.getField(v, "cardinalityTimeoutSeconds"));
        }

        @Test
        @DisplayName("★ 两个配置项互相拆台：列数上限与预算分开看都合理，合起来可能只跑得到零头")
        void budgetMustCoverTheColumnCap() {
            // 200 列 × 2 条语句 ÷ 20 条每分钟 = 1200 秒。默认预算必须盖得住，否则
            // 「上限 200 列」是一句假话，而且运行期完全看不出来。
            assertEquals(1200L, SemanticValueProfiler.requiredBudgetSeconds(200, 20));
            SemanticValueProfiler fresh = new SemanticValueProfiler(
                    mock(ConnectorGateway.class), mock(ConnectorService.class),
                    mock(ConnectorSchemaService.class), new PiiFilter());
            int budget = (int) ReflectionTestUtils.getField(fresh, "budgetSeconds");
            int columns = (int) ReflectionTestUtils.getField(fresh, "maxColumns");
            int perMinute = (int) ReflectionTestUtils.getField(fresh, "statementsPerMinute");
            assertTrue(budget >= SemanticValueProfiler.requiredBudgetSeconds(columns, perMinute),
                    "默认预算 " + budget + " 秒盖不住默认列数上限 " + columns + " 列");
        }
    }

    // ================================================================ 产出形状

    @Nested
    @DisplayName("★ 产出：只给集合，绝不给含义；不完整就绝不带 values")
    class Output {

        @Test
        @DisplayName("完整集合的 detail 片段带 values，并且明写「平台不知道每个取值代表什么」")
        void enumeratedFragment() {
            session.plan("st", 4L, List.of("0", "1", "2", "3"));
            SemanticValueProfiler.ColumnValueDomain d = only(profiler.profile(CONNECTOR_ID, List.of(table())));

            Map<String, Object> f = SemanticValueProfiler.detailFragment(d);

            assertEquals(true, f.get("complete"));
            assertEquals(List.of("0", "1", "2", "3"), f.get("values"));
            assertEquals(4L, f.get("distinct_count"));
            // 禁止推断枚举值含义：产出里只有集合，没有任何一处把 0 说成「待支付」。
            assertTrue(String.valueOf(f.get("note")).contains("不知道"));
            assertEquals(Set.of("complete", "outcome", "distinct_count", "values", "note"), f.keySet());
        }

        @Test
        @DisplayName("★ 非完整结论的 detail 片段一定没有 values 键")
        void nonCompleteFragmentsNeverCarryValues() {
            for (SemanticValueProfiler.Outcome o : SemanticValueProfiler.Outcome.values()) {
                if (o == SemanticValueProfiler.Outcome.ENUMERATED) {
                    continue;
                }
                SemanticValueProfiler.ColumnValueDomain d = SemanticValueProfiler.ColumnValueDomain.builder()
                        .objectName("t").fieldName("c").outcome(o)
                        // 就算有人手工塞了一批值进来，出口也不能把它写出去
                        .values(List.of("不该出现"))
                        .note(o.modelNote())
                        .build();
                Map<String, Object> f = SemanticValueProfiler.detailFragment(d);
                assertEquals(false, f.get("complete"), o.name());
                assertFalse(f.containsKey("values"), o.name() + " 不该带 values");
                assertFalse(d.hasCompleteValueSet(), o.name());
            }
        }

        @Test
        @DisplayName("每一种非完整结论都必须明说「平台没有拿到完整取值」")
        void everyIncompleteOutcomeSaysSo() {
            for (SemanticValueProfiler.Outcome o : SemanticValueProfiler.Outcome.values()) {
                String note = o.modelNote();
                assertNotNull(note, o.name());
                if (o == SemanticValueProfiler.Outcome.ENUMERATED) {
                    continue;
                }
                assertTrue(note.contains("没有") || note.contains("不采集") || note.contains("不采用")
                                || note.contains("不等于"),
                        o.name() + " 的说明没有把「我们没拿到全集」说出来：" + note);
            }
        }

        @Test
        @DisplayName("★ 取值过长时丢整列，绝不截断——截出来的值在客户库里根本不存在")
        void tooLongValuesAreDroppedNotTruncated() {
            String longValue = "这是一段很长的自由文本".repeat(10);
            session.plan("f1", 2L, List.of("短的", longValue));

            SemanticValueProfiler.ColumnValueDomain d =
                    only(profiler.profile(CONNECTOR_ID, List.of(opaqueColumnTable())));

            assertEquals(SemanticValueProfiler.Outcome.NOT_ELIGIBLE, d.getOutcome());
            assertNull(d.getValues());
        }
    }

    // ================================================================ 配置

    @Nested
    @DisplayName("配置默认值")
    class Config {

        @Test
        @DisplayName("★ @Value 的内联默认与 Java 字段初始值必须一致，否则测试全绿、线上是另一套阈值")
        void inlineDefaultsMatchFieldInitializers() throws Exception {
            SemanticValueProfiler fresh = new SemanticValueProfiler(
                    mock(ConnectorGateway.class), mock(ConnectorService.class),
                    mock(ConnectorSchemaService.class), new PiiFilter());
            int checked = 0;
            for (Field f : SemanticValueProfiler.class.getDeclaredFields()) {
                Value v = f.getAnnotation(Value.class);
                if (v == null) {
                    continue;
                }
                String expr = v.value();
                int colon = expr.indexOf(':');
                assertTrue(colon > 0, f.getName() + " 的 @Value 没有内联默认值");
                String inline = expr.substring(colon + 1, expr.length() - 1);
                f.setAccessible(true);
                assertEquals(inline, String.valueOf(f.get(fresh)),
                        "字段 " + f.getName() + " 的内联默认与初始值不一致");
                checked++;
            }
            assertTrue(checked >= 7, "预期至少 7 个可配项，实际 " + checked);
        }

        @Test
        @DisplayName("运维总开关关掉时，同样不发语句")
        void killSwitch() {
            ReflectionTestUtils.setField(profiler, "valueProfileEnabled", false);
            SemanticValueProfiler.ValueProfile p = profiler.profile(CONNECTOR_ID, List.of(table()));
            assertFalse(p.isTierAllowed());
            assertEquals(0, session.statements.size());
        }

        @Test
        @DisplayName("快照为空时如实说，不当成「这个库没有枚举列」")
        void emptySnapshot() {
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(List.of());
            SemanticValueProfiler.ValueProfile p = profiler.profile(CONNECTOR_ID);
            assertTrue(p.isTierAllowed());
            assertTrue(p.getNotes().stream().anyMatch(n -> n.contains("结构快照为空")));
        }

        @Test
        @DisplayName("不传快照时自己去读，读到的就是集成期看到的那一份")
        void readsSnapshotWhenNotSupplied() {
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(List.of(table()));
            session.plan("st", 2L, List.of("a", "b"));
            assertEquals(SemanticValueProfiler.Outcome.ENUMERATED, only(profiler.profile(CONNECTOR_ID)).getOutcome());
        }
    }

    // ================================================================ 夹具

    private static SemanticValueProfiler.ColumnValueDomain only(SemanticValueProfiler.ValueProfile p) {
        assertEquals(1, p.getDomains().size(), "预期恰好一条结论，实际 " + p.getDomains());
        return p.getDomains().get(0);
    }

    private static SemanticValueProfiler.ColumnValueDomain domainOf(SemanticValueProfiler.ValueProfile p, String col) {
        return p.getDomains().stream().filter(d -> col.equals(d.getFieldName())).findFirst()
                .orElseThrow(() -> new AssertionError("没有列 " + col + " 的结论：" + p.getDomains()));
    }

    private static ConnectorSchema table() {
        return schema("t_order", "BASE TABLE", List.of(col("st", "tinyint(4)", "状态")));
    }

    private static ConnectorSchema twoColumnTable() {
        return schema("t_order", "BASE TABLE",
                List.of(col("st", "tinyint(4)", "状态"), col("region", "varchar(16)", "大区")));
    }

    private static ConnectorSchema regionTable() {
        return schema("t_order", "BASE TABLE", List.of(col("region", "varchar(16)", "大区")));
    }

    private static ConnectorSchema phoneTable() {
        return schema("t_user", "BASE TABLE", List.of(col("phone", "varchar(20)", null)));
    }

    /** 列名毫无信息量：列名那一层判不出来，只能靠取值形状。 */
    private static ConnectorSchema opaqueColumnTable() {
        return schema("t_x", "BASE TABLE", List.of(col("f1", "varchar(200)", null)));
    }

    private static Map<String, Object> col(String name, String type, String comment) {
        return colExtra(name, type, null, comment);
    }

    private static Map<String, Object> colExtra(String name, String type, String extra) {
        return colExtra(name, type, extra, null);
    }

    private static Map<String, Object> colExtra(String name, String type, String extra, String comment) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        m.put("nullable", true);
        m.put("comment", comment);
        m.put("extra", extra);
        return m;
    }

    /** 形状与 {@code ConnectorSchemaService.toDetailMap} 一致——快照的 JSON 形状是两边的共同契约。 */
    private static ConnectorSchema schema(String name, String type, List<Map<String, Object>> fields) {
        ConnectorSchema s = new ConnectorSchema();
        s.setConnectorId(CONNECTOR_ID);
        s.setObjectName(name);
        s.setObjectType(type);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", name);
        detail.put("type", type);
        detail.put("comment", null);
        detail.put("fields", fields);
        detail.put("extra", Map.of());
        try {
            s.setDetailJson(CommonUtil.getObjectMapper().writeValueAsString(detail));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    /**
     * 假会话：记下每一条语句和它的护栏参数，按列名回放预置结果。
     *
     * <p>比 mock 一个 {@code MySqlSession} 好的地方是它能<b>断言语句原文</b>——
     * 而语句原文（先 COUNT 后 DISTINCT、LIMIT 是基数 +1、带 IS NOT NULL）
     * 就是这个类的全部行为。
     */
    private static final class FakeSession implements ConnectorSession, QueryCapable {

        private final List<String> statements = new ArrayList<>();
        private final List<QueryOptions> options = new ArrayList<>();
        private final List<Object> connectorIds = new ArrayList<>();
        private final List<Capability> capabilities = new ArrayList<>();
        private final List<String> operations = new ArrayList<>();

        private final Map<String, Long> counts = new LinkedHashMap<>();
        private final Map<String, List<String>> values = new LinkedHashMap<>();
        private final Map<String, ConnectorException> countFailures = new LinkedHashMap<>();
        private final Set<String> truncatedColumns = new java.util.LinkedHashSet<>();

        void plan(String column, long distinct, List<String> vals) {
            counts.put(column, distinct);
            values.put(column, vals);
        }

        void planTruncated(String column, long distinct, List<String> vals) {
            plan(column, distinct, vals);
            truncatedColumns.add(column);
        }

        void planCountFailure(String column, ConnectorException e) {
            countFailures.put(column, e);
        }

        @Override
        public QueryResult query(String statement, QueryOptions opts) {
            statements.add(statement);
            options.add(opts);
            String column = firstBacktickedIdent(statement);
            boolean isCount = statement.startsWith("SELECT COUNT(DISTINCT");
            if (isCount) {
                ConnectorException failure = countFailures.get(column);
                if (failure != null) {
                    throw failure;
                }
                Long n = counts.get(column);
                assertNotNull(n, "测试没有为列 " + column + " 预置基数");
                return result(List.of(List.of((Object) n)), false, statement);
            }
            List<String> vals = values.getOrDefault(column, List.of());
            List<List<Object>> rows = vals.stream().map(v -> List.of((Object) v)).toList();
            return result(rows, truncatedColumns.contains(column), statement);
        }

        private static QueryResult result(List<List<Object>> rows, boolean truncated, String sql) {
            return new QueryResult(List.of("c"), rows, truncated, truncated ? "命中结果大小上限" : null, sql, 1L);
        }

        private static String firstBacktickedIdent(String sql) {
            int a = sql.indexOf('`');
            int b = sql.indexOf('`', a + 1);
            return a < 0 || b < 0 ? "" : sql.substring(a + 1, b);
        }

        @Override
        public void ping() {
            // 假会话不需要探活。
        }

        @Override
        public ReadOnlyVerdict verifyReadOnly() {
            return ReadOnlyVerdict.confirmed("假会话");
        }

        @Override
        public Set<Capability> probeCapabilities() {
            return Set.of(Capability.QUERY);
        }

        @Override
        public void close() {
            // 无状态。
        }
    }
}
