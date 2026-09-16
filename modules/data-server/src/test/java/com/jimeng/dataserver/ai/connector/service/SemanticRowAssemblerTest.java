package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.claude.service.ClaudeService;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.AssemblyReport;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.EntryOutcome;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.EntryRef;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.CODE_CAVEAT_TERM_ANSWERED;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.CODE_DUPLICATE_MERGED;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.CODE_EVIDENCE_GUESS;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.CODE_KEY_TOO_LONG;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.CODE_MISSING_REQUIRED;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.CODE_NAME_NOT_IN_SNAPSHOT;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.STATUS_DROPPED;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.STATUS_REJECTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code toRows} 从推导类抽到 {@link SemanticRowAssembler}（设计文档 7.8、13.4 的 C1）。
 *
 * <h3>这一组钉两件事</h3>
 * <ol>
 *   <li><b>抽取行为不变。</b>{@link Delegation} 用一份覆盖全部分支的模型产出走推导的公开入口 {@code derive()}，
 *       把 {@code replaceInferred} 的入参和 {@code DeriveResult} 逐字比对<b>抽取之前</b>录下的金样。
 *       金样是在旧代码上跑同一份输入得到的，不是照着新代码抄的——改动后它还相等，才说明委托没有改变任何一行的内容。</li>
 *   <li><b>逐条结果可用、且和计数对得上。</b>agent 靠 entries 知道哪一条为什么没进库；计数和逐条结果一旦分叉，
 *       管理台说「丢弃 3 条」、agent 却只看到 2 条原因，没有任何地方会报错。</li>
 * </ol>
 */
class SemanticRowAssemblerTest {

    private static final Long CONNECTOR_ID = 1L;

    /** 192 个字：刚好超过 varchar(191)。 */
    private static final String LONG_TERM = "口".repeat(192);

    /**
     * 覆盖 toRows 每一个分支的一份产出：接受、缺必填、名字对不上（含大小写不同）、GUESS（缺标签、认不出、显式）、
     * 同键合并（后到的高 / 低置信度）、数组里夹着非对象元素、非法基数、已答口径、口径词超长。
     */
    private static final String RICH_PAYLOAD = """
            {
              "objects": [
                {"name":"orders","gloss":"订单主表，一行一个订单头","evidence":"COMMENT","confidence":0.9,"table_shape":"detail","typical_questions":["上月下单量","本周退款数"]},
                {"name":"users","gloss":"用户表"},
                {"name":"t_ghost","gloss":"幽灵表","evidence":"NAME"},
                {"gloss":"没有名字","evidence":"NAME"},
                {"name":"ORDERS","gloss":"大小写不对","evidence":"NAME","confidence":95},
                {"name":"orders","gloss":"重复的订单表","evidence":"name","confidence":40},
                {"name":"shops","gloss":"门店","evidence":"VIBES"}
              ],
              "fields": [
                {"object":"orders","name":"amt","gloss":"订单金额","evidence":"COMMENT","confidence":80},
                {"object":"orders","name":"st","gloss":"0=待支付","evidence":"GUESS"},
                {"object":"orders","name":"nope","gloss":"不存在的列","evidence":"NAME"},
                {"object":"orders","name":"amt","gloss":"金额（高置信）","evidence":"COMMENT","confidence":99},
                {"object":"orders","gloss":"缺列名","evidence":"NAME"},
                "not a map",
                {"object":"users","name":"name","gloss":"用户名","evidence":"NAME","confidence":"abc"}
              ],
              "joins": [
                {"object":"orders","column":"cid","to_object":"users","to_column":"id","cardinality":"n:1","note":"名字像","confidence":70},
                {"object":"orders","column":"cid","to_object":"shops","to_column":"id","evidence":"NAME","confidence":50},
                {"object":"orders","column":"cid","to_object":"users","to_column":"nope"},
                {"object":"orders","column":"CID","to_object":"users","to_column":"id","evidence":"GUESS"},
                {"object":"orders","column":"cid","to_object":"users"},
                {"object":"orders","column":"st","to_object":"shops","to_column":"id","cardinality":"many","evidence":"DATA"}
              ],
              "ambiguities": [
                {"term":"销售额","question":"是否扣退款？","applies_to":["orders"]},
                {"term":"活跃用户","question":"怎么算活跃？","applies_to":["users","orders"]},
                {"term":"活跃用户 ","question":"重复的问题","applies_to":["users"]},
                {"question":"缺 term"},
                {"term":"%LONG%","question":"超长词条"}
              ]
            }
            """.replace("%LONG%", LONG_TERM);

    /**
     * ★ 抽取<b>之前</b>在旧代码（{@code ConnectorSemanticDeriveService} 里的私有 toRows + Stats）上，用
     * {@link #RICH_PAYLOAD} 跑 {@code derive()} 录下的：第一行是 {@code DeriveResult}，其余每行一条 replaceInferred 的入参，
     * 列依次为 scope | object | field | term | gloss | detail_json | evidence | confidence | anchor_kind | anchor_hash | verified | status。
     * <b>不要照着新代码的输出更新它</b>——它存在的意义就是「和抽取前一样」。
     */
    private static final String GOLDEN_BEFORE_EXTRACTION = """
            ConnectorSemanticDeriveService.DeriveResult(ok=true, objectCount=1, fieldCount=2, joinCount=2, caveatCount=1, droppedGuess=3, droppedUnknown=5, droppedTooLong=1, skippedAnswered=1, truncated=false, note=覆盖 3/3 个对象：表用途 1、字段含义 2、关系 2（均未经数据验证）、待确认口径 1 条。已丢弃：无外部依据 3、名字对不上结构 5、重复 4、超长 1 条。另有 1 条口径人已经答过，不再重复提问。)
            OBJECT | orders |  |  | 订单主表，一行一个订单头 | {"table_shape":"DETAIL","table_shape_source":"MODEL","typical_questions":["上月下单量","本周退款数"]} | COMMENT | 90 | NONE | null | NONE | DRAFT
            FIELD | orders | amt |  | 金额（高置信） | null | COMMENT | 99 | FIELD | ee08605b94b37da3e78193ae78eb73379b93c185cc71d01e945458b3d7c3f317 | NONE | DRAFT
            FIELD | users | name |  | 用户名 | null | NAME | null | FIELD | 974df0c5b32fdf3c67e437da942906de48aa5454c6538e724703fab55c537e47 | NONE | DRAFT
            JOIN | orders | cid |  | 关联 users.id（N:1）。名字像。本条未经数据验证，join 前建议先看该列的取值分布。 | {"to_object":"users","to_column":"id","cardinality":"N:1","basis":"未经数据验证","note":"名字像"} | NAME | 70 | JOIN | 183f78300cd6e59e9ec6cd732a24fef58a023e0268ddac6a62e8f5c3a4549805 | NONE | DRAFT
            JOIN | orders | st |  | 关联 shops.id。本条未经数据验证，join 前建议先看该列的取值分布。 | {"to_object":"shops","to_column":"id","cardinality":null,"basis":"未经数据验证"} | DATA | null | JOIN | 783b4d585c6fcd1bec97168dd8ccfe20997e844ef17c6780ec76860c7ac52069 | NONE | DRAFT
            CAVEAT |  |  | 活跃用户 | 怎么算活跃？ | {"applies_to":["users","orders"]} | null | null | NONE | null | NONE | DRAFT
            """;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ================================================================ 夹具

    private static FieldDetail col(String name, String type, String comment) {
        return new FieldDetail(name, type, true, comment, null);
    }

    /** 真实形状的快照行：detail_json 走生产那条 {@code toDetailMap}，不手写 JSON。 */
    private static ConnectorSchema snapshotOf(String name, String comment, FieldDetail... fields) {
        ConnectorSchema s = new ConnectorSchema();
        s.setConnectorId(CONNECTOR_ID);
        s.setObjectName(name);
        s.setObjectType("BASE TABLE");
        s.setObjectComment(comment);
        ObjectDetail d = new ObjectDetail(name, "BASE TABLE", comment, List.of(fields), Map.of());
        try {
            s.setDetailJson(CommonUtil.getObjectMapper().writeValueAsString(ConnectorSchemaService.toDetailMap(d)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    /** orders(id, cid, st, amt) / users(id, name) / shops(id)，与推导测试的常用底座同形。 */
    private static List<ConnectorSchema> snapshot() {
        return List.of(
                snapshotOf("orders", "订单表", col("id", "bigint", "主键"), col("cid", "bigint", null),
                        col("st", "tinyint", null), col("amt", "decimal(12,2)", "金额")),
                snapshotOf("users", "用户表", col("id", "bigint", "主键"), col("name", "varchar(64)", null)),
                snapshotOf("shops", null, col("id", "bigint", "主键")));
    }

    private static ConnectorSemantic answeredMetric(String term) {
        ConnectorSemantic m = new ConnectorSemantic();
        m.setScope(ConnectorSemanticService.SCOPE_METRIC);
        m.setTerm(term);
        m.setStatus(ConnectorSemanticService.ST_CONFIRMED);
        m.setSource(ConnectorSemanticService.SOURCE_HUMAN);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        try {
            return CommonUtil.getObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new AssertionError("这段 JSON 解析不了：" + json, e);
        }
    }

    private static long count(AssemblyReport r, String code) {
        return r.outcomes().stream().filter(o -> code.equals(o.code())).count();
    }

    /** 一条 S1 语料关系行，形状与推导类 corpusRow 产出的一致（toRows 只看键列、evidence、confidence）。 */
    private static ConnectorSemantic corpusJoin(String obj, String col, int confidence) {
        ConnectorSemantic row = SemanticRowAssembler.base(ConnectorSemanticService.SCOPE_JOIN, obj, col, "");
        row.setGloss("关联 users.id。来自视图 v_order");
        row.setEvidence(ConnectorSemanticService.EV_COMMENT);
        row.setConfidence(confidence);
        row.setAnchorKind(ConnectorSemanticService.ANCHOR_JOIN);
        return row;
    }

    // ================================================================ 逐条结果

    @Nested
    @DisplayName("逐条结果：每个跳过分支、每次同键合并各记一条，和计数器对得上")
    class Outcomes {

        /**
         * ★ 守恒：输入里每一个 JSON 对象条目，要么变成了一行（能用 originOf 找回它来自哪一条），要么恰好有一条结果——
         * 不多不少。少了是「静默丢弃」（agent 以为交上了），多了是「一条记两次」（agent 被要求修一条已经进库的东西）。
         */
        @Test
        @DisplayName("★ 每个结果码的条数等于对应计数器；进库行 + 结果 = 输入条目数，且同一条目不重复出现")
        void 逐条结果与原计数一致() {
            List<ConnectorSchema> rows = snapshot();
            AssemblyReport report = new AssemblyReport();
            List<ConnectorSemantic> out = SemanticRowAssembler.toRows(parse(RICH_PAYLOAD),
                    SemanticRowAssembler.parseFields(rows),
                    SemanticRowAssembler.answeredTerms(List.of(answeredMetric("销售额"))), report, List.of());

            // 计数器本身钉死成抽取前的数（与金样第一行同源），再逐项对逐条结果
            assertEquals(3, report.getDroppedGuess());
            assertEquals(5, report.getDroppedUnknown());
            assertEquals(4, report.getDroppedDup());
            assertEquals(1, report.getDroppedTooLong());
            assertEquals(1, report.getDroppedAnswered());
            assertEquals(4, report.getMissingRequired(), "缺必填的四条：objects[3] fields[4] joins[4] ambiguities[3]");

            assertEquals(report.getDroppedGuess(), count(report, CODE_EVIDENCE_GUESS));
            assertEquals(report.getDroppedUnknown(), count(report, CODE_NAME_NOT_IN_SNAPSHOT));
            assertEquals(report.getDroppedDup(), count(report, CODE_DUPLICATE_MERGED));
            assertEquals(report.getDroppedTooLong(), count(report, CODE_KEY_TOO_LONG));
            assertEquals(report.getDroppedAnswered(), count(report, CODE_CAVEAT_TERM_ANSWERED));
            assertEquals(report.getMissingRequired(), count(report, CODE_MISSING_REQUIRED));
            assertEquals(report.outcomes().size(), report.getDroppedGuess() + report.getDroppedUnknown()
                    + report.getDroppedDup() + report.getDroppedTooLong() + report.getDroppedAnswered()
                    + report.getMissingRequired(), "不应有计数器之外的结果");

            assertEquals(out.size(), report.getObjects() + report.getFields() + report.getJoins() + report.getCaveats());

            // 输入里的 JSON 对象条目：objects 7、fields 6（"not a map" 不算）、joins 6、ambiguities 5
            int inputEntries = 7 + 6 + 6 + 5;
            assertEquals(inputEntries, out.size() + report.outcomes().size());

            Set<String> seen = new HashSet<>();
            for (ConnectorSemantic r : out) {
                EntryRef ref = report.originOf(r);
                assertNotNull(ref, "进库的行找不到来源：" + r.getScope() + " " + r.getObjectName());
                assertTrue(seen.add(ref.path()), "同一条目既进了库又被重复登记：" + ref.path());
            }
            for (EntryOutcome o : report.outcomes()) {
                assertTrue(seen.add(o.path()), "同一条目出现了两次：" + o.path());
                assertTrue(STATUS_REJECTED.equals(o.status()) || STATUS_DROPPED.equals(o.status()));
                assertFalse(o.message() == null || o.message().isBlank(), "结果没有给 agent 看的文案：" + o.path());
            }
            // "not a map" 占着 fields[5]，不产生结果；它后面那条的下标仍是 6
            assertFalse(seen.contains("fields[5]"));
            assertTrue(seen.contains("fields[6]"));
        }

        static Stream<Arguments> branches() {
            return Stream.of(
                    // 名称, 产出 JSON, 语料关系, kind, index, key, status, code, 文案片段
                    Arguments.of("OBJECT 缺 gloss", "{\"objects\":[{\"name\":\"orders\",\"evidence\":\"COMMENT\"}]}", List.of(),
                            "objects", 0, "orders", STATUS_REJECTED, CODE_MISSING_REQUIRED, "gloss"),
                    Arguments.of("OBJECT 表不在快照", "{\"objects\":[{\"name\":\"t_ghost\",\"gloss\":\"x\",\"evidence\":\"NAME\"}]}", List.of(),
                            "objects", 0, "t_ghost", STATUS_REJECTED, CODE_NAME_NOT_IN_SNAPSHOT, "t_ghost"),
                    Arguments.of("OBJECT 缺依据标签按 GUESS", "{\"objects\":[{\"name\":\"orders\",\"gloss\":\"订单\"}]}", List.of(),
                            "objects", 0, "orders", STATUS_DROPPED, CODE_EVIDENCE_GUESS, "GUESS"),
                    Arguments.of("FIELD 缺 name", "{\"fields\":[{\"object\":\"orders\",\"gloss\":\"x\",\"evidence\":\"NAME\"}]}", List.of(),
                            "fields", 0, "orders.", STATUS_REJECTED, CODE_MISSING_REQUIRED, "name"),
                    Arguments.of("FIELD 列名大小写不对", "{\"fields\":[{\"object\":\"orders\",\"name\":\"AMT\",\"gloss\":\"x\",\"evidence\":\"NAME\"}]}", List.of(),
                            "fields", 0, "orders.AMT", STATUS_REJECTED, CODE_NAME_NOT_IN_SNAPSHOT, "orders.AMT"),
                    Arguments.of("FIELD 标 GUESS", "{\"fields\":[{\"object\":\"orders\",\"name\":\"st\",\"gloss\":\"0=待支付\",\"evidence\":\"guess\"}]}", List.of(),
                            "fields", 0, "orders.st", STATUS_DROPPED, CODE_EVIDENCE_GUESS, "不入库"),
                    Arguments.of("JOIN 缺 to_column", "{\"joins\":[{\"object\":\"orders\",\"column\":\"cid\",\"to_object\":\"users\"}]}", List.of(),
                            "joins", 0, "orders.cid", STATUS_REJECTED, CODE_MISSING_REQUIRED, "to_column"),
                    Arguments.of("JOIN 右端列不在快照", "{\"joins\":[{\"object\":\"orders\",\"column\":\"cid\",\"to_object\":\"users\",\"to_column\":\"nope\"}]}", List.of(),
                            "joins", 0, "orders.cid", STATUS_REJECTED, CODE_NAME_NOT_IN_SNAPSHOT, "users.nope"),
                    Arguments.of("JOIN 显式 GUESS", "{\"joins\":[{\"object\":\"orders\",\"column\":\"cid\",\"to_object\":\"users\",\"to_column\":\"id\",\"evidence\":\"GUESS\"}]}", List.of(),
                            "joins", 0, "orders.cid", STATUS_DROPPED, CODE_EVIDENCE_GUESS, "GUESS"),
                    Arguments.of("CAVEAT 缺 question", "{\"ambiguities\":[{\"term\":\"活跃用户\"}]}", List.of(),
                            "ambiguities", 0, "活跃用户", STATUS_REJECTED, CODE_MISSING_REQUIRED, "question"),
                    Arguments.of("CAVEAT 口径已答（大小写与空白不同）", "{\"ambiguities\":[{\"term\":\" 销售额 \",\"question\":\"扣不扣退款\"}]}", List.of(),
                            "ambiguities", 0, "销售额", STATUS_DROPPED, CODE_CAVEAT_TERM_ANSWERED, "『销售额』"),
                    Arguments.of("CAVEAT 词条超过 191 字", "{\"ambiguities\":[{\"term\":\"" + LONG_TERM + "\",\"question\":\"q\"}]}", List.of(),
                            "ambiguities", 0, LONG_TERM, STATUS_REJECTED, CODE_KEY_TOO_LONG, "191"),
                    Arguments.of("FIELD 同键后到的置信度高：被顶掉的是先到那条", "{\"fields\":["
                                    + "{\"object\":\"orders\",\"name\":\"amt\",\"gloss\":\"a\",\"evidence\":\"COMMENT\",\"confidence\":60},"
                                    + "{\"object\":\"orders\",\"name\":\"amt\",\"gloss\":\"b\",\"evidence\":\"COMMENT\",\"confidence\":90}]}", List.of(),
                            "fields", 0, "orders.amt", STATUS_DROPPED, CODE_DUPLICATE_MERGED, "fields[1]"),
                    Arguments.of("下标按原数组计：非对象元素占位", "{\"fields\":[\"x\",{\"object\":\"orders\",\"name\":\"st\",\"gloss\":\"g\",\"evidence\":\"VIBES\"}]}", List.of(),
                            "fields", 1, "orders.st", STATUS_DROPPED, CODE_EVIDENCE_GUESS, "GUESS"),
                    Arguments.of("语料关系顶掉模型同一列的关系", "{\"joins\":[{\"object\":\"orders\",\"column\":\"cid\",\"to_object\":\"shops\",\"to_column\":\"id\",\"evidence\":\"NAME\",\"confidence\":99}]}",
                            List.of(corpusJoin("orders", "cid", 2)),
                            "joins", 0, "orders.cid", STATUS_DROPPED, CODE_DUPLICATE_MERGED, "SQL 语料"));
        }

        /**
         * 表驱动：每条输入只触发一个分支，断言它落成的那一条结果的全部字段。path 必须与 agent 提交的数组逐字对得上，
         * code 必须是约定的机器码（会写进 last_reject_reason），status 决定 agent 要不要重提。
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("branches")
        @DisplayName("每个 continue 分支与同键合并：kind / index / key / status / code 与文案")
        void 各continue分支的code正确(String caseName, String payload, List<ConnectorSemantic> corpus,
                                 String kind, int index, String key, String status, String code, String fragment) {
            AssemblyReport report = new AssemblyReport();
            SemanticRowAssembler.toRows(parse(payload), SemanticRowAssembler.parseFields(snapshot()),
                    SemanticRowAssembler.answeredTerms(List.of(answeredMetric("销售额"))), report, corpus);

            assertEquals(1, report.outcomes().size(), caseName + "：期望恰好一条结果，实际 " + report.outcomes());
            EntryOutcome o = report.outcomes().get(0);
            assertEquals(kind, o.kind(), caseName);
            assertEquals(index, o.index(), caseName);
            assertEquals(kind + "[" + index + "]", o.path(), caseName);
            assertEquals(key, o.key(), caseName);
            assertEquals(status, o.status(), caseName);
            assertEquals(code, o.code(), caseName);
            assertTrue(o.message().contains(fragment), caseName + "：文案里没有「" + fragment + "」：" + o.message());
        }
    }

    // ================================================================ 委托

    @Nested
    @DisplayName("推导类改为委托之后，单次推导的产出逐字不变")
    class Delegation {

        /**
         * ★ 金样对拍：同一份输入走 {@code derive()}，replaceInferred 的入参与 DeriveResult 必须与抽取前一模一样。
         * 再用同一份输入直接调 {@link SemanticRowAssembler#toRows}，DeriveResult 的每个计数都要等于 AssemblyReport 的对应计数器——
         * 这是「把 AssemblyReport 的计数器映射回 DeriveResult」这一步本身有没有接错线。
         */
        @Test
        @DisplayName("★ derive() 的落库行与 DeriveResult 等于抽取前录下的金样，且各计数与 AssemblyReport 一一对应")
        void 委托后DeriveResult不变() {
            ConnectorSchemaService schemaService = mock(ConnectorSchemaService.class);
            ConnectorSemanticService semanticService = mock(ConnectorSemanticService.class);
            ConnectionMapper connectionMapper = mock(ConnectionMapper.class);
            ClaudeService claudeService = mock(ClaudeService.class);
            ConnectorSemanticDeriveService service = new ConnectorSemanticDeriveService(schemaService, semanticService,
                    connectionMapper, claudeService, new ConnectorProperties(),
                    mock(SemanticSqlCorpusReader.class), mock(SemanticJoinValidator.class),
                    mock(SemanticValueProfiler.class), mock(ConnectorSemanticMapper.class),
                    mock(ConnectorAuditService.class),
                    mock(ThreadPoolTaskExecutor.class), mock(ThreadPoolTaskExecutor.class),
                    mock(TableShapeDetector.class), mock(org.redisson.api.RedissonClient.class));
            Connection conn = new Connection();
            conn.setId(CONNECTOR_ID);
            conn.setName("客户生产库");
            conn.setKind("MYSQL");
            conn.setTenantId("t1");
            when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(conn);
            when(connectionMapper.update(any(), any())).thenReturn(1);
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(snapshot());
            List<ConnectorSemantic> existing = List.of(answeredMetric("销售额"));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(existing);
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "text");
            block.put("text", RICH_PAYLOAD);
            when(claudeService.messagesInternal(any(), any())).thenReturn(Map.of("content", List.of(block)));

            ConnectorSemanticDeriveService.DeriveResult result = service.derive(CONNECTOR_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ConnectorSemantic>> cap = ArgumentCaptor.forClass(List.class);
            verify(semanticService).replaceInferred(eq(CONNECTOR_ID), cap.capture());
            StringBuilder actual = new StringBuilder().append(result).append('\n');
            for (ConnectorSemantic x : cap.getValue()) {
                actual.append(x.getScope()).append(" | ").append(x.getObjectName()).append(" | ")
                        .append(x.getFieldName()).append(" | ").append(x.getTerm()).append(" | ")
                        .append(x.getGloss()).append(" | ").append(x.getDetailJson()).append(" | ")
                        .append(x.getEvidence()).append(" | ").append(x.getConfidence()).append(" | ")
                        .append(x.getAnchorKind()).append(" | ").append(x.getAnchorHash()).append(" | ")
                        .append(x.getVerified()).append(" | ").append(x.getStatus()).append('\n');
            }
            assertEquals(GOLDEN_BEFORE_EXTRACTION, actual.toString());

            AssemblyReport report = new AssemblyReport();
            SemanticRowAssembler.toRows(parse(RICH_PAYLOAD), SemanticRowAssembler.parseFields(snapshot()),
                    SemanticRowAssembler.answeredTerms(existing), report, List.of());
            assertTrue(result.isOk());
            assertEquals(report.getObjects(), result.getObjectCount());
            assertEquals(report.getFields(), result.getFieldCount());
            assertEquals(report.getJoins(), result.getJoinCount());
            assertEquals(report.getCaveats(), result.getCaveatCount());
            assertEquals(report.getDroppedGuess(), result.getDroppedGuess());
            assertEquals(report.getDroppedUnknown(), result.getDroppedUnknown());
            assertEquals(report.getDroppedTooLong(), result.getDroppedTooLong());
            assertEquals(report.getDroppedAnswered(), result.getSkippedAnswered());
            // 新增的缺必填计数不进推导摘要：摘要措辞与抽取前一致（已由金样钉住），这里再显式说一次
            assertFalse(result.getNote().contains("缺少"));
        }
    }
}
