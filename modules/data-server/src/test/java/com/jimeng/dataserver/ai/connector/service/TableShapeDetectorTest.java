package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.guard.ReadOnlySqlGuard;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.model.QueryResult;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorGateway;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryCapable;
import com.jimeng.persistence.entity.ConnectorSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 键值对表判定。这里钉的是<b>它不会把明细表翻成键值对表</b>——文档给的字面判据
 * 「名列 distinct 小 + 值列为数值」几乎每张明细表都满足，照字面实现是一次方向相反的全错。
 */
class TableShapeDetectorTest {

    private static final Long ID = 9L;

    private ConnectorGateway gateway;
    private ConnectorService connectorService;
    private TableShapeDetector detector;

    @BeforeEach
    void setUp() {
        gateway = mock(ConnectorGateway.class);
        connectorService = mock(ConnectorService.class);
        detector = new TableShapeDetector(gateway, connectorService);
        // 节流照常生效，只是调快：测试里不该真的每条睡 3 秒。
        ReflectionTestUtils.setField(detector, "statementsPerMinute", 6000);
        when(connectorService.dataTier(ID)).thenReturn(SemanticDataTier.DERIVED_STATS);
        TenantContext.set("t1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static FieldDetail col(String name, String type, String extra) {
        return new FieldDetail(name, type, true, null, extra);
    }

    private static ConnectorSchema table(String name, String type, FieldDetail... fields) {
        ConnectorSchema s = new ConnectorSchema();
        s.setConnectorId(ID);
        s.setObjectName(name);
        s.setObjectType(type);
        try {
            s.setDetailJson(CommonUtil.getObjectMapper().writeValueAsString(ConnectorSchemaService.toDetailMap(
                    new ObjectDetail(name, type, null, List.of(fields), Map.of()))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    /** 典型键值对表：自增主键、周期、指标编码（有索引）、指标值。 */
    private static ConnectorSchema kvTable() {
        return table("t_idx_d", "BASE TABLE",
                col("id", "bigint", "主键 / auto_increment"),
                col("stat_date", "date", "有索引"),
                col("metric_code", "varchar(64)", "有索引"),
                col("metric_value", "decimal(18,4)", null));
    }

    private static TableShapeDetector.Stats stats(long names, long rows, double avg, double sd, long numeric,
                                                  long intNames, long fracNames, Double minMag, Double maxMag) {
        return new TableShapeDetector.Stats(names, rows, avg, sd, numeric, numeric, intNames, fracNames, minMag, maxMag);
    }

    private static TableShapeDetector.ShapeVerdict classify(TableShapeDetector.Stats s, TableShapeDetector.Pair p,
                                                            TableShape guess) {
        return TableShapeDetector.classify(TableShapeDetector.ShapeVerdict.builder().objectName("t"), s, p, guess);
    }

    private static final TableShapeDetector.Pair UNAMBIGUOUS =
            new TableShapeDetector.Pair("metric_code", "metric_value", true, false);

    private ConnectorSession sessionReturning(QueryResult qr) {
        ConnectorSession session = mock(ConnectorSession.class, withSettings().extraInterfaces(QueryCapable.class));
        when(((QueryCapable) session).query(anyString(), any())).thenReturn(qr);
        return session;
    }

    @SuppressWarnings("unchecked")
    private void gatewayRuns(ConnectorSession session) {
        when(gateway.executeAsPlatform(eq(ID), eq(Capability.QUERY), anyString(), any()))
                .thenAnswer(inv -> ((ConnectorGateway.Op<Object>) inv.getArgument(3)).apply(session));
    }

    private static QueryResult row(Object... values) {
        return new QueryResult(List.of("name_n", "row_n", "avg_c", "sd_c", "nonnull_n", "numeric_n",
                "int_names", "frac_names", "min_mag", "max_mag"), List.of(new ArrayList<>(List.of(values))),
                false, null, "SELECT …", 3L);
    }

    // ================================================================ 结构预筛

    @Nested
    @DisplayName("结构预筛：不发一条语句")
    class PickPair {

        @Test
        void 典型键值对表挑中指标名与指标值() {
            TableShapeDetector.Pair p = TableShapeDetector.pickPair("t_idx_d", List.of(
                    col("id", "bigint", "主键 / auto_increment"), col("stat_date", "date", "有索引"),
                    col("metric_code", "varchar(64)", "有索引"), col("metric_value", "decimal(18,4)", null)));
            assertNotNull(p);
            assertEquals("metric_code", p.nameColumn());
            assertEquals("metric_value", p.valueColumn());
            assertTrue(p.numericValue());
            assertFalse(p.ambiguousName());
        }

        /** 多列度量是宽表：指标按列摊开，不是按行。 */
        @Test
        void 多个度量列的宽表不是候选() {
            assertNull(TableShapeDetector.pickPair("t_daily", List.of(
                    col("stat_date", "date", "有索引"), col("region", "varchar(32)", null),
                    col("gmv", "decimal(18,2)", null), col("orders", "int", null), col("uv", "int", null))));
        }

        /** 明细表照样挑得出一对（channel, amount），但名列有歧义——确认要走最严那一档。 */
        @Test
        void 明细表名列有多个候选时标记歧义() {
            TableShapeDetector.Pair p = TableShapeDetector.pickPair("orders", List.of(
                    col("id", "bigint", "主键"), col("user_id", "bigint", "有索引"),
                    col("status", "varchar(16)", null), col("channel", "varchar(32)", null),
                    col("amount", "decimal(12,2)", null)));
            assertNotNull(p);
            assertEquals("channel", p.nameColumn(), "取离值列最近的前一列");
            assertTrue(p.ambiguousName());
        }

        @Test
        void 值存成字符串的EAV形态() {
            TableShapeDetector.Pair p = TableShapeDetector.pickPair("t_attr", List.of(
                    col("entity_id", "bigint", "有索引"), col("attr_key", "varchar(64)", null),
                    col("attr_value", "varchar(255)", null)));
            assertNotNull(p);
            assertFalse(p.numericValue());
            assertEquals("attr_key", p.nameColumn());
        }

        /** 指标名是整数外键的形态刻意不测：它和「店铺 id + 销售额」长得一样，漏掉比误判便宜。 */
        @Test
        void 整数指标名不作为候选() {
            assertNull(TableShapeDetector.pickPair("t_fact", List.of(
                    col("entity_id", "bigint", "有索引"), col("metric_id", "int", "有索引"),
                    col("value", "decimal(18,4)", null))));
        }
    }

    // ================================================================ 判定

    @Nested
    @DisplayName("判定：只能确认，不能否定")
    class Classify {

        @Test
        void 模型说键值对且名列无歧义_满足必要条件即确认() {
            // 各指标都是计数、量级相近：没有单位混存证据，但模型的判断 + 必要条件足够确认。
            TableShapeDetector.ShapeVerdict v = classify(
                    stats(12, 12000, 1000, 50, 12000, 12, 0, 100d, 300d), UNAMBIGUOUS, TableShape.KEY_VALUE);
            assertEquals(TableShapeDetector.Outcome.KEY_VALUE, v.getOutcome());
            assertFalse(v.overridesModel());
        }

        /** ★ 本类最要紧的一条：一个维度 + 一个度量的表在必要条件上和键值对表一模一样，绝不能据此推翻模型。 */
        @Test
        void 模型说明细表_只满足必要条件_不推翻() {
            TableShapeDetector.ShapeVerdict v = classify(
                    stats(20, 20000, 1000, 100, 20000, 0, 20, 5000d, 900000d), UNAMBIGUOUS, TableShape.DETAIL);
            assertEquals(TableShapeDetector.Outcome.INCONCLUSIVE, v.getOutcome());
            assertFalse(v.overridesModel());
            assertTrue(v.getBasis().contains("不足以推翻模型"), v.getBasis());
        }

        @Test
        void 单位混存的证据足够时推翻模型() {
            // 计数型指标全是整数、金额和比率是小数，量级差上万倍。
            TableShapeDetector.ShapeVerdict v = classify(
                    stats(8, 8000, 1000, 10, 8000, 3, 5, 0.03d, 250000d), UNAMBIGUOUS, TableShape.DETAIL);
            assertEquals(TableShapeDetector.Outcome.KEY_VALUE, v.getOutcome());
            assertTrue(v.overridesModel());
        }

        /** 名列是本类挑的。模型说键值对、但名列有多个候选时，同样要最严那一档。 */
        @Test
        void 名列有歧义时模型说键值对也要单位混存证据() {
            TableShapeDetector.Pair ambiguous = new TableShapeDetector.Pair("region", "value", true, true);
            TableShapeDetector.ShapeVerdict v = classify(
                    stats(30, 30000, 1000, 10, 30000, 0, 0, 10d, 20d), ambiguous, TableShape.KEY_VALUE);
            assertEquals(TableShapeDetector.Outcome.INCONCLUSIVE, v.getOutcome());
        }

        @Test
        void 名列取值太多不是键值对表() {
            TableShapeDetector.ShapeVerdict v = classify(
                    stats(5000, 50000, 10, 1, 50000, 1, 1, 1d, 100d), UNAMBIGUOUS, TableShape.KEY_VALUE);
            assertEquals(TableShapeDetector.Outcome.NOT_KEY_VALUE, v.getOutcome());
        }

        @Test
        void 行数极不均匀更像业务维度() {
            TableShapeDetector.ShapeVerdict v = classify(
                    stats(4, 10000, 2500, 3760, 10000, 1, 3, 1d, 1000d), UNAMBIGUOUS, TableShape.DETAIL);
            assertEquals(TableShapeDetector.Outcome.NOT_KEY_VALUE, v.getOutcome());
        }

        @Test
        void 值列大多不是数值() {
            TableShapeDetector.Stats s = new TableShapeDetector.Stats(10L, 1000L, 100d, 1d, 1000L, 200L,
                    0L, 0L, null, null);
            assertEquals(TableShapeDetector.Outcome.NOT_KEY_VALUE,
                    classify(s, UNAMBIGUOUS, TableShape.KEY_VALUE).getOutcome());
        }

        /** 样本不足是「判不出」，不是「不是」：下一轮要重测。 */
        @Test
        void 样本太少判不出() {
            TableShapeDetector.ShapeVerdict v = classify(
                    stats(2, 10, 5, 0, 10, 1, 1, 1d, 100d), UNAMBIGUOUS, TableShape.KEY_VALUE);
            assertEquals(TableShapeDetector.Outcome.UNDECIDABLE, v.getOutcome());
        }
    }

    // ================================================================ SQL 能过护栏

    /**
     * ★ 网关执行的是 {@code ReadOnlySqlGuard} 解析后<b>重新序列化</b>的文本。生成器写了护栏认不得的语法，
     * 线上表现是每张表都「判不出来」，只在日志里有一行 error——所以这里拿真的护栏跑一遍。
     */
    @Test
    @DisplayName("测量语句能原样通过只读护栏（数值列与字符串值列两种）")
    void 测量语句通过只读护栏() {
        ReadOnlySqlGuard guard = new ReadOnlySqlGuard();
        String numeric = guard.check(detector.measureSql("t_idx_d", UNAMBIGUOUS), 1).effectiveSql();
        assertTrue(numeric.contains("STDDEV_POP"), numeric);
        assertTrue(numeric.contains("LIMIT 50000"), "内层采样上限不能在改写里丢掉：" + numeric);

        String text = guard.check(detector.measureSql("t_attr",
                new TableShapeDetector.Pair("attr_key", "attr_value", false, false)), 1).effectiveSql();
        assertTrue(text.toUpperCase().contains("REGEXP"), text);
        assertTrue(text.contains("[[:space:]]"), "正则里的字符类不能在序列化时被改写：" + text);
    }

    // ================================================================ 端到端

    @Test
    @DisplayName("第 1 档：一条语句都不发，说清表形态是模型推测")
    void 第一档不测() {
        when(connectorService.dataTier(ID)).thenReturn(SemanticDataTier.METADATA_ONLY);

        TableShapeDetector.ShapeRun run = detector.detect(ID,
                List.of(new TableShapeDetector.Target(kvTable(), TableShape.KEY_VALUE)), null);

        assertEquals(TableShapeDetector.OUT_TIER_BLOCKED, run.getOutcome());
        assertTrue(run.getNote().contains("未经数据测量"), run.getNote());
        verify(gateway, never()).executeAsPlatform(any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("测出键值对表：审计动作名是第 2 档那个、跳过视图、逐张回调")
    void 端到端测出键值对表() {
        gatewayRuns(sessionReturning(row(6L, 6000L, "1000", "20", 6000L, 6000L, 2L, 4L, "0.05", "180000")));
        List<TableShapeDetector.ShapeVerdict> seen = new ArrayList<>();

        TableShapeDetector.ShapeRun run = detector.detect(ID, List.of(
                new TableShapeDetector.Target(kvTable(), TableShape.KEY_VALUE),
                new TableShapeDetector.Target(table("v_kv", "VIEW", col("k", "varchar(8)", null),
                        col("v", "decimal(8,2)", null)), null)), v -> {
            seen.add(v);
            return true;
        });

        assertEquals(TableShapeDetector.OUT_RAN, run.getOutcome());
        assertEquals(1, run.getStatements());
        assertEquals(1, run.getSkippedViews());
        assertEquals(1, seen.size());
        assertEquals(TableShapeDetector.Outcome.KEY_VALUE, seen.get(0).getOutcome());
        assertEquals("metric_code", seen.get(0).measurementFragment().get("name_column"));
        verify(gateway).executeAsPlatform(eq(ID), eq(Capability.QUERY),
                eq(ConnectorAuditService.OP_SEMANTIC_PROBE), any());
    }

    /** 超时绝不能变成「不是键值对表」：那会让下一轮跳过它，一次慢查询永久关掉这张表的测量。 */
    @Test
    @DisplayName("超时判不出，不判「不是」")
    void 超时不判为不是() {
        when(gateway.executeAsPlatform(eq(ID), eq(Capability.QUERY), anyString(), any()))
                .thenThrow(ConnectorException.of(ConnectorErrorCode.TIMEOUT, "超时"));

        TableShapeDetector.ShapeRun run = detector.detect(ID,
                List.of(new TableShapeDetector.Target(kvTable(), TableShape.KEY_VALUE)), null);

        assertEquals(TableShapeDetector.Outcome.UNDECIDABLE, run.getVerdicts().get(0).getOutcome());
    }

    @Test
    @DisplayName("★ @Value 的内联默认与字段初始值一致")
    void inlineDefaultsMatchFieldInitializers() throws Exception {
        TableShapeDetector fresh = new TableShapeDetector(mock(ConnectorGateway.class), mock(ConnectorService.class));
        int checked = 0;
        for (Field f : TableShapeDetector.class.getDeclaredFields()) {
            Value v = f.getAnnotation(Value.class);
            if (v == null) {
                continue;
            }
            String expr = v.value();
            int colon = expr.indexOf(':');
            assertTrue(colon > 0, f.getName() + " 的 @Value 没有内联默认值");
            f.setAccessible(true);
            assertEquals(expr.substring(colon + 1, expr.length() - 1), String.valueOf(f.get(fresh)),
                    "字段 " + f.getName() + " 的内联默认与初始值不一致");
            checked++;
        }
        assertEquals(5, checked);
    }
}
