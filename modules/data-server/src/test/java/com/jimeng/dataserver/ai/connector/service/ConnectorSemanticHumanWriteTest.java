package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorGateway;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorSummary;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.tool.ConnectorToolExecutor;
import com.jimeng.dataserver.admin.common.UserNameResolver;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 缺陷 B4 的写入侧：五个 scope 现在都写得进去了，而<b>写进哪一行、挂什么锚点、留下什么痕</b>各有各的纪律。
 *
 * <p>这里每一条断言对应一个<b>不会报错</b>的失败：
 * <ul>
 *   <li>三列键填错 → 覆盖变成新增，同一件事在库里有两条各说各话的记录；</li>
 *   <li>FIELD / JOIN 没挂上锚点 → 这条断言<b>永不过期</b>，客户把列改成别的含义它照样注入；</li>
 *   <li>锚点拿实时结构算（而不是快照）→ 一落库就"已经过期"，下一次刷新立刻标 STALE，
 *       业务方明明刚答完、答案却一次都没被用上；</li>
 *   <li>覆盖 JOIN 时不留旧对端与旧 verified → 一条采样核过的关系被人改了对端之后<b>再也回不去</b>；</li>
 *   <li>「这两张表没关系」写成 {@code verified=REJECTED} → 一句人说的话伪装成一次采样结论，
 *       并被 {@code mergeInferred} 整组搬运进后面每一版推导；</li>
 *   <li>大小写不同的同一列落成两行 → 两条说明各说各话，注入时谁赢由查询顺序决定。</li>
 * </ul>
 */
class ConnectorSemanticHumanWriteTest {

    private static final Long CONN_ID = 100L;
    private static final String TENANT = "t1";

    /** 快照里的两张表。注意 {@code t_ord} 的壳是小写——下面专门有用大写去写的用例。 */
    private static final Map<String, Map<String, FieldDetail>> SNAPSHOT = Map.of(
            "t_ord", new LinkedHashMap<>(Map.of(
                    "id", new FieldDetail("id", "bigint", false, "主键", null),
                    "st", new FieldDetail("st", "tinyint", true, null, null),
                    "cust_id", new FieldDetail("cust_id", "bigint", true, null, null),
                    "pay_amt", new FieldDetail("pay_amt", "decimal(10,2)", true, "实付", null))),
            "t_cust", new LinkedHashMap<>(Map.of(
                    "id", new FieldDetail("id", "bigint", false, "主键", null))));

    private ConnectorSemanticMapper semanticMapper;
    private ConnectionMapper connectionMapper;
    private ConnectorSemanticService service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ConnectorSemantic.class);
        TableInfoHelper.initTableInfo(assistant, Connection.class);
    }

    @BeforeEach
    void setUp() {
        semanticMapper = mock(ConnectorSemanticMapper.class);
        connectionMapper = mock(ConnectionMapper.class);
        service = new ConnectorSemanticService(semanticMapper, connectionMapper);
        Connection c = new Connection();
        c.setId(CONN_ID);
        c.setTenantId(TENANT);
        when(connectionMapper.selectById(CONN_ID)).thenReturn(c);
        when(semanticMapper.update(any(), any())).thenReturn(1);
    }

    private ConnectorSemantic inserted() {
        ArgumentCaptor<ConnectorSemantic> cap = ArgumentCaptor.forClass(ConnectorSemantic.class);
        verify(semanticMapper).insert(cap.capture());
        return cap.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailOf(ConnectorSemantic row) {
        if (row.getDetailJson() == null) {
            return Map.of();
        }
        try {
            return CommonUtil.getObjectMapper().readValue(row.getDetailJson(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String json(Object o) {
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 捕获这次覆盖真正写下去的 set 子句。 */
    private LambdaUpdateWrapper<ConnectorSemantic> capturedUpdate() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemantic>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(semanticMapper).update(any(), cap.capture());
        return cap.getValue();
    }

    // ================================================================ 五类的三列与 detail 形状

    @Nested
    @DisplayName("五个 scope 各自的三列键与 detail 形状")
    class Shapes {

        /**
         * ★ 唯一键是 (tenant_id, connector_id, scope, object_name, field_name, term)。
         * 三列填错的表现不是报错，是写进<b>另一行</b>：覆盖变成新增。
         */
        @Test
        @DisplayName("OBJECT：object=表名，field / term 都是空串，不锚结构")
        void objectRow() {
            ConnectorSemantic row = service.annotateObject(CONN_ID, "t_ord", "订单主表", "u9", "张三", "tr-1");

            assertEquals(ConnectorSemanticService.SCOPE_OBJECT, row.getScope());
            assertEquals("t_ord", row.getObjectName());
            assertEquals("", row.getFieldName());
            assertEquals("", row.getTerm());
            assertEquals(ConnectorSemanticService.SOURCE_HUMAN, row.getSource());
            assertEquals(ConnectorSemanticService.ST_CONFIRMED, row.getStatus());
            // 表用途不锚结构：客户加一列并不改变「这张表是订单主表」这句话。
            assertEquals(ConnectorSemanticService.ANCHOR_NONE, row.getAnchorKind());
            assertNull(row.getAnchorHash());
            assertNull(row.getDetailJson());
            assertEquals("张三", row.getAnsweredName());
            assertEquals("tr-1", row.getTraceId());
        }

        @Test
        @DisplayName("FIELD：object=表名 + field=列名，term 空串，锚这一列自己的指纹")
        void fieldRow() {
            FieldDetail st = SNAPSHOT.get("t_ord").get("st");
            ConnectorSemantic row = service.annotateField(CONN_ID, "t_ord", "st", "1=已支付",
                    ConnectorSemanticService.fieldAnchor(st), "u9", "张三", null);

            assertEquals(ConnectorSemanticService.SCOPE_FIELD, row.getScope());
            assertEquals("t_ord", row.getObjectName());
            assertEquals("st", row.getFieldName());
            assertEquals("", row.getTerm());
            assertEquals(ConnectorSemanticService.ANCHOR_FIELD, row.getAnchorKind());
            assertEquals(ConnectorSemanticService.fieldAnchor(st), row.getAnchorHash());
        }

        @Test
        @DisplayName("JOIN：object / field 存【左】端，detail 必带 to_object + to_column，锚两端组合")
        void joinRow() {
            FieldDetail left = SNAPSHOT.get("t_ord").get("cust_id");
            FieldDetail right = SNAPSHOT.get("t_cust").get("id");
            ConnectorSemantic row = service.annotateJoin(CONN_ID, "t_ord", "cust_id", "t_cust", "id",
                    true, "订单的下单人", ConnectorSemanticService.joinAnchor(left, right), "u9", "张三", null);

            assertEquals(ConnectorSemanticService.SCOPE_JOIN, row.getScope());
            assertEquals("t_ord", row.getObjectName());
            assertEquals("cust_id", row.getFieldName());
            assertEquals("", row.getTerm());
            Map<String, Object> d = detailOf(row);
            assertEquals("t_cust", d.get(ConnectorSemanticService.KEY_TO_OBJECT));
            assertEquals("id", d.get(ConnectorSemanticService.KEY_TO_COLUMN));
            assertEquals(ConnectorSemanticService.HV_RELATED, d.get(ConnectorSemanticService.KEY_HUMAN_VERDICT));
            assertEquals(ConnectorSemanticService.ANCHOR_JOIN, row.getAnchorKind());
            assertEquals(ConnectorSemanticService.joinAnchor(left, right), row.getAnchorHash());
        }

        @Test
        @DisplayName("METRIC：object / field 都是空串，term=词条（口径挂在整条连接上，不挂某张表）")
        void metricRow() {
            ConnectorSemantic row = service.defineMetric(CONN_ID, "销售额", "实付金额合计，扣退款",
                    null, "u9", "张三", null);

            assertEquals(ConnectorSemanticService.SCOPE_METRIC, row.getScope());
            assertEquals("", row.getObjectName());
            assertEquals("", row.getFieldName());
            assertEquals("销售额", row.getTerm());
            assertEquals(ConnectorSemanticService.ANCHOR_NONE, row.getAnchorKind());
            assertNull(row.getAnchorHash());
        }

        /**
         * ★ CAVEAT 的 {@code evidence} 必须是 null：它不是一条断言，而是一句提醒 /
         * 一个还没有答案的问题。给它挂任何一个依据来源都是在说「这句话有某种出处」。
         */
        @Test
        @DisplayName("CAVEAT：term=词条，三列里只有 term 有值；★ evidence 为 null")
        void caveatRow() {
            ConnectorSemantic row = service.annotateCaveat(CONN_ID, "2023年前的数据",
                    "2023年前是从旧系统导入的，金额单位是分", List.of("t_ord"), "u9", "张三", null);

            assertEquals(ConnectorSemanticService.SCOPE_CAVEAT, row.getScope());
            assertEquals("", row.getObjectName());
            assertEquals("", row.getFieldName());
            assertEquals("2023年前的数据", row.getTerm());
            assertNull(row.getEvidence(), "CAVEAT 是一句提醒，不是断言，不该挂依据来源");
            assertEquals(List.of("t_ord"), detailOf(row).get(ConnectorSemanticService.KEY_APPLIES_TO));
            assertEquals(ConnectorSemanticService.ANCHOR_NONE, row.getAnchorKind());
        }

        /** 其余四类是人答的断言：依据来源只能是「人说的」，它恰恰是数据库里查不到答案的那一类。 */
        @Test
        @DisplayName("OBJECT / FIELD / JOIN / METRIC 的 evidence 一律 GUESS，绝不冒充 COMMENT / DATA")
        void assertionsAreGuess() {
            assertEquals(ConnectorSemanticService.EV_GUESS,
                    service.annotateObject(CONN_ID, "t_ord", "订单主表", null, null, null).getEvidence());
            assertEquals(ConnectorSemanticService.EV_GUESS,
                    service.annotateField(CONN_ID, "t_ord", "st", "1=已支付", "h", null, null, null).getEvidence());
            assertEquals(ConnectorSemanticService.EV_GUESS,
                    service.annotateJoin(CONN_ID, "t_ord", "cust_id", "t_cust", "id", true, "x", "h",
                            null, null, null).getEvidence());
            assertEquals(ConnectorSemanticService.EV_GUESS,
                    service.defineMetric(CONN_ID, "销售额", "扣退款", null, null, null, null).getEvidence());
        }
    }

    // ================================================================ 「这两张表没关系」

    @Nested
    @DisplayName("人说「这两张表没关系」")
    class Unrelated {

        /**
         * ★ 需求里点名的那一条：<b>绝不能</b>用 {@code verified=REJECTED} 表达。
         * 那一列的含义是「采样核过的结论」，而 {@code mergeInferred} 保留旧结论时是整组搬运的——
         * 一条人写的否定会伪装成一次采样结论，被抄进后面每一版推导，而且再也纠正不了。
         */
        @Test
        @DisplayName("★ 插入：human_verdict=UNRELATED，而 verified 仍是 NONE（不是 REJECTED）")
        void insertsHumanVerdictNotRejected() {
            ConnectorSemantic row = service.annotateJoin(CONN_ID, "t_ord", "cust_id", "t_cust", "id",
                    false, "同名但一个是内部单号、一个是渠道单号", "h", "u9", "张三", null);

            assertEquals(ConnectorSemanticService.HV_UNRELATED,
                    detailOf(row).get(ConnectorSemanticService.KEY_HUMAN_VERDICT));
            assertEquals(ConnectorSemanticService.V_NONE, row.getVerified());
            assertFalse(ConnectorSemanticService.V_REJECTED.equals(row.getVerified()),
                    "人说的否定绝不能写成 REJECTED——那是采样核过的结论");
        }

        /** 覆盖一条已经被采样验过的关系时同样不碰 verified：人说的话不该顶替一次实测。 */
        @Test
        @DisplayName("★ 覆盖：一次都不 set verified")
        void overwriteNeverSetsVerified() {
            when(semanticMapper.selectOne(any())).thenReturn(confirmedJoin());

            service.annotateJoin(CONN_ID, "t_ord", "cust_id", "t_cust", "id", false, "其实没关系",
                    "h2", "u9", "张三", null);

            assertFalse(capturedUpdate().getSqlSet().contains("verified"),
                    "覆盖路径的 set 子句里不许出现 verified：" + capturedUpdate().getSqlSet());
        }

        /**
         * ★ 覆盖 JOIN 的留痕必须带上<b>旧的对端</b>与<b>旧的 verified</b>。
         * 不带的后果很具体：一条采样核过的关系被人改了对端之后，原来验过的是哪一对、验成了什么，
         * 两样都没了——再也回不去。
         */
        @Test
        @DisplayName("★ 覆盖 JOIN：留痕带旧对端与旧 verified")
        void historyKeepsOldEndpointAndVerdict() {
            when(semanticMapper.selectOne(any())).thenReturn(confirmedJoin());

            ConnectorSemantic out = service.annotateJoin(CONN_ID, "t_ord", "cust_id", "t_member", "uid",
                    true, "改挂到会员表", "h2", "u9", "张三", null);

            Map<?, ?> entry = firstHistory(out);
            assertEquals(ConnectorSemanticService.V_CONFIRMED, entry.get("from_verified"));
            assertEquals("t_cust", entry.get("from_to_object"));
            assertEquals("id", entry.get("from_to_column"));
            assertNotNull(entry.get("from_detail"), "整份旧 detail 也要留着，取证材料不做时间机器但要齐");
            // 新值确实换成了新对端。
            assertEquals("t_member", detailOf(out).get(ConnectorSemanticService.KEY_TO_OBJECT));
        }

        private ConnectorSemantic confirmedJoin() {
            ConnectorSemantic j = new ConnectorSemantic();
            j.setId(7L);
            j.setTenantId(TENANT);
            j.setConnectorId(CONN_ID);
            j.setScope(ConnectorSemanticService.SCOPE_JOIN);
            j.setObjectName("t_ord");
            j.setFieldName("cust_id");
            j.setTerm("");
            j.setGloss("关联 t_cust.id");
            j.setSource(ConnectorSemanticService.SOURCE_INFERRED);
            j.setStatus(ConnectorSemanticService.ST_CONFIRMED);
            // 这条是真的采过样、对得上的：正因为如此，人改它的时候才必须把结论留下来。
            j.setVerified(ConnectorSemanticService.V_CONFIRMED);
            j.setAnchorKind(ConnectorSemanticService.ANCHOR_JOIN);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put(ConnectorSemanticService.KEY_TO_OBJECT, "t_cust");
            d.put(ConnectorSemanticService.KEY_TO_COLUMN, "id");
            d.put("containment", 0.99);
            j.setDetailJson(json(d));
            return j;
        }

        @SuppressWarnings("unchecked")
        private Map<?, ?> firstHistory(ConnectorSemantic row) {
            try {
                List<Object> hist = CommonUtil.getObjectMapper().readValue(row.getHistoryJson(), List.class);
                return (Map<?, ?>) hist.get(0);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ================================================================ 口径的列集合锚点

    @Nested
    @DisplayName("口径锚在算式真正引用到的那几列上")
    class MetricAnchor {

        @Test
        @DisplayName("给了依赖列：插入时写 COLUMN_SET + 列集合指纹，名单落进 detail")
        void insertsColumnSetAnchor() {
            List<ConnectorSemanticService.AnchorColumn> cols = List.of(
                    new ConnectorSemanticService.AnchorColumn("t_ord", "pay_amt",
                            ConnectorSemanticService.fieldAnchor(SNAPSHOT.get("t_ord").get("pay_amt"))));

            ConnectorSemantic row = service.defineMetric(CONN_ID, "销售额", "SUM(pay_amt)",
                    null, cols, SNAPSHOT, "u9", "张三", null);

            assertEquals(ConnectorSemanticService.ANCHOR_COLUMN_SET, row.getAnchorKind());
            assertEquals(ConnectorSemanticService.columnSetAnchor(cols, SNAPSHOT), row.getAnchorHash());
            List<?> stored = (List<?>) detailOf(row).get("anchor_columns");
            assertEquals(1, stored.size());
            assertEquals("t_ord", ((Map<?, ?>) stored.get(0)).get("object"));
            assertEquals("pay_amt", ((Map<?, ?>) stored.get(0)).get("column"));
        }

        /**
         * ★ 人重新确认一条已经过期的口径、但没重发依赖列时必须 re-baseline。
         * 只把 status 翻回 CONFIRMED、锚点还留着旧基线，下一次刷新重算依然对不上，
         * 会把人刚确认的口径<b>再一次</b>悄悄标成 STALE，而他完全不知道确认被撤销了。
         */
        @Test
        @DisplayName("★ 覆盖不带依赖列、既有是 COLUMN_SET：按既有名单在当前快照上重算（re-baseline）")
        void rebaselinesOnReconfirm() {
            ConnectorSemantic stale = staleColumnSetMetric("旧基线对不上了");
            when(semanticMapper.selectOne(any())).thenReturn(stale);

            ConnectorSemantic out = service.defineMetric(CONN_ID, "销售额", "还是这么算",
                    null, List.of(), SNAPSHOT, "u9", "张三", null);

            String expected = ConnectorSemanticService.columnSetAnchor(
                    List.of(new ConnectorSemanticService.AnchorColumn("t_ord", "pay_amt")), SNAPSHOT);
            assertEquals(ConnectorSemanticService.ANCHOR_COLUMN_SET, out.getAnchorKind());
            assertEquals(expected, out.getAnchorHash(), "重新确认之后锚点必须是【这一刻】的新基线");
            assertTrue(capturedUpdate().getSqlSet().contains("anchor_hash"));
        }

        /** 既有行本来就没有锚：给一条纯人工口径现编一个锚点，等于凭空给它加上一个会过期的理由。 */
        @Test
        @DisplayName("覆盖不带依赖列、既有是 NONE：锚点那两列一个都不 set")
        void leavesNoneAnchorAlone() {
            ConnectorSemantic plain = staleColumnSetMetric("纯人工口径");
            plain.setAnchorKind(ConnectorSemanticService.ANCHOR_NONE);
            plain.setAnchorHash(null);
            plain.setDetailJson(null);
            when(semanticMapper.selectOne(any())).thenReturn(plain);

            service.defineMetric(CONN_ID, "客户", "指下单人", null, List.of(), SNAPSHOT, "u9", "张三", null);

            String set = capturedUpdate().getSqlSet();
            assertFalse(set.contains("anchor_kind"), set);
            assertFalse(set.contains("anchor_hash"), set);
        }

        /** 覆盖路径对 METRIC 同样一次都不 set verified：那一列永远只由采样验证写。 */
        @Test
        @DisplayName("★ 口径覆盖也不 set verified")
        void metricOverwriteNeverSetsVerified() {
            when(semanticMapper.selectOne(any())).thenReturn(staleColumnSetMetric("旧口径"));

            service.defineMetric(CONN_ID, "销售额", "新口径", null, List.of(), SNAPSHOT, "u9", "张三", null);

            assertFalse(capturedUpdate().getSqlSet().contains("verified"), capturedUpdate().getSqlSet());
        }

        private ConnectorSemantic staleColumnSetMetric(String gloss) {
            ConnectorSemantic m = new ConnectorSemantic();
            m.setId(9L);
            m.setTenantId(TENANT);
            m.setConnectorId(CONN_ID);
            m.setScope(ConnectorSemanticService.SCOPE_METRIC);
            m.setObjectName("");
            m.setFieldName("");
            m.setTerm("销售额");
            m.setGloss(gloss);
            m.setSource(ConnectorSemanticService.SOURCE_HUMAN);
            m.setStatus(ConnectorSemanticService.ST_STALE);
            m.setVerified(ConnectorSemanticService.V_NONE);
            m.setAnchorKind(ConnectorSemanticService.ANCHOR_COLUMN_SET);
            m.setAnchorHash("过期的旧基线");
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("anchor_columns", List.of(Map.of("object", "t_ord", "column", "pay_amt",
                    "anchor", "写入那一刻的旧指纹")));
            m.setDetailJson(json(d));
            return m;
        }
    }

    // ================================================================ conn_annotate（执行器侧）

    /**
     * 执行器侧只测三件事：锚点是不是真的从<b>快照</b>算出来的、快照里找不到名字时拒不拒写、
     * 大小写不同的同一条会不会落到同一行。业务形状在上面几组里已经钉过了。
     */
    @Nested
    @DisplayName("conn_annotate：锚点与快照同源")
    class Tool {

        private ConnectorSemanticService serviceSpy;
        private ConnectorSnapshotColumnService snapshotService;
        private ConnectorToolExecutor executor;

        @BeforeEach
        void setUpTool() {
            ConnectorGateway gateway = mock(ConnectorGateway.class);
            when(gateway.listAuthorized()).thenReturn(List.of(new ConnectorSummary(
                    "crm", "CRM", "mysql", Set.of(Capability.DESCRIBE), "HEALTHY", null, true)));
            serviceSpy = mock(ConnectorSemanticService.class);
            snapshotService = mock(ConnectorSnapshotColumnService.class);
            when(snapshotService.columnsOf(any())).thenReturn(SNAPSHOT);
            ConnectionMapper mapper = mock(ConnectionMapper.class);
            Connection conn = new Connection();
            conn.setId(CONN_ID);
            conn.setName("crm");
            when(mapper.selectOne(any())).thenReturn(conn);
            when(serviceSpy.annotateField(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenAnswer(inv -> {
                        ConnectorSemantic r = new ConnectorSemantic();
                        r.setObjectName(inv.getArgument(1));
                        r.setFieldName(inv.getArgument(2));
                        r.setGloss(inv.getArgument(3));
                        return r;
                    });
            executor = new ConnectorToolExecutor(gateway, new ConnectorProperties(), serviceSpy, mapper,
                    mock(ConnectorSemanticMapper.class), mock(UserNameResolver.class),
                    mock(ConnectorRowPresenceService.class), snapshotService);
        }

        private Map<String, Object> annotate(Map<String, Object> args) {
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) executor.execute("conn_annotate", args);
            return out;
        }

        /** ★ 锚点必须是从快照那一列算出来的，而不是从 conn_describe 的实时结构。 */
        @Test
        @DisplayName("★ FIELD：锚点等于快照里那一列的 fieldAnchor")
        void fieldAnchorComesFromSnapshot() {
            Map<String, Object> out = annotate(Map.of("connector", "crm", "kind", "field",
                    "object", "t_ord", "field", "st", "note", "1=已支付"));

            assertEquals(Boolean.TRUE, out.get("saved"));
            ArgumentCaptor<String> anchor = ArgumentCaptor.forClass(String.class);
            verify(serviceSpy).annotateField(any(), any(), any(), any(), anchor.capture(), any(), any(), any());
            assertEquals(ConnectorSemanticService.fieldAnchor(SNAPSHOT.get("t_ord").get("st")),
                    anchor.getValue());
        }

        /**
         * ★ 大小写不同的同一条必须落到<b>同一行</b>：唯一键的排序规则是 utf8mb4_unicode_ci，
         * 不归一的话库里存的会是模型随手写的那个壳，两条说明各说各话。
         */
        @Test
        @DisplayName("★ 大小写不同的同一条：名字换成快照里的原写法")
        void foldsCaseToSnapshotSpelling() {
            annotate(Map.of("connector", "crm", "kind", "field",
                    "object", "T_ORD", "field", "ST", "note", "1=已支付"));

            ArgumentCaptor<String> obj = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> col = ArgumentCaptor.forClass(String.class);
            verify(serviceSpy).annotateField(any(), obj.capture(), col.capture(), any(), any(),
                    any(), any(), any());
            assertEquals("t_ord", obj.getValue());
            assertEquals("st", col.getValue());
        }

        /**
         * ★ 快照里找不到就拒写。一条挂不上锚点的 FIELD 断言是一条<b>永不过期</b>的断言：
         * 客户把这一列改成别的含义，它照样一轮一轮地注入。
         */
        @Test
        @DisplayName("★ 快照里没有这一列：拒写，并说清是哪个入参错了")
        void refusesUnknownColumn() {
            Map<String, Object> out = annotate(Map.of("connector", "crm", "kind", "field",
                    "object", "t_ord", "field", "没有这一列", "note", "x"));

            assertEquals(ConnectorErrorCode.NOT_FOUND.name().toLowerCase(java.util.Locale.ROOT),
                    out.get("error"));
            assertTrue(String.valueOf(out.get("detail")).contains("没有这一列"));
            verify(serviceSpy, never()).annotateField(any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("快照里没有这张表：同样拒写，一条语义都不落库")
        void refusesUnknownObject() {
            Map<String, Object> out = annotate(Map.of("connector", "crm", "kind", "object",
                    "object", "查无此表", "note", "x"));

            assertEquals(ConnectorErrorCode.NOT_FOUND.name().toLowerCase(java.util.Locale.ROOT),
                    out.get("error"));
            verify(serviceSpy, never()).annotateObject(any(), any(), any(), any(), any(), any());
        }

        /** 平台压根没有这条连接的结构快照：拒写，且必须说成「先刷新结构」而不是「你把表名写错了」。 */
        @Test
        @DisplayName("没有结构快照：拒写，并说明是平台还没刷新结构")
        void refusesWithoutSnapshot() {
            when(snapshotService.columnsOf(any())).thenReturn(Map.of());

            Map<String, Object> out = annotate(Map.of("connector", "crm", "kind", "object",
                    "object", "t_ord", "note", "订单主表"));

            assertEquals(ConnectorErrorCode.CONFIG_ERROR.name().toLowerCase(java.util.Locale.ROOT),
                    out.get("error"));
            assertTrue(String.valueOf(out.get("detail")).contains("结构快照"));
        }

        /** CAVEAT 挂在整条连接上、不碰任何表名列名，所以它是四类里唯一不需要快照的。 */
        @Test
        @DisplayName("CAVEAT 不读快照：没有结构快照时照样记得住")
        void caveatNeedsNoSnapshot() {
            when(snapshotService.columnsOf(any())).thenReturn(Map.of());
            ConnectorSemantic saved = new ConnectorSemantic();
            saved.setTerm("2023年前的数据");
            saved.setGloss("金额单位是分");
            when(serviceSpy.annotateCaveat(any(), any(), any(), any(), any(), any(), any())).thenReturn(saved);

            Map<String, Object> out = annotate(Map.of("connector", "crm", "kind", "caveat",
                    "term", "2023年前的数据", "note", "金额单位是分"));

            assertEquals(Boolean.TRUE, out.get("saved"));
            verify(snapshotService, never()).columnsOf(any());
        }

        /** 缺什么说什么：只回一句「参数不全」会让模型把四个参数轮流试一遍。 */
        @Test
        @DisplayName("kind=join 缺对端：报错点名缺的是哪几个参数")
        void namesMissingParams() {
            Map<String, Object> out = annotate(Map.of("connector", "crm", "kind", "join",
                    "object", "t_ord", "field", "cust_id", "note", "x"));

            assertEquals(ConnectorErrorCode.CONFIG_ERROR.name().toLowerCase(java.util.Locale.ROOT),
                    out.get("error"));
            String detail = String.valueOf(out.get("detail"));
            assertTrue(detail.contains("to_object") && detail.contains("to_column"), detail);
        }

        /** 未知 kind 要把四个合法取值列全，并指回 conn_define_metric——否则模型会拿本工具去写口径。 */
        @Test
        @DisplayName("未知 kind：列出四个合法取值，并指回 conn_define_metric")
        void rejectsUnknownKind() {
            Map<String, Object> out = annotate(Map.of("connector", "crm", "kind", "metric", "note", "x"));

            String detail = String.valueOf(out.get("detail"));
            assertTrue(detail.contains("conn_define_metric"), detail);
        }
    }
}
