package com.jimeng.dataserver.ai.connector.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.AnchorColumn;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SnapshotPresence;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.StaleResult;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ANCHOR_COLUMN_SET;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.KEY_ANCHOR_COLUMNS;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.KEY_STALE_COLUMNS;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SCOPE_METRIC;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SCOPE_OBJECT;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SOURCE_HUMAN;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SOURCE_INFERRED;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ST_CONFIRMED;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ST_DRAFT;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ST_STALE;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.V_NONE;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.columnSetAnchor;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.fieldAnchor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 口径锚在列集合上（{@code anchor_kind=COLUMN_SET}）之后，漂移处置的三值判定。
 *
 * <p>守的是缺陷 B5 的两侧：
 * <ul>
 *   <li><b>漏报</b>：口径的 SQL 片段引用的列被改名、删掉、或<b>被复用成别的含义</b>，口径却一直注入。
 *       列被删还算吵闹（查询报错）；列被复用成别的含义 = 查询成功、数字静默地错。</li>
 *   <li><b>误报</b>：口径依赖的表这次没被描述到（被挤出描述上限、目录只列了它），判定却把「没看」当成「没了」——
 *       人答的口径在一次与结构无关的刷新里被停用，管理台还去叫业务方重答。</li>
 * </ul>
 * 所以判定是三值的：{@code OK / STALE / NO_BASIS}，而 {@code NO_BASIS} 的定义就是「状态一个字都不许动」。
 */
class ConnectorSemanticColumnSetAnchorTest {

    private static final Long CONN_ID = 100L;
    private static final String TENANT = "t1";

    private static final FieldDetail ORD_ID = new FieldDetail("id", "bigint", false, "订单ID", null);
    private static final FieldDetail ORD_PAY_AMT = new FieldDetail("pay_amt", "decimal(12,2)", true, "实付金额", null);
    private static final FieldDetail RF_ID = new FieldDetail("id", "bigint", false, "退款ID", null);
    private static final FieldDetail RF_AMT = new FieldDetail("amt", "decimal(12,2)", true, "退款金额", null);

    /** ★ 最要命的那一种：列还在、名字类型都没动，只有注释改了——含义被复用了。 */
    private static final FieldDetail RF_AMT_REUSED =
            new FieldDetail("amt", "decimal(12,2)", true, "退款申请金额（含未打款）", null);

    private ConnectorSemanticMapper semanticMapper;
    private ConnectorSemanticService service;
    private long seq = 1;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ConnectorSemantic.class);
    }

    @BeforeEach
    void setUp() {
        semanticMapper = mock(ConnectorSemanticMapper.class);
        ConnectionMapper connectionMapper = mock(ConnectionMapper.class);
        service = new ConnectorSemanticService(semanticMapper, connectionMapper);
        when(semanticMapper.update(any(), any())).thenReturn(1);
        Connection c = new Connection();
        c.setId(CONN_ID);
        c.setTenantId(TENANT);
        when(connectionMapper.selectById(CONN_ID)).thenReturn(c);
    }

    // ================================================================ 夹具

    /** 快照：orders(id, pay_amt) + refunds(id, amt)。传进来的 amt 决定 refunds.amt 这次长什么样。 */
    private static Map<String, Map<String, FieldDetail>> snapshot(FieldDetail... refundCols) {
        Map<String, Map<String, FieldDetail>> m = new LinkedHashMap<>();
        m.put("orders", cols(ORD_ID, ORD_PAY_AMT));
        m.put("refunds", cols(refundCols));
        return m;
    }

    private static Map<String, Map<String, FieldDetail>> ordersOnly() {
        Map<String, Map<String, FieldDetail>> m = new LinkedHashMap<>();
        m.put("orders", cols(ORD_ID, ORD_PAY_AMT));
        return m;
    }

    private static Map<String, FieldDetail> cols(FieldDetail... fs) {
        Map<String, FieldDetail> m = new LinkedHashMap<>();
        for (FieldDetail f : fs) {
            m.put(f.name(), f);
        }
        return m;
    }

    /** 口径依赖的两列：orders.pay_amt 与 refunds.amt，各自带上写入那一刻的列指纹。 */
    private static List<AnchorColumn> dependsOn() {
        return List.of(new AnchorColumn("orders", ORD_PAY_AMT.name(), fieldAnchor(ORD_PAY_AMT)),
                new AnchorColumn("refunds", RF_AMT.name(), fieldAnchor(RF_AMT)));
    }

    /**
     * 一条锚在列集合上的口径行。<b>挂在整条连接上</b>（object_name / field_name 是空串），
     * 与 {@code defineMetric} 写下来的形状一致——这正是「直接给 METRIC 行挂 COLUMN_SET」不能去取左列的原因。
     */
    private ConnectorSemantic metricRow(List<AnchorColumn> columns, String status) {
        ConnectorSemantic r = base(SCOPE_METRIC, "", "", status);
        r.setTerm("净销售额");
        r.setSource(SOURCE_HUMAN);
        r.setGloss("SUM(orders.pay_amt) - SUM(refunds.amt)");
        r.setAnchorKind(ANCHOR_COLUMN_SET);
        r.setAnchorHash(columnSetAnchor(columns, snapshot(RF_ID, RF_AMT)));
        r.setDetailJson(json(detailWith(columns)));
        return r;
    }

    private static Map<String, Object> detailWith(List<AnchorColumn> columns) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("sql_fragment", "SUM(orders.pay_amt) - SUM(refunds.amt)");
        d.put(KEY_ANCHOR_COLUMNS, columns.stream().map(AnchorColumn::toDetailMap).toList());
        return d;
    }

    private ConnectorSemantic base(String scope, String obj, String field, String status) {
        ConnectorSemantic r = new ConnectorSemantic();
        r.setId(seq++);
        r.setTenantId(TENANT);
        r.setConnectorId(CONN_ID);
        r.setScope(scope);
        r.setObjectName(obj);
        r.setFieldName(field);
        r.setTerm("");
        r.setSource(SOURCE_INFERRED);
        r.setStatus(status);
        r.setVerified(V_NONE);
        return r;
    }

    private void givenRows(ConnectorSemantic... rows) {
        when(semanticMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(rows)));
    }

    /** 目录列了这几张表、并描述到了 described 里的那些。 */
    private static SnapshotPresence presence(List<String> described, String... listed) {
        return SnapshotPresence.of(described, Arrays.asList(listed), false);
    }

    private static String json(Object o) {
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(ConnectorSemantic r) {
        try {
            return CommonUtil.getObjectMapper().readValue(r.getDetailJson(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> staleColumns(ConnectorSemantic r) {
        Object v = detail(r).get(KEY_STALE_COLUMNS);
        return v == null ? List.of() : (List<String>) v;
    }

    // ================================================================ 指纹本身

    @Nested
    @DisplayName("列集合指纹")
    class Anchor {

        /**
         * ★ 不排序就是「每次刷新都假过期」：列集合来自 detail_json，写入那一刻的书写顺序不保证稳定，
         * 而 hash 只要跟着顺序变，同一组没动过的列每一轮都会算出一个对不上的值。
         */
        @Test
        @DisplayName("★ 列的先后顺序不改变指纹")
        void 顺序不同指纹相同() {
            List<AnchorColumn> a = dependsOn();
            List<AnchorColumn> b = List.of(a.get(1), a.get(0));

            assertEquals(columnSetAnchor(a, snapshot(RF_ID, RF_AMT)), columnSetAnchor(b, snapshot(RF_ID, RF_AMT)));
        }

        @Test
        @DisplayName("同一列写两遍不改变指纹；表名列名只改大小写同样不改变指纹")
        void 去重与大小写折叠() {
            List<AnchorColumn> once = dependsOn();
            List<AnchorColumn> twice = List.of(once.get(0), once.get(1), once.get(0));
            List<AnchorColumn> shouted = List.of(new AnchorColumn("ORDERS", "PAY_AMT"), new AnchorColumn("Refunds", "Amt"));

            assertEquals(columnSetAnchor(once, snapshot(RF_ID, RF_AMT)), columnSetAnchor(twice, snapshot(RF_ID, RF_AMT)));
            assertEquals(columnSetAnchor(once, snapshot(RF_ID, RF_AMT)), columnSetAnchor(shouted, snapshot(RF_ID, RF_AMT)));
        }

        @Test
        @DisplayName("任一列改了注释指纹就变；列找不到 / 集合为空则算不出来（null）")
        void 变化与算不出来() {
            assertNotEquals(columnSetAnchor(dependsOn(), snapshot(RF_ID, RF_AMT)),
                    columnSetAnchor(dependsOn(), snapshot(RF_ID, RF_AMT_REUSED)),
                    "列被复用成别的含义：名字类型都没动，只有注释变了，指纹必须跟着变");
            assertNull(columnSetAnchor(dependsOn(), snapshot(RF_ID)), "列不在了 = 算不出来");
            assertNull(columnSetAnchor(List.of(), snapshot(RF_ID, RF_AMT)));
            assertNull(columnSetAnchor(null, snapshot(RF_ID, RF_AMT)));
        }
    }

    // ================================================================ STALE

    @Nested
    @DisplayName("结构真的变了：过期，并说得出是哪几列")
    class Stale {

        /** 缺陷 B5 点名的那一种：查询照跑、行数照出、数字静默地错。 */
        @Test
        @DisplayName("★ 列被复用成别的含义（只改注释）：口径过期，并记下是哪一列")
        void 列改注释即过期() {
            ConnectorSemantic m = metricRow(dependsOn(), ST_CONFIRMED);
            givenRows(m);

            StaleResult r = service.applyDrift(CONN_ID, snapshot(RF_ID, RF_AMT_REUSED),
                    presence(List.of("orders", "refunds"), "orders", "refunds"), List.of());

            assertEquals(1, r.staled());
            assertEquals(ST_STALE, m.getStatus());
            assertEquals(List.of("refunds.amt"), staleColumns(m), "只有这一列变了，另一列不进名单");
        }

        @Test
        @DisplayName("列被删掉：口径过期，名单记下没了的那一列")
        void 列被删即过期() {
            ConnectorSemantic m = metricRow(dependsOn(), ST_CONFIRMED);
            givenRows(m);

            StaleResult r = service.applyDrift(CONN_ID, snapshot(RF_ID),
                    presence(List.of("orders", "refunds"), "orders", "refunds"), List.of());

            assertEquals(1, r.staled());
            assertEquals(ST_STALE, m.getStatus());
            assertEquals(List.of("refunds.amt"), staleColumns(m));
        }

        /**
         * 表都没了，它的列当然也没了——这是确定的结论，不是「没看到」。
         * 与 {@code LISTED_ONLY} 的区别正是这一条：{@code ABSENT} 有依据，照常过期。
         */
        @Test
        @DisplayName("依赖的表 ABSENT（目录完整、没列出它）：照常过期")
        void 表没了即过期() {
            ConnectorSemantic m = metricRow(dependsOn(), ST_CONFIRMED);
            givenRows(m);

            StaleResult r = service.applyDrift(CONN_ID, ordersOnly(),
                    presence(List.of("orders"), "orders"), List.of());

            assertEquals(1, r.staled());
            assertEquals(ST_STALE, m.getStatus());
            assertEquals(List.of("refunds.amt"), staleColumns(m));
        }

        /** 写入侧没存每列指纹时（列集合只有表名列名），照样判得出变了，只是名单点不出改了注释的那一列。 */
        @Test
        @DisplayName("列集合没存每列指纹：仍然判过期，名单只报得出已经没了的列")
        void 没存每列指纹也判得出过期() {
            List<AnchorColumn> bare = List.of(new AnchorColumn("orders", ORD_PAY_AMT.name()),
                    new AnchorColumn("refunds", RF_AMT.name()));
            ConnectorSemantic m = metricRow(bare, ST_CONFIRMED);
            givenRows(m);

            StaleResult r = service.applyDrift(CONN_ID, snapshot(RF_ID, RF_AMT_REUSED),
                    presence(List.of("orders", "refunds"), "orders", "refunds"), List.of());

            assertEquals(1, r.staled());
            assertEquals(ST_STALE, m.getStatus());
            assertTrue(staleColumns(m).isEmpty(), "点不出名字，但绝不能因此放过这次过期");
        }
    }

    // ================================================================ OK / 复活

    @Nested
    @DisplayName("结构没变 / 改回来了")
    class Stable {

        /** ★ 只要有一次假过期，人答的口径就被停用一轮；这条守的是「没动过的列不许判过期」。 */
        @Test
        @DisplayName("★ detail 里列的顺序与写入时相反：不产生假过期，一次写都不发生")
        void 顺序重排不假过期() {
            List<AnchorColumn> asWritten = dependsOn();
            ConnectorSemantic m = metricRow(asWritten, ST_CONFIRMED);
            // 重新确认时列被换了个顺序写回 detail，锚点还是当初那个值。
            m.setDetailJson(json(detailWith(List.of(asWritten.get(1), asWritten.get(0)))));
            givenRows(m);

            StaleResult r = service.applyDrift(CONN_ID, snapshot(RF_ID, RF_AMT),
                    presence(List.of("orders", "refunds"), "orders", "refunds"), List.of());

            assertEquals(0, r.staled() + r.revived());
            assertEquals(ST_CONFIRMED, m.getStatus());
            verify(semanticMapper, never()).update(any(), any());
        }

        /**
         * 重新确认（写入侧按新结构重算锚点、重新挂上）之后，下一次刷新必须认账：
         * 结构与新锚点对得上 → 撤销 STALE、清掉「是哪几列变了」。
         * 这条与 {@code flippedStatus} 的「结构改回来了要能撤销 STALE」是同一条路，对 COLUMN_SET 同样成立。
         */
        @Test
        @DisplayName("★ 重新确认后按新结构重挂锚点：STALE 被撤销，变化列名单清空")
        void 重新确认后re_baseline() {
            ConnectorSemantic m = metricRow(dependsOn(), ST_STALE);
            List<AnchorColumn> rebased = List.of(
                    new AnchorColumn("orders", ORD_PAY_AMT.name(), fieldAnchor(ORD_PAY_AMT)),
                    new AnchorColumn("refunds", RF_AMT_REUSED.name(), fieldAnchor(RF_AMT_REUSED)));
            Map<String, Object> d = detailWith(rebased);
            d.put(KEY_STALE_COLUMNS, List.of("refunds.amt"));
            m.setDetailJson(json(d));
            m.setAnchorHash(columnSetAnchor(rebased, snapshot(RF_ID, RF_AMT_REUSED)));
            givenRows(m);

            StaleResult r = service.applyDrift(CONN_ID, snapshot(RF_ID, RF_AMT_REUSED),
                    presence(List.of("orders", "refunds"), "orders", "refunds"), List.of());

            assertEquals(1, r.revived());
            assertEquals(ST_CONFIRMED, m.getStatus(), "HUMAN 的行复活回 CONFIRMED");
            assertTrue(staleColumns(m).isEmpty(), "已经撤销的过期不许在 detail 里留个尾巴");
        }
    }

    // ================================================================ NO_BASIS

    @Nested
    @DisplayName("★ 没有依据：状态一个字都不许动")
    class NoBasis {

        /**
         * 快照最多描述 200 张表。口径引用的表被挤出去、只剩目录里列着它——
         * 这时候没有列可比。判过期 = 人答的口径在一次与结构无关的刷新里被停用；
         * 判没变 = 一条真该过期的行被复活。两条都错，所以什么都不做。
         */
        @Test
        @DisplayName("★ 依赖的表只被列出、没描述到：既不过期也不复活，一次写都不发生")
        void 表只被列出未描述() {
            ConnectorSemantic ok = metricRow(dependsOn(), ST_CONFIRMED);
            ConnectorSemantic stale = metricRow(dependsOn(), ST_STALE);
            String okDetail = ok.getDetailJson();
            String staleDetail = stale.getDetailJson();
            givenRows(ok, stale);

            StaleResult r = service.applyDrift(CONN_ID, ordersOnly(),
                    presence(List.of("orders"), "orders", "refunds"), List.of());

            assertEquals(0, r.staled() + r.revived() + r.concurrentSkips());
            assertEquals(ST_CONFIRMED, ok.getStatus());
            assertEquals(ST_STALE, stale.getStatus(), "复活同样是一个结论，而这次手上没有下结论的材料");
            assertEquals(okDetail, ok.getDetailJson(), "detail 也一个字不动");
            assertEquals(staleDetail, stale.getDetailJson());
            verify(semanticMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("目录被截断、依赖的表没列出（UNKNOWN）：同样一个字不碰")
        void 目录截断下的未知表() {
            ConnectorSemantic m = metricRow(dependsOn(), ST_CONFIRMED);
            givenRows(m);

            StaleResult r = service.applyDrift(CONN_ID, ordersOnly(),
                    SnapshotPresence.of(List.of("orders"), List.of("orders"), true), List.of());

            assertEquals(0, r.staled() + r.revived());
            assertEquals(ST_CONFIRMED, m.getStatus());
            verify(semanticMapper, never()).update(any(), any());
        }
    }

    // ================================================================ 列集合坏掉

    @Nested
    @DisplayName("列集合坏掉：是我们自己的 bug，不许拿它停用人答的口径")
    class Broken {

        private final Logger logger = (Logger) LoggerFactory.getLogger(ConnectorSemanticService.class);
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        @BeforeEach
        void attach() {
            appender.start();
            logger.addAppender(appender);
        }

        @AfterEach
        void detach() {
            logger.detachAppender(appender);
            appender.stop();
        }

        private void 一个字不动并且warn(ConnectorSemantic m) {
            givenRows(m);
            String before = m.getDetailJson();

            StaleResult r = service.applyDrift(CONN_ID, snapshot(RF_ID, RF_AMT_REUSED),
                    presence(List.of("orders", "refunds"), "orders", "refunds"), List.of());

            assertEquals(0, r.staled() + r.revived());
            assertEquals(ST_CONFIRMED, m.getStatus());
            assertEquals(before, m.getDetailJson());
            verify(semanticMapper, never()).update(any(), any());
            assertTrue(appender.list.stream().anyMatch(e -> "WARN".equals(e.getLevel().toString())
                            && e.getFormattedMessage().contains(KEY_ANCHOR_COLUMNS)),
                    "读不出列集合必须留一条 WARN，否则这条口径会无声地永远判不了");
        }

        @Test
        @DisplayName("★ 列集合整个缺失：NO_BASIS + warn")
        void 列集合缺失() {
            ConnectorSemantic m = metricRow(dependsOn(), ST_CONFIRMED);
            Map<String, Object> d = new LinkedHashMap<>(detail(m));
            d.remove(KEY_ANCHOR_COLUMNS);
            m.setDetailJson(json(d));
            一个字不动并且warn(m);
        }

        @Test
        @DisplayName("列集合不是对象列表（写成了字符串）：NO_BASIS + warn")
        void 列集合形状不对() {
            ConnectorSemantic m = metricRow(dependsOn(), ST_CONFIRMED);
            Map<String, Object> d = new LinkedHashMap<>(detail(m));
            d.put(KEY_ANCHOR_COLUMNS, List.of("orders.pay_amt", "refunds.amt"));
            m.setDetailJson(json(d));
            一个字不动并且warn(m);
        }

        @Test
        @DisplayName("列集合里的元素缺列名：NO_BASIS + warn")
        void 元素缺字段() {
            ConnectorSemantic m = metricRow(dependsOn(), ST_CONFIRMED);
            Map<String, Object> d = new LinkedHashMap<>(detail(m));
            d.put(KEY_ANCHOR_COLUMNS, List.of(Map.of("object", "orders")));
            m.setDetailJson(json(d));
            一个字不动并且warn(m);
        }

        @Test
        @DisplayName("detail_json 整个解析不了：NO_BASIS + warn")
        void detail坏掉() {
            ConnectorSemantic m = metricRow(dependsOn(), ST_CONFIRMED);
            m.setDetailJson("{不是 json");
            一个字不动并且warn(m);
        }
    }

    // ================================================================ 挂在表上的 COLUMN_SET 行

    /**
     * COLUMN_SET 现在只由口径产生（口径挂在整条连接上，走第二遍），
     * 但判定不该只对第二遍成立：挂在某张表上的行同样要能三值分流。
     */
    @Nested
    @DisplayName("挂在具体表上的 COLUMN_SET 行：第一遍同样三值分流")
    class OnObject {

        private ConnectorSemantic objectRow(String status) {
            ConnectorSemantic r = base(SCOPE_OBJECT, "orders", "", status);
            r.setAnchorKind(ANCHOR_COLUMN_SET);
            r.setAnchorHash(columnSetAnchor(dependsOn(), snapshot(RF_ID, RF_AMT)));
            r.setDetailJson(json(detailWith(dependsOn())));
            return r;
        }

        @Test
        @DisplayName("依赖的另一张表只被列出：不过期也不复活")
        void 另一张表没描述到() {
            ConnectorSemantic row = objectRow(ST_DRAFT);
            givenRows(row);

            StaleResult r = service.applyDrift(CONN_ID, ordersOnly(),
                    presence(List.of("orders"), "orders", "refunds"), List.of());

            assertEquals(0, r.staled() + r.revived());
            assertEquals(ST_DRAFT, row.getStatus());
            verify(semanticMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("依赖的列变了：过期，并把变化的列写进 detail")
        void 依赖的列变了() {
            ConnectorSemantic row = objectRow(ST_DRAFT);
            givenRows(row);

            StaleResult r = service.applyDrift(CONN_ID, snapshot(RF_ID, RF_AMT_REUSED),
                    presence(List.of("orders", "refunds"), "orders", "refunds"), List.of());

            assertEquals(1, r.staled());
            assertEquals(ST_STALE, row.getStatus());
            assertEquals(List.of("refunds.amt"), staleColumns(row));
            assertTrue(r.removedImpact().isEmpty(), "没有表消失，影响面为空");
        }
    }
}
