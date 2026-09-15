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
import com.jimeng.dataserver.ai.connector.service.SemanticJoinValidator;
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
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code conn_describe} 的语义层注入：表级说明与表形态、关系的两个桶、fan-out 告警、列值域。
 *
 * <p>每条断言对应一个<b>不会报错</b>的失败：
 * <ul>
 *   <li>三种可直接用的验证结论说成同一句话 → 模型对三种情况只能做同一件事，等于没验；</li>
 *   <li>WEAK / 多态外键 / 复合键混进 joins → 模型按单列连，连出别的类型里恰好同号的错行；</li>
 *   <li>键值对表没有聚合告警 → 不同指标的值被加在一起，SUM 出一个像样的错数；</li>
 *   <li>1:N / N:N 被当成可以随便连 → join 放大行数，SUM 出来的金额凭空变大；</li>
 *   <li>「没去查这列的取值」被说成「这列没有枚举值」→ 模型放心写死一个永远查不到数据的条件；</li>
 *   <li>没有语义行时多出一个 {@code "joins": []} → 模型读成「这张表没有任何关联」。</li>
 * </ul>
 */
class ConnectorToolExecutorSemanticTest {

    /** 这套特性不存在时 conn_describe 的样子。没有语义行的连接器必须一字不差地回到这里。 */
    private static final String BASELINE_DESCRIBE_JSON = "{\"connector\":\"crm\",\"object\":\"t_ord\",\"type\":\"TABLE\","
            + "\"comment\":\"订单表\",\"fields\":["
            + "{\"name\":\"id\",\"type\":\"bigint\",\"nullable\":false,\"comment\":\"主键\",\"extra\":\"主键\"},"
            + "{\"name\":\"st\",\"type\":\"tinyint\",\"nullable\":true,\"comment\":null,\"extra\":null}],"
            + "\"extra\":{\"engine\":\"InnoDB\"}}";

    private ConnectorSemanticService semanticService;
    private ConnectorSemanticMapper semanticMapper;
    private ConnectorToolExecutor executor;

    /** FakeSession 返回的实时列。键值对表的用例会换成自己的一套。 */
    private List<FieldDetail> liveFields = List.of(
            new FieldDetail("id", "bigint", false, "主键", "主键"),
            new FieldDetail("st", "tinyint", true, null, null));

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
        semanticMapper = mock(ConnectorSemanticMapper.class);

        when(gateway.execute(anyString(), any(Capability.class), anyString(), any()))
                .thenAnswer(inv -> ((ConnectorGateway.Op<Object>) inv.getArgument(3)).apply(new FakeSession()));
        Connection conn = new Connection();
        conn.setId(1L);
        conn.setName("crm");
        when(connectionMapper.selectOne(any())).thenReturn(conn);
        noSemantics();
        // 带取值的那些用例描述的是「第 3 档开着」的连接。不桩这一句，Mockito 的默认 false 会让它们
        // 全部走「档位没开放」那条路——断言照样能过一部分，测的却已经不是它们名字里说的那件事。
        when(semanticService.allowsSampleValues(any())).thenReturn(true);

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

        assertThat(json).isEqualTo(BASELINE_DESCRIBE_JSON);
        verify(semanticService, never()).allowsSampleValues(any());
    }

    /** 表级说明是叠加的注解：读它的那一趟坏了，describe 必须和没有语义层时一模一样，而不是整个报错。 */
    @Test
    void objectRowLookupFails_payloadStaysByteIdentical() throws Exception {
        when(semanticMapper.selectList(any())).thenThrow(new IllegalStateException("db down"));

        String json = CommonUtil.getObjectMapper().writeValueAsString(describe());

        assertThat(json).isEqualTo(BASELINE_DESCRIBE_JSON);
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

    // ------------------------------------------------------------------ 表级说明

    /** ★ catalog 里截到 80 字，完整版必须真的在 describe 出现，而且和客户自己的注释并排、不合并。 */
    @Test
    void objectGloss_isGivenInFull_besideTheCustomersOwnComment() {
        String gloss = "订单主表：一行=一个订单头。" + "金额含税不含运费，取消的订单不会被物理删除，".repeat(5);
        assertThat(gloss.length()).as("前提：比 catalog 的 80 字长").isGreaterThan(80);
        objectRow(gloss, ConnectorSemanticService.ST_DRAFT, null);

        Map<String, Object> out = describe();

        assertThat(out).containsEntry("semantic", gloss.trim());
        assertThat(out).as("客户库自己的注释原样保留，不被平台说明覆盖").containsEntry("comment", "订单表");
        assertThat(out).containsEntry("semantic_status", ConnectorSemanticService.ST_DRAFT);
        assertThat(out).doesNotContainKey("semantic_note").containsKey("semantic_hint");
        assertThat(out).as("没有形态就不出形态").doesNotContainKeys("table_shape", "table_shape_warning");
    }

    @Test
    void staleObjectRow_carriesTheDriftMarker() {
        objectRow("订单主表", ConnectorSemanticService.ST_STALE, null);

        Map<String, Object> out = describe();

        assertThat(out).containsEntry("semantic_status", ConnectorSemanticService.ST_STALE);
        assertThat(String.valueOf(out.get("semantic_note"))).contains("结构已变");
    }

    /** ★ 键值对表：跨指标名 SUM 没有意义。实测过就点名两列，而且点的是本次实时结构里的写法。 */
    @Test
    void keyValueMeasured_namesBothColumnsAndForbidsCrossMetricSum() {
        liveFields = List.of(
                new FieldDetail("stat_date", "date", false, null, null),
                new FieldDetail("metric_name", "varchar(64)", false, null, null),
                new FieldDetail("metric_value", "decimal(18,2)", true, null, null));
        objectRow("每日经营指标", ConnectorSemanticService.ST_DRAFT,
                shape("KEY_VALUE", "MEASURED", "METRIC_NAME", "metric_value"));

        Map<String, Object> out = describe();

        assertThat(out).containsEntry("table_shape", "KEY_VALUE").containsEntry("table_shape_source", "MEASURED");
        assertThat(out).containsEntry("kv_name_column", "metric_name").containsEntry("kv_value_column", "metric_value");
        assertThat(String.valueOf(out.get("table_shape_warning")))
                .contains("metric_name").contains("metric_value")
                .contains("SUM").contains("过滤").contains("实测");
    }

    /** 只有模型的判断时必须说出"没实测"，否则它和实测结论读起来一样硬；也不许点名任何一列。 */
    @Test
    void keyValueFromModelOnly_saysUnmeasured_andNamesNoColumns() {
        objectRow(null, ConnectorSemanticService.ST_DRAFT, shape("KEY_VALUE", "MODEL", "st", "id"));

        Map<String, Object> out = describe();

        assertThat(out).as("gloss 与形态互相独立：没写说明，形态也不能跟着消失")
                .containsEntry("table_shape", "KEY_VALUE")
                .doesNotContainKeys("semantic", "kv_name_column", "kv_value_column");
        assertThat(String.valueOf(out.get("table_shape_warning"))).contains("没有实测").contains("SUM");
    }

    /**
     * ★ 平台测过、结果和模型相反时，「没有实测过」是一句假话，而且把唯一的反证藏起来了。
     * 形态照出（测量用的两列是按结构挑的，「不是」证明不了它不是），但告警必须说出实测不支持，并带上依据。
     */
    @Test
    void keyValueFromModel_contradictedByMeasurement_saysSo_withItsBasis() {
        objectRow("每日经营指标", ConnectorSemanticService.ST_DRAFT, shape("KEY_VALUE", "MODEL", null, null,
                measurement("NOT_KEY_VALUE", "st", "id", "采样 800 行，st 有 1 种取值，只有一种取值，不是指标名列")));

        Map<String, Object> out = describe();

        assertThat(out).containsEntry("table_shape", "KEY_VALUE").containsEntry("table_shape_source", "MODEL")
                .doesNotContainKeys("kv_name_column", "kv_value_column");
        assertThat(String.valueOf(out.get("table_shape_warning")))
                .doesNotContain("没有实测")
                .contains("实测的结果不支持")
                .contains("采样 800 行，st 有 1 种取值")
                .as("要让模型先核实，而不是照着键值对表去聚合").contains("先查几行数据确认")
                .contains("SUM");
    }

    /** 动过手但没结论：不是「没实测」，也不是「实测否定了」。UNDECIDABLE 还可能是被中断没测到，所以只说「尝试过」。 */
    @Test
    void keyValueFromModel_measuredWithoutConclusion_saysUndecided_notUnmeasured() {
        for (String outcome : List.of("INCONCLUSIVE", "UNDECIDABLE")) {
            objectRow(null, ConnectorSemanticService.ST_DRAFT, shape("KEY_VALUE", "MODEL", null, null,
                    measurement(outcome, "st", "id", "本轮被中断，这张表没测")));

            String warning = String.valueOf(describe().get("table_shape_warning"));

            assertThat(warning).as(outcome)
                    .doesNotContain("没有实测").doesNotContain("不支持")
                    .contains("尝试过用数据测量，但没有得出结论").contains("本轮被中断，这张表没测")
                    .contains("SUM");
        }
    }

    /** 依据里点名的列在实时结构里已经不存在：不引这句依据，理由同不点名 kv_name_column。结论本身照说。 */
    @Test
    void keyValueFromModel_basisNamingGoneColumns_isNotQuoted_butTheVerdictStillIs() {
        objectRow(null, ConnectorSemanticService.ST_DRAFT, shape("KEY_VALUE", "MODEL", null, null,
                measurement("NOT_KEY_VALUE", "metric_name", "metric_value", "采样 800 行，metric_name 有 1 种取值")));

        String warning = String.valueOf(describe().get("table_shape_warning"));

        assertThat(warning).contains("实测的结果不支持").doesNotContain("metric_name").doesNotContain("没有实测");
    }

    /** 留痕在、结论认不出：不许退回「没实测」，也不许引一条可能正好和告警相反的依据。 */
    @Test
    void keyValueFromModel_unreadableMeasurement_neverClaimsUnmeasured() {
        objectRow(null, ConnectorSemanticService.ST_DRAFT, shape("KEY_VALUE", "MODEL", null, null,
                measurement("SOMETHING_NEWER", "st", "id", "判定为键值对表：指标名在 st")));

        String warning = String.valueOf(describe().get("table_shape_warning"));

        assertThat(warning).doesNotContain("没有实测").doesNotContain("判定为键值对表").contains("认不出");
    }

    /** 记下的两列在实时结构里已经不存在：点名会让模型写出一条引用幽灵列的 SQL。 */
    @Test
    void keyValueMeasured_butColumnsAreGone_doesNotNameThem() {
        objectRow("每日经营指标", ConnectorSemanticService.ST_DRAFT,
                shape("KEY_VALUE", "MEASURED", "metric_name", "metric_value"));

        Map<String, Object> out = describe();

        assertThat(out).doesNotContainKeys("kv_name_column", "kv_value_column");
        assertThat(String.valueOf(out.get("table_shape_warning")))
                .contains("对不上").contains("SUM").doesNotContain("metric_name");
    }

    @Test
    void nonKeyValueShape_carriesItsSource_andNoWarning() {
        objectRow("订单明细", ConnectorSemanticService.ST_DRAFT, shape("DETAIL", "MODEL", null, null));

        Map<String, Object> out = describe();

        assertThat(out).containsEntry("table_shape", "DETAIL").containsEntry("table_shape_source", "MODEL");
        assertThat(out).doesNotContainKey("table_shape_warning");
    }

    /**
     * ★ 上线那一版写的是自由文本（主表 / 明细表 / 维度表 / 流水表 / 其他……），没有 table_shape_source。
     * 老词表和新枚举字面撞车、含义不同：老「明细表」是行项目表，不是 DETAIL；老「其他」映射成 OTHER 会断言
     * 「不是键值对表」、把聚合告警压掉。所以没有来源的一律不出形态——哪怕文本碰巧就是枚举名——但表用途那句照给。
     */
    @Test
    void legacyRowWithoutSource_neverEmitsAShape_whateverTheText() {
        for (String legacy : List.of("其他", "明细表", "键值对表", "主表", "维度表", "流水表", "OTHER", "DETAIL", "KEY_VALUE")) {
            objectRow("订单主表", ConnectorSemanticService.ST_DRAFT, shape(legacy, null, null, null));

            Map<String, Object> out = describe();

            assertThat(out).as(legacy).containsEntry("semantic", "订单主表")
                    .doesNotContainKeys("table_shape", "table_shape_source", "table_shape_warning");
        }
    }

    /**
     * 推导侧只写原样的枚举名和原样的来源。中文名、小写、认不出的来源都不是它写出来的样子——
     * 这种行既没有说明也没有可信形态时，返回必须和没有语义层时一字不差。
     */
    @Test
    void onlyExactContractValuesAreAccepted() throws Exception {
        String[][] notTheContract = {
                {"其他", "MODEL"}, {"明细表", "MODEL"}, {"键值对表", "MEASURED"}, {"detail", "MODEL"},
                {"DETAIL", "model"}, {"KEY_VALUE", "HUMAN"}};
        for (String[] c : notTheContract) {
            objectRow(null, ConnectorSemanticService.ST_DRAFT, shape(c[0], c[1], null, null));

            String json = CommonUtil.getObjectMapper().writeValueAsString(describe());

            assertThat(json).as("%s / %s", c[0], c[1]).isEqualTo(BASELINE_DESCRIBE_JSON);
        }
    }

    // ------------------------------------------------------------------ joins：三种可直接用的结论

    @Test
    void reliableVerificationStates_neverReadTheSame() {
        String confirmed = basisOf(join("order_id", ConnectorSemanticService.V_CONFIRMED,
                probe(1000, 970, 0.97d, "N:1", true, null)));
        String undecidable = basisOf(join("order_id", ConnectorSemanticService.V_UNDECIDABLE,
                probe(null, null, null, null, null, "这张表几乎是空的，样本不足以判定——这不等于这条关系是错的")));
        String none = basisOf(join("order_id", ConnectorSemanticService.V_NONE, base()));

        assertThat(List.of(confirmed, undecidable, none))
                .as("三种结论三句话，说成一样模型就只能按一种方式处理")
                .doesNotHaveDuplicates();

        assertThat(confirmed).contains("采样 1000 个取值，命中 970（包含率 97.0%）");
        assertThat(confirmed).as("验过了就别再让模型白跑一次 COUNT").contains("不必");

        // ★ V_NONE = 没探查过，V_UNDECIDABLE = 探查过但判不出来。它们对下一步的指示不同，永远不许同形。
        assertThat(undecidable).contains("判不出来").contains("这张表几乎是空的");
        assertThat(undecidable).as("查过了就不能说成「未经数据验证」").doesNotContain("未经数据验证");
        assertThat(none).contains("未经数据验证");
    }

    @Test
    void verifiedValueIsHandedOverRaw_soTheModelCanBranchOnIt() {
        semanticsWithJoins(join("order_id", ConnectorSemanticService.V_UNDECIDABLE,
                probe(500, null, null, "N:1", true, "超时")));

        assertThat(firstJoin()).containsEntry("verified", ConnectorSemanticService.V_UNDECIDABLE);
    }

    /** 契约之前的老行没有 join_kind：按单列处理，而且 joins 的形状一个 key 都不多。 */
    @Test
    void legacyRowWithoutJoinKind_staysInJoins_withTheOldShape() {
        semanticsWithJoins(join("order_id", ConnectorSemanticService.V_CONFIRMED,
                probe(1000, 1000, 1.0d, "N:1", true, null)));

        Map<String, Object> out = describe();

        assertThat(out).doesNotContainKey("unreliable_relations");
        assertThat(firstJoin()).doesNotContainKeys("join_kind", "care_reason", "condition");
    }

    // ------------------------------------------------------------------ unreliable_relations

    /** ★ 包含率 0.5~0.9 常见成因是多态外键或复合键，单列 join 不是「部分正确」，而是连出错的行。 */
    @Test
    void weakJoin_leavesJoins_andStatesTheMeasuredBasisAndTheLikelyCause() {
        semanticsWithJoins(join("order_id", ConnectorSemanticService.V_WEAK,
                probe(1000, 800, 0.80d, "N:1", true, null)));

        Map<String, Object> out = describe();

        assertThat(out).as("部分支持的关系不能和可直接用的放在同一个数组里").doesNotContainKey("joins");
        assertThat(out).containsKey("semantic_hint");
        Map<String, Object> r = firstUnreliable(out);
        assertThat(r).containsEntry("verified", ConnectorSemanticService.V_WEAK).containsEntry("join_kind", "SIMPLE");
        assertThat(String.valueOf(r.get("condition")))
                .contains("采样 1000 个取值，命中 800（包含率 80.0%）")
                .contains("数据只部分支持，常见成因是多态外键或复合键");
        assertThat(String.valueOf(r.get("care_reason"))).isNotBlank();
        assertThat(String.valueOf(r.get("basis"))).doesNotContain("可以直接使用");
    }

    /** 多态外键验证通过也不进 joins：包含率量不出「别的类型里恰好同号的行」。 */
    @Test
    void polymorphicWithCollectedValue_demandsTheDiscriminatorCondition_evenWhenConfirmed() {
        Map<String, Object> d = probe(1000, 990, 0.99d, "N:1", true, null);
        d.put("join_kind", "POLYMORPHIC");
        d.put("discriminator_column", "resource_type");
        d.put("discriminator_value", "order");
        d.put("care_reason", "resource_id 按 resource_type 指向不同的表");
        semanticsWithJoins(join("resource_id", ConnectorSemanticService.V_CONFIRMED, d));

        Map<String, Object> out = describe();

        assertThat(out).doesNotContainKey("joins");
        Map<String, Object> r = firstUnreliable(out);
        assertThat(r).containsEntry("join_kind", "POLYMORPHIC")
                .containsEntry("discriminator_column", "resource_type")
                .containsEntry("discriminator_value", "order")
                .containsEntry("care_reason", "resource_id 按 resource_type 指向不同的表");
        assertThat(String.valueOf(r.get("condition"))).contains("必须同时加上 resource_type = 'order' 条件");
        assertThat(String.valueOf(r.get("basis")))
                .as("同一条里不能一边说可以直接用、一边说必须加条件")
                .doesNotContain("可以直接使用").doesNotContain("不必");
    }

    /**
     * 第 3 档开着、却没记下取值：这时再说「取值需第 3 档」是假话——它此刻就在第 3 档。
     * 没确认的（没验 / 判不出来）只能说疑似，两种结局都交给模型去核；分组探查确认过、只是取值没记下（PII 挡了）的，照旧说必须加。
     */
    @Test
    void polymorphicWithoutRecordedValue_atTier3_doesNotClaimTier3IsNeeded() {
        Map<String, Object> d = base();
        d.put("join_kind", "POLYMORPHIC");
        d.put("discriminator_column", "resource_type");
        semanticsWithJoins(join("resource_id", ConnectorSemanticService.V_NONE, d));

        Map<String, Object> r = firstUnreliable(describe());

        assertThat(r).containsEntry("discriminator_column", "resource_type").doesNotContainKey("discriminator_value");
        assertThat(String.valueOf(r.get("condition")))
                .contains("疑似多态外键").contains("平台没有记下它的取值")
                .contains("先查出 resource_type 有哪些取值")
                .contains("每个取值都指向 t_ord_mst 时它只是分类列，不要加这个条件")
                .doesNotContain("不加它的条件就 join 会匹配到别的类型的行")
                .doesNotContain("需第 3 档");

        d.put("probed_with_sample_values", true);
        semanticsWithJoins(join("resource_id", ConnectorSemanticService.V_CONFIRMED, d));

        assertThat(String.valueOf(firstUnreliable(describe()).get("condition")))
                .contains("存在判别列 resource_type，不加它的条件就 join 会匹配到别的类型的行；平台没有记下哪个取值对应 t_ord_mst")
                .doesNotContain("疑似").doesNotContain("需第 3 档");
    }

    /** 档位没开放、本来也没有取值：要说的是「档位没开放」，这句话无论库里有没有存过取值都成立。 */
    @Test
    void polymorphicWithoutRecordedValue_belowTier3_saysTheTierIsNotOpen() {
        when(semanticService.allowsSampleValues(any())).thenReturn(false);
        Map<String, Object> d = base();
        d.put("join_kind", "POLYMORPHIC");
        d.put("discriminator_column", "resource_type");
        semanticsWithJoins(join("resource_id", ConnectorSemanticService.V_NONE, d));

        Map<String, Object> r = firstUnreliable(describe());

        assertThat(r).containsEntry("discriminator_column", "resource_type").doesNotContainKey("discriminator_value");
        assertThat(String.valueOf(r.get("condition")))
                .contains("没有确认开放第 3 档").contains("先查出 resource_type 有哪些取值");
    }

    // ------------------------------------------------------------------ 第 3 档取值：读出侧再判一次档位

    /**
     * ★ 连接被静默降回第 2 档之后，库里存着的判别值不能继续流进模型上下文（和 ai_model_call_content）。
     * 判别列是结构，照给；取值、嵌着取值的 care_reason、拿取值拼出的 condition 一个都不能带出去。
     */
    @Test
    void tierNoLongerAllowsSampleValues_withholdsTheDiscriminatorValueFromEveryKey() throws Exception {
        when(semanticService.allowsSampleValues(any())).thenReturn(false);
        Map<String, Object> d = probe(1000, 990, 0.99d, "N:1", true, null);
        d.put("join_kind", "POLYMORPHIC");
        d.put("discriminator_column", "resource_type");
        d.put("discriminator_value", "SECRET_KIND_7");
        d.put("care_reason", "多态外键：t_ord.resource_id 只有在 t_ord.resource_type = 'SECRET_KIND_7' 时才指向 t_ord_mst.id。");
        semanticsWithJoins(join("resource_id", ConnectorSemanticService.V_CONFIRMED, d));

        Map<String, Object> out = describe();

        assertThat(CommonUtil.getObjectMapper().writeValueAsString(out))
                .as("真实取值不许从任何一个 key 漏出去").doesNotContain("SECRET_KIND_7");
        Map<String, Object> r = firstUnreliable(out);
        assertThat(r).containsEntry("join_kind", "POLYMORPHIC")
                .containsEntry("discriminator_column", "resource_type")
                .doesNotContainKey("discriminator_value");
        assertThat(String.valueOf(r.get("care_reason"))).as("换成按形态兜底的那句，不能缺").contains("多态外键");
        assertThat(String.valueOf(r.get("condition")))
                .as("要说清是档位没开放，否则会被读成「没采到」")
                .contains("没有确认开放第 3 档").contains("先查出 resource_type 有哪些取值");
    }

    /**
     * 从 care_reason 里认组合键的两条边界：增量推导可能改了 to_object 的大小写（那句话仍是当初的写法），要认得出；
     * 括号里读出不是标识符形状的东西时，只说「没记下完整的键列」，一个字都不点名。
     */
    @Test
    void compositeReadFromCareReason_ignoresCase_andNamesNothingThatIsNotAnIdentifier() {
        String validatorSentence = String.valueOf(partitionedOrderRelation("resource_id", false).get("care_reason"));
        Map<String, Object> poly = new LinkedHashMap<>();
        poly.put("to_object", "ORDERS_P");
        poly.put("to_column", "ID");
        poly.put("join_kind", "POLYMORPHIC");
        poly.put("discriminator_column", "resource_type");
        poly.put("care_reason", validatorSentence);
        semanticsWithJoins(join("resource_id", ConnectorSemanticService.V_NONE, poly));

        assertThat(String.valueOf(firstUnreliable(describe()).get("condition")))
                .contains("ORDERS_P.ID 只是组合唯一键 (id, create_time) 的一部分").contains("COUNT(DISTINCT ID)");

        Map<String, Object> composite = base();
        composite.put("join_kind", "COMPOSITE");
        composite.put("care_reason", "t_ord_mst.id 只是组合唯一键 (id, 'x y') 的一部分，单独不唯一");
        semanticsWithJoins(join("order_no", ConnectorSemanticService.V_NONE, composite));

        assertThat(String.valueOf(firstUnreliable(describe()).get("condition")))
                .contains("没记下完整的键列").doesNotContain("'x y'").doesNotContain("组合唯一键 (");
    }

    /**
     * ★ 第 3 档之下，存着的 care_reason 只在它<b>不可能</b>嵌着取值时原样给：probed_with_sample_values 不是 true、且没有 discriminator_value。
     * 任一个键说「可能」就按结构重拼（认不出的布尔按「探查跑过」处理）；不是组合键就不凭空加组合键提醒。第 3 档开着时照原样给。
     */
    @Test
    void belowTier3_storedPolymorphicCareReason_isUsedOnlyWhenItCannotHoldAValue() {
        when(semanticService.allowsSampleValues(any())).thenReturn(false);
        String stored = "疑似多态外键：指向哪张表可能由同表的 resource_type 决定";
        for (Object probed : new Object[]{null, false, "false"}) {
            semanticsWithJoins(join("resource_id", ConnectorSemanticService.V_UNDECIDABLE, polymorphicWithCare(stored, probed)));

            assertThat(firstUnreliable(describe())).as("probed=%s", probed).containsEntry("care_reason", stored);
        }
        for (Object probed : new Object[]{true, "TRUE", 1}) {
            semanticsWithJoins(join("resource_id", ConnectorSemanticService.V_UNDECIDABLE, polymorphicWithCare(stored, probed)));

            assertThat(String.valueOf(firstUnreliable(describe()).get("care_reason"))).as("probed=%s", probed)
                    .isNotEqualTo(stored).contains("疑似多态外键").contains("分类列").doesNotContain("组合唯一键");
        }

        // 判别值那个键在：哪怕那句话里看不出取值，也不用它；确认过的才说「按类型指向不同的表」。
        Map<String, Object> valueKey = polymorphicWithCare(stored, null);
        valueKey.put("discriminator_value", "SECRET_KIND_7");
        semanticsWithJoins(join("resource_id", ConnectorSemanticService.V_CONFIRMED, valueKey));
        Map<String, Object> out = describe();
        assertThat(json(out)).doesNotContain("SECRET_KIND_7");
        assertThat(String.valueOf(firstUnreliable(out).get("care_reason")))
                .isNotEqualTo(stored).contains("多态外键").doesNotContain("疑似");

        when(semanticService.allowsSampleValues(any())).thenReturn(true);
        semanticsWithJoins(join("resource_id", ConnectorSemanticService.V_UNDECIDABLE, polymorphicWithCare(stored, true)));
        assertThat(firstUnreliable(describe())).containsEntry("care_reason", stored);
    }

    /**
     * ★ 默认档（第 2 档）上的多态外键：验证侧 structureOnly 写下的那句不带取值，却带着最要紧的保留——只是疑似，
     * 每个取值都指向目标表时它只是分类列。那句要原样给，condition 也不许把它说成「必须按类型过滤」：
     * 模型照着过滤一个类型，其余类型的行被静默漏掉。结构判定（没验）与第 2 档包含率判不出来（probed_with_sample_values=false）
     * 两种都照给；同时是组合键时，组合键那一句本来就在那句话里。
     */
    @Test
    void tier2_structureOnlyCareReason_isEmittedVerbatim_reservationIncluded() {
        when(semanticService.allowsSampleValues(any())).thenReturn(false);
        Map<String, Object> structural = polymorphicRelation();
        String stored = String.valueOf(structural.get("care_reason"));
        assertThat(stored).as("前提：验证侧的真实产出带着保留、不带取值")
                .contains("疑似多态外键").contains("它只是分类列，这个条件可以不加");
        Map<String, Object> probedAtTier2 = polymorphicRelation();
        probedAtTier2.put("probed_with_sample_values", false);

        for (ConnectorSemantic row : List.of(join("resource_id", ConnectorSemanticService.V_NONE, structural),
                join("resource_id", ConnectorSemanticService.V_UNDECIDABLE, probedAtTier2))) {
            semanticsWithJoins(row);

            Map<String, Object> r = firstUnreliable(describe());

            assertThat(r).as(row.getVerified()).containsEntry("care_reason", stored);
            assertThat(String.valueOf(r.get("condition"))).as(row.getVerified())
                    .contains("疑似多态外键").contains("没有确认开放第 3 档")
                    .contains("每个取值都指向 t_ord_mst 时它只是分类列，不要加这个条件")
                    .doesNotContain("不加它的条件就 join 会匹配到别的类型的行");
        }

        Map<String, Object> withComposite = partitionedOrderRelation("resource_id", true);
        semanticsWithJoins(join("resource_id", ConnectorSemanticService.V_NONE, withComposite));
        assertThat(firstUnreliable(describe())).containsEntry("care_reason", withComposite.get("care_reason"));
    }

    /**
     * 分组探查跑过（probed_with_sample_values=true）写下的那句，第 3 档之下不再原样给：它可能嵌着那次探查带回的东西。
     * 按结构重拼，组合键那一半照留；没确认的仍说「疑似」，确认过的才说「按类型指向不同的表」。
     */
    @Test
    void tier2_afterTheGroupedProbeRan_usesTheStructuralFallback_keepingTheCompositePart() {
        when(semanticService.allowsSampleValues(any())).thenReturn(false);
        for (String verified : List.of(ConnectorSemanticService.V_UNDECIDABLE, ConnectorSemanticService.V_CONFIRMED)) {
            Map<String, Object> d = partitionedOrderRelation("resource_id", true);
            d.put("care_reason", d.get("care_reason") + "满足确认线的那个取值没有记录：PROBE_ONLY_DETAIL_7。");
            d.put("probed_with_sample_values", true);
            semanticsWithJoins(join("resource_id", verified, d));

            Map<String, Object> out = describe();

            assertThat(json(out)).as(verified).doesNotContain("PROBE_ONLY_DETAIL_7");
            Map<String, Object> r = firstUnreliable(out);
            String care = String.valueOf(r.get("care_reason"));
            String condition = String.valueOf(r.get("condition"));
            assertThat(care).as(verified)
                    .contains("包含率只能说明这个 id 在对面存在")
                    .contains("orders_p.id 只是组合唯一键 (id, create_time) 的一部分");
            assertThat(condition).as(verified).contains("没有确认开放第 3 档").contains("COUNT(DISTINCT id)");
            if (ConnectorSemanticService.V_CONFIRMED.equals(verified)) {
                assertThat(care).doesNotContain("疑似");
                assertThat(condition).contains("存在判别列 resource_type，不加它的条件就 join 会匹配到别的类型的行");
            } else {
                assertThat(care).contains("疑似多态外键").contains("分类列");
                assertThat(condition).contains("疑似多态外键")
                        .doesNotContain("不加它的条件就 join 会匹配到别的类型的行");
            }
        }
    }

    /** ★ 值域整段都是第 3 档那次采集的产物：档位没开放时取值、distinct_count、那句 note 一概不给，只留 complete=false。 */
    @Test
    void tierNoLongerAllowsSampleValues_withholdsTheWholeValueDomain() throws Exception {
        when(semanticService.allowsSampleValues(any())).thenReturn(false);
        semanticsWithField("st", "状态码", valueDomain(Map.of(
                "complete", true, "outcome", "ENUMERATED", "distinct_count", 2,
                "values", List.of("华东大区", "华南大区"),
                "note", SemanticValueProfiler.Outcome.ENUMERATED.modelNote())));

        Map<String, Object> out = describe();

        assertThat(CommonUtil.getObjectMapper().writeValueAsString(out)).doesNotContain("华东大区").doesNotContain("华南大区");
        Map<String, Object> vd = valueDomainIn(out, "st");
        assertThat(vd).containsEntry("complete", false).doesNotContainKeys("values", "distinct_count");
        assertThat(String.valueOf(vd.get("note"))).contains("没有确认开放第 3 档").contains("不等于");
        assertThat(fieldIn(out, "st")).as("说明本身不是取值，照给").containsEntry("semantic", "状态码");
    }

    /**
     * ★ 值域阶段给没有 FIELD 行的列补写的那一行，gloss 就是把取值原样列出来的一句话。
     * 降档后只挡 value_domain，同一批真实取值会从这一列的 semantic 原样出去。
     */
    @Test
    void tierNoLongerAllowsSampleValues_dropsAnInferredGlossBuiltFromTheValues() throws Exception {
        when(semanticService.allowsSampleValues(any())).thenReturn(false);
        semanticsWithFieldRow(legacyValueRow(valueDomain(Map.of(
                "complete", true, "outcome", "ENUMERATED", "values", List.of("华东大区", "华南大区")))));

        Map<String, Object> out = describe();

        assertThat(json(out)).doesNotContain("华东大区").doesNotContain("华南大区");
        assertThat(fieldIn(out, "st")).doesNotContainKey("semantic");
        assertThat(valueDomainIn(out, "st")).containsEntry("complete", false).doesNotContainKey("values");
    }

    /**
     * ★ 挡 gloss 不能看 value_domain 此刻还列不列着取值：再剖析一次没拿到完整集合，片段被覆盖成不带取值的样子，
     * gloss 却还是当初那句列着取值的话；清理取值把片段删空也一样。老行、带标记的新行、各种片段形状，降档后一律不给。
     */
    @Test
    void valueProfileGloss_isWithheldBelowTier3_whateverValueDomainNowHolds() {
        when(semanticService.allowsSampleValues(any())).thenReturn(false);
        Map<String, Object> reprofiled = new LinkedHashMap<>();
        reprofiled.put("complete", false);
        reprofiled.put("outcome", "HIGH_CARDINALITY");
        reprofiled.put("note", SemanticValueProfiler.Outcome.HIGH_CARDINALITY.modelNote());
        for (String detail : List.of(valueDomain(reprofiled), valueDomain(new LinkedHashMap<>()),
                "{\"value_domain\":null}", "{not json")) {
            semanticsWithFieldRow(legacyValueRow(detail));

            Map<String, Object> out = describe();

            assertThat(json(out)).as("老行 %s", detail).doesNotContain("华东大区");
            assertThat(fieldIn(out, "st")).as("老行 %s", detail).doesNotContainKey("semantic");
        }

        // 带标记的新行：gloss 按契约不再嵌取值，照样挡——由出处决定，不由内容决定；值域整段没有了也挡。
        for (String detail : List.of("{\"origin\":\"value_profile\",\"value_domain\":" + json(reprofiled) + "}",
                "{\"origin\":\"value_profile\"}")) {
            ConnectorSemantic row = field("st", "取值共 12 种（采样时点的完整集合）", detail);
            row.setSource(ConnectorSemanticService.SOURCE_INFERRED);
            row.setEvidence(ConnectorSemanticService.EV_DATA);
            semanticsWithFieldRow(row);

            assertThat(fieldOf("st")).as("新行 %s", detail).doesNotContainKey("semantic");
        }
    }

    @Test
    void valueProfileGloss_isShownWhileTier3IsOpen() {
        semanticsWithFieldRow(legacyValueRow(valueDomain(Map.of(
                "complete", true, "outcome", "ENUMERATED", "values", List.of("华东大区", "华南大区")))));

        assertThat(String.valueOf(fieldOf("st").get("semantic"))).contains("华东大区");
    }

    /**
     * ★ 闸只挡值域阶段补写的行。模型写的字段说明后来被值域阶段并进了 value_domain（这一列曾被剖析过）：
     * 降档后值域照挡，说明照给——那句话是模型看结构写的，不是第 3 档采集带出来的；挡掉就是一句注解无声消失。
     */
    @Test
    void modelWrittenGlossOnAOnceProfiledColumn_isNotWithheld() {
        when(semanticService.allowsSampleValues(any())).thenReturn(false);
        for (String evidence : List.of(ConnectorSemanticService.EV_COMMENT, ConnectorSemanticService.EV_NAME)) {
            ConnectorSemantic row = field("st", "订单状态码，含义要问业务方", valueDomain(Map.of(
                    "complete", true, "outcome", "ENUMERATED", "values", List.of("华东大区", "华南大区"))));
            row.setSource(ConnectorSemanticService.SOURCE_INFERRED);
            row.setEvidence(evidence);
            semanticsWithFieldRow(row);

            Map<String, Object> out = describe();

            assertThat(fieldIn(out, "st")).as(evidence).containsEntry("semantic", "订单状态码，含义要问业务方");
            assertThat(json(out)).as("值域照挡 %s", evidence).doesNotContain("华东大区");
        }
    }

    /**
     * 推导提示词让模型给「从类型 / 约束读得出来」的说明也标 DATA。这种行从没被值域阶段碰过、没有 value_domain，
     * 降档（以及默认的第 2 档）时照给：只按「INFERRED + FIELD + EV_DATA」字面判，每条默认档连接上这类说明都会无声消失。
     */
    @Test
    void modelGlossTaggedData_neverProfiled_isNotWithheld() {
        when(semanticService.allowsSampleValues(any())).thenReturn(false);
        ConnectorSemantic row = field("id", "自增主键，一行一个订单", null);
        row.setSource(ConnectorSemanticService.SOURCE_INFERRED);
        row.setEvidence(ConnectorSemanticService.EV_DATA);
        semanticsWithFieldRow(row);

        assertThat(fieldOf("id")).containsEntry("semantic", "自增主键，一行一个订单");
        verify(semanticService, never()).allowsSampleValues(any());
    }

    /**
     * ★ 挡不挡 gloss 与清理第 3 档取值用同一个判据（ConnectorSemanticService.isValueProfileRow），逐行对得上。
     * 两边一旦不一致都不出声：本类窄了，清理还没跑到的那一刻 gloss 连同取值照旧出库；本类宽了，清理不碰的说明在这里无声消失。
     * 人写的说明没带值域阶段的标记时照给；带着标记的，清理会把它换掉，这里也不给。
     */
    @Test
    void glossWithholding_agreesWithThePurgeRule_rowByRow() {
        when(semanticService.allowsSampleValues(any())).thenReturn(false);
        String values = "{\"value_domain\":{\"complete\":true,\"outcome\":\"ENUMERATED\",\"values\":[\"0\",\"1\"]}}";
        String marked = "{\"origin\":\"value_profile\","
                + "\"value_domain\":{\"complete\":true,\"outcome\":\"ENUMERATED\",\"values\":[\"0\",\"1\"]}}";
        List<Object[]> cases = List.of(
                new Object[]{ConnectorSemanticService.SOURCE_HUMAN, ConnectorSemanticService.EV_DATA, values, false},
                new Object[]{ConnectorSemanticService.SOURCE_IMPORTED, ConnectorSemanticService.EV_DATA, values, false},
                new Object[]{ConnectorSemanticService.SOURCE_HUMAN, ConnectorSemanticService.EV_DATA, marked, true},
                new Object[]{ConnectorSemanticService.SOURCE_IMPORTED, ConnectorSemanticService.EV_NAME, marked, true},
                new Object[]{ConnectorSemanticService.SOURCE_INFERRED, ConnectorSemanticService.EV_DATA, values, true},
                new Object[]{ConnectorSemanticService.SOURCE_INFERRED, ConnectorSemanticService.EV_DATA, "{not json", true},
                new Object[]{ConnectorSemanticService.SOURCE_INFERRED, ConnectorSemanticService.EV_DATA, "{}", false},
                new Object[]{ConnectorSemanticService.SOURCE_INFERRED, ConnectorSemanticService.EV_DATA, null, false},
                new Object[]{ConnectorSemanticService.SOURCE_INFERRED, ConnectorSemanticService.EV_COMMENT, values, false});
        for (Object[] c : cases) {
            ConnectorSemantic row = field("st", "状态：0 待支付、1 已支付", (String) c[2]);
            row.setSource((String) c[0]);
            row.setEvidence((String) c[1]);
            semanticsWithFieldRow(row);
            String label = c[0] + "/" + c[1] + "/" + c[2];
            boolean valueRow = (boolean) c[3];

            assertThat(ConnectorSemanticService.isValueProfileRow(row)).as("契约 %s", label).isEqualTo(valueRow);
            assertThat(fieldOf("st").containsKey("semantic")).as("本类 %s", label).isEqualTo(!valueRow);
        }
    }

    /** 契约上 allowsSampleValues 自己就 fail-closed；它万一抛了，describe 不能失败，取值也不能因此放出去。 */
    @Test
    void tierLookupThrows_failsClosed_andDescribeStillSucceeds() throws Exception {
        when(semanticService.allowsSampleValues(any())).thenThrow(new IllegalStateException("db down"));
        Map<String, Object> d = base();
        d.put("join_kind", "POLYMORPHIC");
        d.put("discriminator_column", "kind");
        d.put("discriminator_value", "order_kind_x");
        ConnectorSemantic poly = join("ref_id", ConnectorSemanticService.V_CONFIRMED, d);
        ConnectorSemantic st = field("st", null, valueDomain(Map.of(
                "complete", true, "outcome", "ENUMERATED", "values", List.of("val_a", "val_b"))));
        when(semanticService.forObject(any(), anyString())).thenReturn(
                ConnectorSemanticService.ObjectSemantics.builder()
                        .fields(Map.of("st", st)).joins(List.of(poly)).build());

        Map<String, Object> out = describe();

        assertThat(CommonUtil.getObjectMapper().writeValueAsString(out))
                .doesNotContain("order_kind_x").doesNotContain("val_a").doesNotContain("val_b");
        assertThat(firstUnreliable(out)).containsEntry("discriminator_column", "kind");
        assertThat(valueDomainIn(out, "st")).containsEntry("complete", false);
    }

    /** 档位懒着问：没有第 3 档才有的东西时一次都不问，有几条也只问一次。 */
    @Test
    void tierIsAskedLazily_andAtMostOncePerDescribe() {
        semanticsWithJoins(join("order_id", ConnectorSemanticService.V_CONFIRMED,
                probe(1000, 1000, 1.0d, "N:1", true, null)));
        describe();
        verify(semanticService, never()).allowsSampleValues(any());

        Map<String, Object> a = base();
        a.put("join_kind", "POLYMORPHIC");
        a.put("discriminator_column", "kind");
        a.put("discriminator_value", "A");
        Map<String, Object> b = base();
        b.put("join_kind", "POLYMORPHIC");
        b.put("discriminator_column", "kind");
        ConnectorSemantic st = field("st", null, valueDomain(Map.of(
                "complete", true, "outcome", "ENUMERATED", "values", List.of("0", "1"))));
        when(semanticService.forObject(any(), anyString())).thenReturn(
                ConnectorSemanticService.ObjectSemantics.builder().fields(Map.of("st", st))
                        .joins(List.of(join("a_id", ConnectorSemanticService.V_CONFIRMED, a),
                                join("b_id", ConnectorSemanticService.V_NONE, b))).build());
        describe();
        verify(semanticService, times(1)).allowsSampleValues(1L);
    }

    @Test
    void discriminatorValue_isRenderedAsAnEscapedSqlLiteral() {
        Map<String, Object> d = base();
        d.put("join_kind", "polymorphic");
        d.put("discriminator_column", "kind");
        d.put("discriminator_value", "o'rder");
        semanticsWithJoins(join("ref_id", ConnectorSemanticService.V_CONFIRMED, d));

        Map<String, Object> r = firstUnreliable(describe());

        assertThat(r).containsEntry("join_kind", "POLYMORPHIC");
        assertThat(String.valueOf(r.get("condition"))).contains("kind = 'o''rder'");
    }

    /**
     * ★ 复合键：condition 只说平台确知的那一对。分区表主键被迫是 (id, create_time) 时，照「每一列逐一对上」写出
     * order_item.create_time = orders_p.create_time 会把几乎所有明细连丢（本地 MySQL 实测 4 行只剩 1 行），不报错。
     * 左表刻意也有一列 create_time：同名列就摆在那里，也不许被配上去。
     * care_reason 用验证侧的真实产出，所以「两句话不互相矛盾」在这里是真比对过的，而不是拿一句手写的替身。
     */
    @Test
    void composite_namesOnlyTheKnownPair_andNeverPairsTheOtherKeyColumnsByName() throws Exception {
        Map<String, Object> d = partitionedOrderRelation("order_id", false);
        assertThat(d).as("前提：验证侧把它判成了组合键").containsEntry("join_kind", "COMPOSITE");
        semanticsWithJoins(join("order_id", ConnectorSemanticService.V_CONFIRMED, d));

        Map<String, Object> r = firstUnreliable(describe());
        String condition = String.valueOf(r.get("condition"));

        assertThat(r).containsEntry("join_kind", "COMPOSITE")
                .containsEntry("composite_columns", List.of("id", "create_time"));
        assertThat(condition)
                .contains("本表 order_id → orders_p.id 这一对")
                .contains("其余键列 create_time 在本表对应哪一列，平台不知道")
                .contains("不要按同名列去配")
                .contains("COUNT(*) 与 COUNT(DISTINCT id)")
                .contains("相等说明 id 实际一行一个，可以只按这一对关联")
                .doesNotContain("全部列一起").doesNotContain("逐一对上");
        assertThat(CommonUtil.getObjectMapper().writeValueAsString(r))
                .as("任何一处都不许出现按名字替本表配出来的键列")
                .doesNotContain("create_time =").doesNotContain("= orders_p.create_time")
                .doesNotContain("t_ord.create_time").doesNotContain("本表 create_time");
        assertThat(String.valueOf(r.get("care_reason"))).as("care_reason 与 condition 给出的是同一个检查")
                .contains("COUNT(DISTINCT id)");
    }

    /** 没记下完整键列的复合键：不点名任何一组键列，但确知的那一对和 COUNT 检查照样要说；也不说「一列定位不到一行」这句常常是假的话。 */
    @Test
    void compositeWithoutRecordedKey_stillStatesTheKnownPairAndTheCountCheck() {
        Map<String, Object> d = base();
        d.put("join_kind", "COMPOSITE");
        semanticsWithJoins(join("order_no", ConnectorSemanticService.V_NONE, d));

        Map<String, Object> r = firstUnreliable(describe());

        assertThat(String.valueOf(r.get("condition")))
                .contains("本表 order_no → t_ord_mst.id 这一对").contains("没记下完整的键列")
                .contains("COUNT(*) 与 COUNT(DISTINCT id)").doesNotContain("全部");
        assertThat(String.valueOf(r.get("care_reason")))
                .contains("单独没有唯一约束").doesNotContain("定位不到");
    }

    /**
     * ★ 多态外键同时是组合键：验证侧只记 POLYMORPHIC、不写 composite_columns，组合键那一半只写在 care_reason 里。
     * 用验证侧的真实产出钉住两件事：condition 两段都给；本类认得出那句话里的组合键——验证侧改了措辞，这条先红，
     * 而不是线上静默少一段提醒。
     */
    @Test
    void polymorphicAndComposite_conditionCarriesBothParts_readFromTheValidatorsRealWording() {
        Map<String, Object> d = partitionedOrderRelation("resource_id", true);
        assertThat(d).as("前提：两者都命中时验证侧记成多态外键、不写 composite_columns")
                .containsEntry("join_kind", "POLYMORPHIC").doesNotContainKey("composite_columns");
        semanticsWithJoins(join("resource_id", ConnectorSemanticService.V_NONE, d));

        Map<String, Object> r = firstUnreliable(describe());

        assertThat(r).as("第 3 档开着：验证侧那句原样给").containsEntry("care_reason", d.get("care_reason"));
        assertThat(String.valueOf(r.get("condition")))
                .contains("疑似多态外键").contains("判别列 resource_type")
                .contains("orders_p.id 只是组合唯一键 (id, create_time) 的一部分")
                .contains("本表 resource_id → orders_p.id 这一对")
                .contains("COUNT(*) 与 COUNT(DISTINCT id)");
    }

    /**
     * ★ 降档后，分组探查写下的那句 care_reason 整句不用（它嵌着取值），但组合键那一半不能跟着丢。
     * 判别值那个键没了、那句话还嵌着取值时，取值同样不许出去：判据不能只是「discriminator_value 在不在」——
     * 写下判别值的那次探查必然记着 probed_with_sample_values=true，它照样挡住。
     */
    @Test
    void polymorphicAndComposite_belowTier3_keepsTheCompositeWarning_andNeverLeaksTheValue() throws Exception {
        when(semanticService.allowsSampleValues(any())).thenReturn(false);
        String compositePart = String.valueOf(partitionedOrderRelation("resource_id", false).get("care_reason"));
        for (boolean valueKeyPurged : List.of(false, true)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("to_object", "orders_p");
            d.put("to_column", "id");
            d.put("join_kind", "POLYMORPHIC");
            d.put("discriminator_column", "resource_type");
            // 推导侧在分组探查带回结果时记下的（K-2）：嵌着判别值的那句话只出自那次探查。
            d.put("probed_with_sample_values", true);
            if (!valueKeyPurged) {
                d.put("discriminator_value", "SECRET_KIND_7");
            }
            // 验证侧在第 3 档记下判别值时那句话的形状：取值嵌在前半句，组合键那一句（真实产出）接在后面。
            d.put("care_reason", "多态外键：t_ord.resource_id 只有在 t_ord.resource_type = 'SECRET_KIND_7' 时才指向 orders_p.id。"
                    + "关联时必须带上这个条件，否则指向别的表的行也会被连上，而且不报错。" + compositePart);
            semanticsWithJoins(join("resource_id", ConnectorSemanticService.V_CONFIRMED, d));

            Map<String, Object> out = describe();

            assertThat(CommonUtil.getObjectMapper().writeValueAsString(out)).as("purged=%s", valueKeyPurged)
                    .doesNotContain("SECRET_KIND_7");
            Map<String, Object> r = firstUnreliable(out);
            assertThat(String.valueOf(r.get("care_reason"))).as("purged=%s", valueKeyPurged)
                    .contains("多态外键")
                    .contains("orders_p.id 只是组合唯一键 (id, create_time) 的一部分")
                    .contains("单独没有唯一约束");
            assertThat(String.valueOf(r.get("condition"))).as("purged=%s", valueKeyPurged)
                    .contains("没有确认开放第 3 档").contains("COUNT(DISTINCT id)");
        }
    }

    /** 契约要求这一桶每条都带 care_reason 和 condition；验证侧没写原因、没记全键列时也不能缺。 */
    @Test
    @SuppressWarnings("unchecked")
    void everyUnreliableRelation_carriesCareReasonAndCondition_evenWhenDetailIsThin() {
        Map<String, Object> poly = base();
        poly.put("join_kind", "POLYMORPHIC");
        Map<String, Object> composite = base();
        composite.put("join_kind", "COMPOSITE");
        Map<String, Object> unknown = base();
        unknown.put("join_kind", "RANGE");
        semanticsWithJoins(
                join("a_id", ConnectorSemanticService.V_CONFIRMED, poly),
                join("b_id", ConnectorSemanticService.V_NONE, composite),
                join("c_id", ConnectorSemanticService.V_WEAK, base()),
                join("d_id", ConnectorSemanticService.V_CONFIRMED, unknown));

        Map<String, Object> out = describe();

        assertThat(out).as("认不出的形态也不能当成单列关系").doesNotContainKey("joins");
        List<Map<String, Object>> rs = (List<Map<String, Object>>) out.get("unreliable_relations");
        assertThat(rs).hasSize(4);
        for (Map<String, Object> r : rs) {
            assertThat(String.valueOf(r.get("care_reason"))).as("care_reason of %s", r.get("column")).isNotBlank();
            assertThat(String.valueOf(r.get("condition"))).as("condition of %s", r.get("column")).isNotBlank();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void mixedRelations_landInTheirOwnBuckets() {
        Map<String, Object> composite = base();
        composite.put("join_kind", "COMPOSITE");
        semanticsWithJoins(
                join("order_id", ConnectorSemanticService.V_CONFIRMED, probe(1000, 1000, 1.0d, "N:1", true, null)),
                join("order_no", ConnectorSemanticService.V_CONFIRMED, composite));

        Map<String, Object> out = describe();

        assertThat((List<Map<String, Object>>) out.get("joins")).extracting(m -> m.get("column")).containsExactly("order_id");
        assertThat((List<Map<String, Object>>) out.get("unreliable_relations"))
                .extracting(m -> m.get("column")).containsExactly("order_no");
    }

    /** 被数据否掉的关系一个字都不该进模型上下文——不管它是什么形态，也不管库里存的是不是小写。 */
    @Test
    void rejected_isInjectedNowhere_whateverItsKindOrCase() {
        Map<String, Object> poly = probe(1000, 120, 0.12d, null, false, "左侧过半取值在右侧找不到，按错误关系处理");
        poly.put("join_kind", "POLYMORPHIC");
        semanticsWithJoins(
                join("order_id", ConnectorSemanticService.V_REJECTED, base()),
                join("order_id", "rejected", base()),
                join("resource_id", ConnectorSemanticService.V_REJECTED, poly));

        assertThat(describe()).doesNotContainKeys("joins", "unreliable_relations", "semantic_hint");
    }

    @Test
    void unrecognizedVerified_isNotInjected() {
        semanticsWithJoins(join("order_id", "MAYBE", base()));

        assertThat(describe()).doesNotContainKeys("joins", "unreliable_relations");
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstUnreliable(Map<String, Object> out) {
        List<Map<String, Object>> rs = (List<Map<String, Object>>) out.get("unreliable_relations");
        assertThat(rs).hasSize(1);
        return rs.get(0);
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

    /** 从一次已经拿到的 describe 结果里取字段——同一个用例里不必为了取值再调一次 describe。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldIn(Map<String, Object> out, String name) {
        List<Map<String, Object>> fields = (List<Map<String, Object>>) out.get("fields");
        return fields.stream().filter(f -> name.equals(f.get("name"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> valueDomainIn(Map<String, Object> out, String field) {
        Object vd = fieldIn(out, field).get("value_domain");
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

    /** 表级 OBJECT 行走 mapper（forObject 不带它），所以桩打在 semanticMapper 上。 */
    private void objectRow(String gloss, String status, String detailJson) {
        ConnectorSemantic r = new ConnectorSemantic();
        r.setScope(ConnectorSemanticService.SCOPE_OBJECT);
        r.setObjectName("t_ord");
        r.setFieldName("");
        r.setTerm("");
        r.setGloss(gloss);
        r.setStatus(status);
        r.setDetailJson(detailJson);
        when(semanticMapper.selectList(any())).thenReturn(List.of(r));
    }

    private static String shape(String shape, String source, String nameColumn, String valueColumn) {
        return shape(shape, source, nameColumn, valueColumn, null);
    }

    private static String shape(String shape, String source, String nameColumn, String valueColumn,
                                Map<String, Object> measurement) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (shape != null) d.put("table_shape", shape);
        if (source != null) d.put("table_shape_source", source);
        if (nameColumn != null) d.put("kv_name_column", nameColumn);
        if (valueColumn != null) d.put("kv_value_column", valueColumn);
        if (measurement != null) d.put("table_shape_measurement", measurement);
        return json(d);
    }

    /** 与 {@code TableShapeDetector.ShapeVerdict.measurementFragment()} 同形。 */
    private static Map<String, Object> measurement(String outcome, String nameColumn, String valueColumn, String basis) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", outcome);
        m.put("name_column", nameColumn);
        m.put("value_column", valueColumn);
        m.put("basis", basis);
        return m;
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

    /** 上线那一版值域阶段补写的行：推导侧 + FIELD + EV_DATA，gloss 把取值原样列在句子里，没有 origin 标记。 */
    private static ConnectorSemantic legacyValueRow(String detailJson) {
        ConnectorSemantic row = field("st",
                "取值只有这 2 种：华东大区、华南大区。（采样时点的完整集合，之后客户新增的取值不在其中）", detailJson);
        row.setSource(ConnectorSemanticService.SOURCE_INFERRED);
        row.setEvidence(ConnectorSemanticService.EV_DATA);
        return row;
    }

    /**
     * 验证侧对「t_ord.fromColumn → orders_p.id」给出的结构判定——<b>真实产出</b>，走公开的 {@code structureOnly}，
     * 再并上 S2 写下的两端。orders_p 的主键是 (id, create_time)：MySQL 分区表被迫的形状，本地 semval.orders_p 同形。
     * 左表刻意也有 create_time（同名、不同义）。{@code polymorphic=true} 时左表另有 resource_type，验证侧会判成「多态外键 + 组合键」。
     *
     * <p>不按位置 new 验证器：它的构造器随依赖增减而变，而 structureOnly 只读入参、不碰任何字段。
     */
    private static Map<String, Object> partitionedOrderRelation(String fromColumn, boolean polymorphic) {
        Map<String, FieldDetail> left = new LinkedHashMap<>();
        left.put(fromColumn, new FieldDetail(fromColumn, "bigint", false, null, null));
        left.put("create_time", new FieldDetail("create_time", "datetime", false, null, null));
        if (polymorphic) {
            left.put("resource_type", new FieldDetail("resource_type", "varchar(32)", false, null, null));
        }
        Map<String, FieldDetail> right = new LinkedHashMap<>();
        right.put("id", new FieldDetail("id", "bigint", false, null, null));
        right.put("create_time", new FieldDetail("create_time", "datetime", false, null, null));

        SemanticJoinValidator validator = mock(SemanticJoinValidator.class, CALLS_REAL_METHODS);
        Map<String, Object> structure = validator.structureOnly(
                SemanticJoinValidator.JoinCandidate.of("t_ord", fromColumn, "orders_p", "id"),
                Map.of("t_ord", left, "orders_p", right),
                Map.of("orders_p", List.of(List.of("id", "create_time"))));

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("to_object", "orders_p");
        d.put("to_column", "id");
        d.putAll(structure);
        return d;
    }

    /**
     * 验证侧对「t_ord.resource_id → t_ord_mst.id」给出的结构判定——<b>真实产出</b>（公开的 {@code structureOnly}），
     * 左表有 resource_type、对面不是组合键。第 1、2 档上多态外键存着的 care_reason 就是这一句。
     */
    private static Map<String, Object> polymorphicRelation() {
        Map<String, FieldDetail> left = new LinkedHashMap<>();
        left.put("resource_id", new FieldDetail("resource_id", "bigint", false, null, null));
        left.put("resource_type", new FieldDetail("resource_type", "varchar(32)", false, null, null));
        Map<String, FieldDetail> right = new LinkedHashMap<>();
        right.put("id", new FieldDetail("id", "bigint", false, null, null));

        SemanticJoinValidator validator = mock(SemanticJoinValidator.class, CALLS_REAL_METHODS);
        Map<String, Object> d = base();
        d.putAll(validator.structureOnly(
                SemanticJoinValidator.JoinCandidate.of("t_ord", "resource_id", "t_ord_mst", "id"),
                Map.of("t_ord", left, "t_ord_mst", right), Map.of()));
        return d;
    }

    /** 手写的多态外键行：care_reason 与 probed_with_sample_values（null = 不写这个键）由用例指定。 */
    private static Map<String, Object> polymorphicWithCare(String careReason, Object probedWithSampleValues) {
        Map<String, Object> d = base();
        d.put("join_kind", "POLYMORPHIC");
        d.put("discriminator_column", "resource_type");
        d.put("care_reason", careReason);
        if (probedWithSampleValues != null) {
            d.put("probed_with_sample_values", probedWithSampleValues);
        }
        return d;
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

    /** 只实现 describe 那一条路；其余方法本用例走不到。非 static：列从外层 {@link #liveFields} 取。 */
    private final class FakeSession implements ConnectorSession, DescribeCapable {

        @Override
        public com.jimeng.dataserver.ai.connector.model.CatalogView catalog() {
            return new com.jimeng.dataserver.ai.connector.model.CatalogView("TABLE",
                    List.of(new com.jimeng.dataserver.ai.connector.model.CatalogEntry("t_ord", "TABLE", "订单表")),
                    false, 1, null);
        }

        @Override
        public ObjectDetail describe(String object) {
            return new ObjectDetail("t_ord", "TABLE", "订单表", new ArrayList<>(liveFields), Map.of("engine", "InnoDB"));
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
