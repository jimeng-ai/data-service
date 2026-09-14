package com.jimeng.dataserver.ai.connector.tool;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorGateway;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticValueProfiler;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.cap.DescribeCapable;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code conn_describe} 的语义层注入：表关系的四种验证结论、fan-out 告警、列值域。
 *
 * <p>每条断言对应一个<b>不会报错</b>的失败：
 * <ul>
 *   <li>四种验证结论说成同一句话 → 模型对四种情况只能做同一件事，等于没验；</li>
 *   <li>1:N / N:N 被当成可以随便连 → join 放大行数，SUM 出来的金额凭空变大；</li>
 *   <li>「没去查这列的取值」被说成「这列没有枚举值」→ 模型放心写死一个永远查不到数据的条件；</li>
 *   <li>没有语义行时多出一个 {@code "joins": []} → 模型读成「这张表没有任何关联」。</li>
 * </ul>
 */
class ConnectorToolExecutorSemanticTest {

    private ConnectorSemanticService semanticService;
    private ConnectorToolExecutor executor;

    /** LambdaQueryWrapper 的列名解析要用 MP 的实体缓存；纯单测里没有 Spring，得自己灌一遍。 */
    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Connection.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemantic.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ConnectorGateway gateway = mock(ConnectorGateway.class);
        semanticService = mock(ConnectorSemanticService.class);
        ConnectionMapper connectionMapper = mock(ConnectionMapper.class);
        ConnectorSemanticMapper semanticMapper = mock(ConnectorSemanticMapper.class);

        when(gateway.execute(anyString(), any(Capability.class), anyString(), any()))
                .thenAnswer(inv -> ((ConnectorGateway.Op<Object>) inv.getArgument(3)).apply(new FakeSession()));
        Connection conn = new Connection();
        conn.setId(1L);
        conn.setName("crm");
        when(connectionMapper.selectOne(any())).thenReturn(conn);
        noSemantics();

        executor = new ConnectorToolExecutor(gateway, new ConnectorProperties(),
                semanticService, connectionMapper, semanticMapper);
    }

    // ------------------------------------------------------------------ 向后兼容

    /**
     * ★ 一条语义行都没有的连接器，返回的 JSON 必须和这套特性不存在时<b>一字不差</b>。
     *
     * <p>不是洁癖：{@code "joins": []} 读起来是「这张表没有关联」，{@code "semantic": null}
     * 读起来是「平台对这一列没有说明」——而事实是我们根本没有这条信息。
     * 空值在这里不是"没内容"，是"一句错话"。
     */
    @Test
    void noSemanticRows_payloadStaysByteIdentical() throws Exception {
        String json = CommonUtil.getObjectMapper().writeValueAsString(describe());

        assertThat(json).isEqualTo("{\"connector\":\"crm\",\"object\":\"t_ord\",\"type\":\"TABLE\","
                + "\"comment\":\"订单表\",\"fields\":["
                + "{\"name\":\"id\",\"type\":\"bigint\",\"nullable\":false,\"comment\":\"主键\",\"extra\":\"主键\"},"
                + "{\"name\":\"st\",\"type\":\"tinyint\",\"nullable\":true,\"comment\":null,\"extra\":null}],"
                + "\"extra\":{\"engine\":\"InnoDB\"}}");
    }

    /** 同一条纪律也要覆盖 conn_catalog：它是模型每轮都会调的那个，多一个 key 就是每轮多一份噪声。 */
    @Test
    @SuppressWarnings("unchecked")
    void noSemanticRows_catalogStaysByteIdentical() throws Exception {
        Object out = executor.execute("conn_catalog", Map.of("connector", "crm"));
        String json = CommonUtil.getObjectMapper().writeValueAsString(out);

        assertThat(json).isEqualTo("{\"connector\":\"crm\",\"object_kind\":\"TABLE\",\"count\":1,\"total\":1,"
                + "\"entries\":[{\"name\":\"t_ord\",\"type\":\"TABLE\",\"comment\":\"订单表\"}],"
                + "\"truncated\":false}");
        assertThat((Map<String, Object>) out).doesNotContainKeys("glossary", "ambiguities", "semantic_hint");
    }

    // ------------------------------------------------------------------ 四种验证结论

    @Test
    void fourVerificationStates_neverReadTheSame() {
        String confirmed = basisOf(join("order_id", ConnectorSemanticService.V_CONFIRMED,
                probe(1000, 970, 0.97d, "N:1", true, null)));
        String weak = basisOf(join("order_id", ConnectorSemanticService.V_WEAK,
                probe(1000, 800, 0.80d, "N:1", true, null)));
        String undecidable = basisOf(join("order_id", ConnectorSemanticService.V_UNDECIDABLE,
                probe(null, null, null, null, null, "这张表几乎是空的，样本不足以判定——这不等于这条关系是错的")));
        String none = basisOf(join("order_id", ConnectorSemanticService.V_NONE, base()));

        assertThat(List.of(confirmed, weak, undecidable, none))
                .as("四种结论四句话，说成一样模型就只能按一种方式处理")
                .doesNotHaveDuplicates();

        assertThat(confirmed).contains("采样 1000 个取值，命中 970（包含率 97.0%）");
        assertThat(confirmed).as("验过了就别再让模型白跑一次 COUNT").contains("不必");
        assertThat(weak).as("部分支持这件事必须说出来").contains("部分");
        assertThat(weak).as("WEAK 仍然要求先自己核一次").contains("COUNT");

        // ★ V_NONE = 没探查过，V_UNDECIDABLE = 探查过但判不出来。它们对下一步的指示不同，永远不许同形。
        assertThat(undecidable).contains("判不出来").contains("这张表几乎是空的");
        assertThat(undecidable).as("查过了就不能说成「未经数据验证」").doesNotContain("未经数据验证");
        assertThat(none).contains("未经数据验证");
    }

    @Test
    void rejectedJoin_isNotInjectedAtAll() {
        semanticsWithJoins(join("order_id", ConnectorSemanticService.V_REJECTED,
                probe(1000, 120, 0.12d, null, false, "左侧过半取值在右侧找不到，按错误关系处理")));

        assertThat(describe()).as("被数据否掉的关系一个字都不该进模型上下文").doesNotContainKey("joins");
    }

    @Test
    void verifiedValueIsHandedOverRaw_soTheModelCanBranchOnIt() {
        semanticsWithJoins(join("order_id", ConnectorSemanticService.V_WEAK, probe(500, 400, 0.8d, "N:1", true, null)));

        assertThat(firstJoin()).containsEntry("verified", ConnectorSemanticService.V_WEAK);
    }

    // ------------------------------------------------------------------ fan-out

    @Test
    void oneToMany_warnsThatJoinInflatesSum() {
        semanticsWithJoins(join("id", ConnectorSemanticService.V_CONFIRMED,
                probe(1000, 1000, 1.0d, "1:N", false, null)));

        Map<String, Object> j = firstJoin();
        assertThat(j).containsEntry("auto_joinable", false);
        assertThat(String.valueOf(j.get("fanout_warning")))
                .as("放大行数会让 SUM 凭空变大，而且不报错——这件事必须单独说")
                .contains("放大").contains("SUM");
    }

    @Test
    void manyToMany_warnsAndTellsItNotToJoinDirectly() {
        semanticsWithJoins(join("tag", ConnectorSemanticService.V_CONFIRMED,
                probe(1000, 990, 0.99d, "N:N", false, null)));

        assertThat(String.valueOf(firstJoin().get("fanout_warning")))
                .contains("N:N").contains("不要直接 join");
    }

    @Test
    void rightSideUnique_getsNoFanoutWarning() {
        semanticsWithJoins(join("order_id", ConnectorSemanticService.V_CONFIRMED,
                probe(1000, 1000, 1.0d, "N:1", true, null)));

        Map<String, Object> j = firstJoin();
        assertThat(j).containsEntry("auto_joinable", true);
        assertThat(j).as("右侧唯一时不该制造一条没用的告警").doesNotContainKey("fanout_warning");
    }

    @Test
    void unprobedJoin_saysNothingAboutUniqueness() {
        semanticsWithJoins(join("order_id", ConnectorSemanticService.V_NONE, base()));

        assertThat(firstJoin())
                .as("没测过右侧唯一性时不能给 auto_joinable——缺省会被读成「可以随便连」")
                .doesNotContainKey("auto_joinable");
    }

    // ------------------------------------------------------------------ 值域

    @Test
    void enumeratedColumn_listsTheValuesAndRefusesToExplainThem() {
        semanticsWithField("st", null, valueDomain(Map.of(
                "complete", true,
                "outcome", "ENUMERATED",
                "distinct_count", 4,
                "values", List.of("0", "1", "2", "3"),
                "note", SemanticValueProfiler.Outcome.ENUMERATED.modelNote())));

        Map<String, Object> vd = valueDomainOf("st");
        assertThat(vd).containsEntry("complete", true);
        assertThat(vd).containsEntry("values", List.of("0", "1", "2", "3"));
        assertThat(String.valueOf(vd.get("note")))
                .as("只给集合、绝不给含义：0/1/2/3 可以列，「0=待支付」永远不许出现")
                .contains("不知道");
    }

    @Test
    void highCardinalityColumn_neverEmitsAnEmptyValueArray() {
        semanticsWithField("st", null, valueDomain(Map.of(
                "complete", false,
                "outcome", "HIGH_CARDINALITY",
                "note", SemanticValueProfiler.Outcome.HIGH_CARDINALITY.modelNote())));

        Map<String, Object> vd = valueDomainOf("st");
        assertThat(vd).containsEntry("complete", false);
        assertThat(vd).as("空数组读起来就是「这列没有取值」").doesNotContainKey("values");
        assertThat(String.valueOf(vd.get("note"))).contains("【没有】");
    }

    /** ★ 「这列没有枚举值」和「没去查这列的枚举值」对模型的含义完全相反。 */
    @Test
    void tierDisabled_saysWeDidNotLook_notThatThereIsNothing() {
        semanticsWithField("st", null, valueDomain(SemanticValueProfiler.notEnabledFragment()));

        Map<String, Object> vd = valueDomainOf("st");
        assertThat(vd).containsEntry("complete", false);
        assertThat(vd).doesNotContainKey("values");
        assertThat(String.valueOf(vd.get("note"))).isEqualTo(SemanticValueProfiler.NOT_ENABLED_NOTE);
    }

    @Test
    void noteFallsBackToTheOutcome_whenItWasNotStored() {
        semanticsWithField("st", null, valueDomain(Map.of("complete", false, "outcome", "PII_BLOCKED")));

        assertThat(String.valueOf(valueDomainOf("st").get("note")))
                .as("九种结局各有一句写好的话，这里不该另编一套措辞")
                .isEqualTo(SemanticValueProfiler.Outcome.PII_BLOCKED.modelNote());
    }

    @Test
    void claimsCompleteButHasNoValues_isDowngradedAndSaysSo() {
        semanticsWithField("st", null, valueDomain(Map.of(
                "complete", true,
                "outcome", "ENUMERATED",
                "note", SemanticValueProfiler.Outcome.ENUMERATED.modelNote())));

        Map<String, Object> vd = valueDomainOf("st");
        assertThat(vd).containsEntry("complete", false);
        assertThat(String.valueOf(vd.get("note")))
                .as("下面一个取值都没有，就不能再挂着那句「以下是全部取值」")
                .doesNotContain("以下是");
    }

    @Test
    void staleColumn_keepsTheWarningButDropsTheValues() {
        ConnectorSemantic row = field("st", "状态码", valueDomain(Map.of(
                "complete", true, "outcome", "ENUMERATED", "values", List.of("0", "1"))));
        row.setStatus(ConnectorSemanticService.ST_STALE);
        semanticsWithFieldRow(row);

        Map<String, Object> vd = valueDomainOf("st");
        assertThat(vd).containsEntry("complete", false);
        assertThat(vd).as("列结构变了，当初采的那组值属于一个已经不存在的列").doesNotContainKey("values");
        assertThat(String.valueOf(vd.get("note"))).contains("不等于");
    }

    @Test
    void valueDomainSurvivesWithoutAGloss() {
        semanticsWithField("st", null, valueDomain(Map.of(
                "complete", true, "outcome", "ENUMERATED", "values", List.of("0", "1"))));

        assertThat(fieldOf("st"))
                .as("S4 采到了取值而 S2 没写出说明，这一列的取值集合不能跟着消失")
                .doesNotContainKey("semantic")
                .containsKey("value_domain");
    }

    // ------------------------------------------------------------------ 夹具

    @SuppressWarnings("unchecked")
    private Map<String, Object> describe() {
        Object out = executor.execute("conn_describe", Map.of("connector", "crm", "object", "t_ord"));
        assertThat(out).isInstanceOf(Map.class);
        Map<String, Object> m = (Map<String, Object>) out;
        assertThat(m).as("本用例不该走到错误分支").doesNotContainKey("error");
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstJoin() {
        List<Map<String, Object>> joins = (List<Map<String, Object>>) describe().get("joins");
        assertThat(joins).hasSize(1);
        return joins.get(0);
    }

    private String basisOf(ConnectorSemantic row) {
        semanticsWithJoins(row);
        return String.valueOf(firstJoin().get("basis"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fieldOf(String name) {
        List<Map<String, Object>> fields = (List<Map<String, Object>>) describe().get("fields");
        return fields.stream().filter(f -> name.equals(f.get("name"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> valueDomainOf(String field) {
        Object vd = fieldOf(field).get("value_domain");
        assertThat(vd).isInstanceOf(Map.class);
        return (Map<String, Object>) vd;
    }

    private void noSemantics() {
        when(semanticService.forObject(any(), anyString())).thenReturn(
                ConnectorSemanticService.ObjectSemantics.builder().fields(Map.of()).joins(List.of()).build());
        when(semanticService.forCatalog(any())).thenReturn(
                ConnectorSemanticService.CatalogSemantics.builder().objects(Map.of()).glossary(List.of()).build());
    }

    private void semanticsWithJoins(ConnectorSemantic... joins) {
        when(semanticService.forObject(any(), anyString())).thenReturn(
                ConnectorSemanticService.ObjectSemantics.builder()
                        .fields(Map.of()).joins(List.of(joins)).build());
    }

    private void semanticsWithField(String column, String gloss, String detailJson) {
        semanticsWithFieldRow(field(column, gloss, detailJson));
    }

    private void semanticsWithFieldRow(ConnectorSemantic row) {
        when(semanticService.forObject(any(), anyString())).thenReturn(
                ConnectorSemanticService.ObjectSemantics.builder()
                        .fields(Map.of(row.getFieldName(), row)).joins(List.of()).build());
    }

    private static ConnectorSemantic field(String column, String gloss, String detailJson) {
        ConnectorSemantic r = new ConnectorSemantic();
        r.setScope(ConnectorSemanticService.SCOPE_FIELD);
        r.setObjectName("t_ord");
        r.setFieldName(column);
        r.setGloss(gloss);
        r.setDetailJson(detailJson);
        r.setStatus(ConnectorSemanticService.ST_DRAFT);
        return r;
    }

    private static ConnectorSemantic join(String column, String verified, Map<String, Object> detail) {
        ConnectorSemantic r = new ConnectorSemantic();
        r.setScope(ConnectorSemanticService.SCOPE_JOIN);
        r.setObjectName("t_ord");
        r.setFieldName(column);
        r.setEvidence(ConnectorSemanticService.EV_NAME);
        r.setVerified(verified);
        r.setStatus(ConnectorSemanticService.ST_DRAFT);
        r.setDetailJson(json(detail));
        return r;
    }

    /** S2 写下的那一半：两端的对象与列。 */
    private static Map<String, Object> base() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("to_object", "t_ord_mst");
        d.put("to_column", "id");
        return d;
    }

    /** S3 探查之后 {@code detailPatch()} 并进去的那一半。 */
    private static Map<String, Object> probe(Integer sampleN, Integer matchN, Double containment,
                                             String cardinality, Boolean autoJoinable, String verifyNote) {
        Map<String, Object> d = base();
        if (cardinality != null) d.put("cardinality", cardinality);
        if (autoJoinable != null) d.put("auto_joinable", autoJoinable);
        if (sampleN != null) d.put("sample_n", sampleN);
        if (matchN != null) d.put("match_n", matchN);
        if (containment != null) d.put("containment", containment);
        if (verifyNote != null) d.put("verify_note", verifyNote);
        return d;
    }

    private static String valueDomain(Map<String, Object> fragment) {
        return json(Map.of(SemanticValueProfiler.DETAIL_KEY, fragment));
    }

    private static String json(Map<String, Object> m) {
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 只实现 describe 那一条路；其余方法本用例走不到。 */
    private static final class FakeSession implements ConnectorSession, DescribeCapable {

        @Override
        public com.jimeng.dataserver.ai.connector.model.CatalogView catalog() {
            return new com.jimeng.dataserver.ai.connector.model.CatalogView("TABLE",
                    List.of(new com.jimeng.dataserver.ai.connector.model.CatalogEntry("t_ord", "TABLE", "订单表")),
                    false, 1, null);
        }

        @Override
        public ObjectDetail describe(String object) {
            List<FieldDetail> fields = new ArrayList<>();
            fields.add(new FieldDetail("id", "bigint", false, "主键", "主键"));
            fields.add(new FieldDetail("st", "tinyint", true, null, null));
            return new ObjectDetail("t_ord", "TABLE", "订单表", fields, Map.of("engine", "InnoDB"));
        }

        @Override
        public void ping() {
        }

        @Override
        public ReadOnlyVerdict verifyReadOnly() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<Capability> probeCapabilities() {
            return Set.of(Capability.DESCRIBE);
        }

        @Override
        public void close() {
        }
    }
}
