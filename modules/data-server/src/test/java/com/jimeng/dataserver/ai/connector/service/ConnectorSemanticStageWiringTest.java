package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.claude.service.ClaudeService;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>接线</b>那一层：S1 怎么进说明书、S3/S4 怎么在说明书生成之后把结论补回去。
 *
 * <h3>这个类钉的不是 S1/S3/S4 本身</h3>
 * 那三个类各有自己的用例（{@code SemanticSqlCorpusReaderTest} / {@code SemanticJoinValidatorTest} /
 * {@code SemanticValueProfilerTest}），这里全部 mock 掉。本类只回答一个问题：
 * <b>把它们接起来的那几十行，会不会在不报错的情况下把事情做反。</b>
 * 集成层的失效方式全是静默的——大小写差一个字母就少几十条关系；{@code verified} 忘了写，
 * 采样跑完了而模型眼里什么都没变；「没探查」被当成「探查了判不出来」，一次没跑的验证看起来像跑过。
 */
class ConnectorSemanticStageWiringTest {

    private static final Long CONNECTOR_ID = 7L;
    private static final String TENANT = "t1";

    private ConnectorSchemaService schemaService;
    private ConnectorSemanticService semanticService;
    private ConnectionMapper connectionMapper;
    private ClaudeService claudeService;
    private SemanticSqlCorpusReader corpusReader;
    private SemanticJoinValidator joinValidator;
    private SemanticValueProfiler valueProfiler;
    private ConnectorSemanticMapper semanticMapper;
    private ConnectorAuditService auditService;
    private ThreadPoolTaskExecutor streamExecutor;
    private ThreadPoolTaskExecutor semanticStageExecutor;
    private TableShapeDetector shapeDetector;
    private ConnectorSemanticDeriveService service;

    @BeforeEach
    void setUp() {
        schemaService = mock(ConnectorSchemaService.class);
        semanticService = mock(ConnectorSemanticService.class);
        connectionMapper = mock(ConnectionMapper.class);
        claudeService = mock(ClaudeService.class);
        corpusReader = mock(SemanticSqlCorpusReader.class);
        joinValidator = mock(SemanticJoinValidator.class);
        valueProfiler = mock(SemanticValueProfiler.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        auditService = mock(ConnectorAuditService.class);
        streamExecutor = mock(ThreadPoolTaskExecutor.class);
        semanticStageExecutor = mock(ThreadPoolTaskExecutor.class);
        shapeDetector = mock(TableShapeDetector.class);
        service = new ConnectorSemanticDeriveService(schemaService, semanticService, connectionMapper,
                claudeService, new ConnectorProperties(), corpusReader, joinValidator, valueProfiler,
                semanticMapper, auditService, streamExecutor, semanticStageExecutor, shapeDetector,
                mock(org.redisson.api.RedissonClient.class));

        Connection conn = new Connection();
        conn.setId(CONNECTOR_ID);
        conn.setName("客户生产库");
        conn.setKind("MYSQL");
        conn.setTenantId(TENANT);
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(conn);
        when(connectionMapper.update(any(), any())).thenReturn(1);
        when(semanticMapper.updateById(any())).thenReturn(1);
        // S1 走的是 executeAsPlatform，它要真实租户；没有这一行整条语料路径会被跳过。
        TenantContext.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ================================================================ 夹具

    private static FieldDetail col(String name, String type) {
        return new FieldDetail(name, type, true, null, null);
    }

    private static ConnectorSchema snapshotOf(String name, FieldDetail... fields) {
        ConnectorSchema s = new ConnectorSchema();
        s.setConnectorId(CONNECTOR_ID);
        s.setObjectName(name);
        s.setObjectType("BASE TABLE");
        ObjectDetail d = new ObjectDetail(name, "BASE TABLE", null, List.of(fields), Map.of());
        try {
            s.setDetailJson(CommonUtil.getObjectMapper()
                    .writeValueAsString(ConnectorSchemaService.toDetailMap(d)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    /** 快照里的写法一律小写，语料里的写法故意给大写——这正是 lower_case_table_names 造成的那种错开。 */
    private List<ConnectorSchema> defaultSnapshot() {
        List<ConnectorSchema> rows = List.of(
                snapshotOf("orders", col("id", "bigint"), col("cid", "bigint"), col("st", "tinyint")),
                snapshotOf("users", col("id", "bigint"), col("name", "varchar(64)")),
                snapshotOf("shops", col("id", "bigint")));
        when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(rows);
        return rows;
    }

    private void modelOutputs(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("content", List.of(block));
        when(claudeService.messagesInternal(any(), any())).thenReturn(resp);
    }

    private static SemanticSqlCorpusReader.CorpusJoin corpusJoin(String lo, String lc, String ro, String rc,
                                                                 String... viewNames) {
        SemanticSqlCorpusReader.CorpusJoin j = new SemanticSqlCorpusReader.CorpusJoin();
        j.setLeftObject(lo);
        j.setLeftColumn(lc);
        j.setRightObject(ro);
        j.setRightColumn(rc);
        List<SemanticSqlCorpusReader.CorpusSource> sources = new ArrayList<>();
        for (String v : viewNames) {
            SemanticSqlCorpusReader.CorpusSource s = new SemanticSqlCorpusReader.CorpusSource();
            s.setKind(SemanticSqlCorpusReader.SRC_VIEW);
            s.setName(v);
            sources.add(s);
        }
        j.setSources(sources);
        return j;
    }

    private void corpusReturns(SemanticSqlCorpusReader.CorpusJoin... joins) {
        SemanticSqlCorpusReader.CorpusResult r = new SemanticSqlCorpusReader.CorpusResult();
        r.setOk(true);
        r.setViewsSeen(joins.length);
        r.setViewsParsed(joins.length);
        r.setJoins(new ArrayList<>(List.of(joins)));
        when(corpusReader.read(CONNECTOR_ID)).thenReturn(r);
    }

    private List<ConnectorSemantic> captureWrittenRows() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConnectorSemantic>> cap = ArgumentCaptor.forClass(List.class);
        verify(semanticService).replaceInferred(eq(CONNECTOR_ID), cap.capture());
        return cap.getValue();
    }

    private static ConnectorSemantic find(List<ConnectorSemantic> rows, String scope, String obj, String field) {
        return rows.stream()
                .filter(r -> scope.equals(r.getScope()) && obj.equals(r.getObjectName())
                        && field.equals(r.getFieldName()))
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailOf(ConnectorSemantic row) {
        try {
            return CommonUtil.getObjectMapper().readValue(row.getDetailJson(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 一条已经落库的 JOIN 行，形状与推导写出来的一致。 */
    private static ConnectorSemantic joinRow(long id, String obj, String col, String toObj, String toCol,
                                             String source, String verified) {
        ConnectorSemantic r = new ConnectorSemantic();
        r.setId(id);
        r.setConnectorId(CONNECTOR_ID);
        r.setTenantId(TENANT);
        r.setScope(ConnectorSemanticService.SCOPE_JOIN);
        r.setObjectName(obj);
        r.setFieldName(col);
        r.setTerm("");
        r.setSource(source);
        r.setVerified(verified);
        r.setGloss("关联 " + toObj + "." + toCol + "。本条未经数据验证。");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("to_object", toObj);
        d.put("to_column", toCol);
        d.put("basis", "未经数据验证");
        try {
            r.setDetailJson(CommonUtil.getObjectMapper().writeValueAsString(d));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return r;
    }

    private static SemanticJoinValidator.JoinVerdict verdict(String obj, String col, String toObj, String toCol,
                                                             String verified, String cardinality, boolean probed) {
        return SemanticJoinValidator.JoinVerdict.builder()
                .fromObject(obj).fromColumn(col).toObject(toObj).toColumn(toCol)
                .verified(verified)
                .cardinality(cardinality)
                .autoJoinable(SemanticJoinValidator.CARD_MANY_ONE.equals(cardinality))
                .sampleN(probed ? 1000 : null)
                .matchN(probed ? 970 : null)
                .containment(probed ? 0.97d : null)
                .basis(probed ? "采样 1000 个取值，命中 970（包含率 97.0%）" : "未经数据验证")
                .probed(probed)
                .build();
    }

    /** 让 mock 的验证器把 verdicts 一条条喂回 ProbeProgress，正如真实实现那样。 */
    private void validatorReturns(String outcome, SemanticJoinValidator.JoinVerdict... verdicts) {
        List<SemanticJoinValidator.JoinVerdict> list = List.of(verdicts);
        // 五参版本：第 4 个参数是唯一键，回调挪到了第 5 个。接线方调四参版本就等于组合键判定在线上不生效。
        when(joinValidator.validate(eq(CONNECTOR_ID), any(), any(), any(), any())).thenAnswer(inv -> {
            SemanticJoinValidator.ProbeProgress p = inv.getArgument(4);
            if (p != null) {
                for (int i = 0; i < list.size(); i++) {
                    if (!p.onDecided(list.get(i), i + 1, list.size())) {
                        break;
                    }
                }
            }
            return SemanticJoinValidator.JoinValidationResult.builder()
                    .outcome(outcome).note("测试").verdicts(list)
                    .probeCount(list.size()).sampledRows(1000L).build();
        });
    }

    private void valueProfilerDisabled() {
        when(valueProfiler.profile(anyLong(), any())).thenReturn(
                SemanticValueProfiler.ValueProfile.builder()
                        .tierAllowed(false)
                        .tierStatement(SemanticValueProfiler.NOT_ENABLED_NOTE)
                        .build());
    }

    // ================================================================ S1：语料关系进说明书

    @Nested
    @DisplayName("S1 既有 SQL 语料")
    class Corpus {

        @Test
        @DisplayName("语料关系落成 JOIN 行：evidence=COMMENT、verified=NONE、basis 里留着「未经数据验证」")
        void corpusJoinBecomesUnverifiedRow() {
            defaultSnapshot();
            modelOutputs("{\"objects\":[],\"fields\":[],\"joins\":[],\"ambiguities\":[]}");
            corpusReturns(corpusJoin("orders", "cid", "users", "id", "v_order_user"));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());

            service.derive(CONNECTOR_ID);

            ConnectorSemantic row = find(captureWrittenRows(),
                    ConnectorSemanticService.SCOPE_JOIN, "orders", "cid");
            assertNotNull(row, "语料挖到的关系必须进说明书，否则 S1 整个阶段等于没接");
            assertEquals(ConnectorSemanticService.EV_COMMENT, row.getEvidence(),
                    "视图里的 join 是客户自己写在库里的一句话，不是我们猜的");
            // ★ 这一条最要紧：视图里的等值条件不是「数据对得上」的证据。
            //   标成已验证之后 S3 再也不会去核它（候选按 verified==NONE 挑），
            //   于是一条从没被验证过的关系永远不会被验证。
            assertEquals(ConnectorSemanticService.V_NONE, row.getVerified());
            Map<String, Object> d = detailOf(row);
            assertTrue(String.valueOf(d.get("basis")).contains("未经数据验证"), "basis 必须留着这四个字");
            assertTrue(String.valueOf(d.get("basis")).contains("v_order_user"), "basis 要能让人回到那个视图去核");
            assertNull(d.get("cardinality"), "视图里的一个等值条件不含任何基数信息");
            assertEquals("users", d.get("to_object"));
            assertEquals("id", d.get("to_column"));
            assertEquals(ConnectorSemanticService.ANCHOR_JOIN, row.getAnchorKind());
            assertNotNull(row.getAnchorHash(), "没有锚点就永远不会被漂移检测标 STALE");
        }

        @Test
        @DisplayName("★ 大小写对不上快照时仍然匹配得上，并按快照的写法落库")
        void matchesSnapshotCaseInsensitively() {
            defaultSnapshot();   // 快照里是 orders / cid / users / id
            modelOutputs("{\"objects\":[]}");
            // 语料返回的是【视图正文里的写法】，取决于客户那台机器的 lower_case_table_names
            corpusReturns(corpusJoin("ORDERS", "CID", "Users", "ID", "v_x"));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());

            service.derive(CONNECTOR_ID);

            List<ConnectorSemantic> rows = captureWrittenRows();
            // 严格匹配会让这里是 null，而表现只是「这次挖到 0 条」——不报错、没人会发现。
            ConnectorSemantic row = find(rows, ConnectorSemanticService.SCOPE_JOIN, "orders", "cid");
            assertNotNull(row, "大小写敏感的匹配会静默丢掉这条关系");
            Map<String, Object> d = detailOf(row);
            // 落库必须是快照的写法：锚点、注入层、S3 的候选都要拿它去和快照精确对齐。
            assertEquals("users", d.get("to_object"));
            assertEquals("id", d.get("to_column"));
        }

        @Test
        @DisplayName("语料关系压过模型在同一列上猜的那条（撞的是唯一键，不是先来后到）")
        void corpusBeatsModelGuessOnSameKey() {
            defaultSnapshot();
            // 模型把 orders.cid 猜成指向 shops，还给了 95 的高把握
            modelOutputs("{\"joins\":[{\"object\":\"orders\",\"column\":\"cid\","
                    + "\"to_object\":\"shops\",\"to_column\":\"id\",\"evidence\":\"NAME\",\"confidence\":95}]}");
            corpusReturns(corpusJoin("orders", "cid", "users", "id", "v_order_user"));
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());

            service.derive(CONNECTOR_ID);

            ConnectorSemantic row = find(captureWrittenRows(),
                    ConnectorSemanticService.SCOPE_JOIN, "orders", "cid");
            assertNotNull(row);
            // 两个 confidence 不是一把尺子：模型那个是「我有多大把握」，语料那个是「见过几处」。
            // 比大小的话，一条人真的写过的关系会被一条名字像的猜测挤掉。
            assertEquals("users", detailOf(row).get("to_object"));
            assertEquals(ConnectorSemanticService.EV_COMMENT, row.getEvidence());
        }

        @Test
        @DisplayName("★ 模型颗粒无收时，光靠语料的几条关系不能替换上一版说明书")
        void corpusAloneDoesNotWipeThePreviousManual() {
            defaultSnapshot();
            modelOutputs("{\"objects\":[],\"fields\":[],\"joins\":[],\"ambiguities\":[]}");
            corpusReturns(corpusJoin("orders", "cid", "users", "id", "v_x"));
            ConnectorSemantic old = new ConnectorSemantic();
            old.setScope(ConnectorSemanticService.SCOPE_FIELD);
            old.setSource(ConnectorSemanticService.SOURCE_INFERRED);
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(old));

            ConnectorSemanticDeriveService.DeriveResult r = service.derive(CONNECTOR_ID);

            assertFalse(r.isOk());
            // replaceInferred 是先物理删再插。放行的话，上一版几百条字段含义会被几条关系换掉，
            // 而且状态写成 READY——一次看起来成功的重新生成。
            verify(semanticService, never()).replaceInferred(anyLong(), any());
        }

        @Test
        @DisplayName("语料读失败不影响推导，只把原因写进 note")
        void corpusFailureIsNotFatal() {
            defaultSnapshot();
            modelOutputs("{\"objects\":[{\"name\":\"orders\",\"gloss\":\"订单主表\",\"evidence\":\"NAME\"}]}");
            SemanticSqlCorpusReader.CorpusResult bad = new SemanticSqlCorpusReader.CorpusResult();
            bad.setOk(false);
            bad.setNote("读取既有 SQL 语料失败：账号没有 SHOW VIEW 权限");
            when(corpusReader.read(CONNECTOR_ID)).thenReturn(bad);
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());

            ConnectorSemanticDeriveService.DeriveResult r = service.derive(CONNECTOR_ID);

            assertTrue(r.isOk(), "语料是锦上添花，它失败不该把一次正常推导拖成失败");
            assertTrue(r.getNote().contains("SHOW VIEW"), "失败原因必须出现在给人看的那行字里");
        }
    }

    // ================================================================ 阶段派发

    @Nested
    @DisplayName("采样验证阶段的派发")
    class Dispatch {

        @Test
        @DisplayName("★ 推导成功后派到【阶段池】，绝不派到 streamExecutor")
        void dispatchedToTheDedicatedPool() {
            defaultSnapshot();
            modelOutputs("{\"objects\":[{\"name\":\"orders\",\"gloss\":\"订单主表\",\"evidence\":\"NAME\"}]}");
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());

            assertTrue(service.derive(CONNECTOR_ID).isOk());

            verify(semanticStageExecutor).execute(any(Runnable.class));
            // streamExecutor 是 SynchronousQueue + CallerRunsPolicy：池满时任务就地跑在调用线程上。
            // 把一个最长半小时的作业放上去，等于给某次建连请求加上半小时。
            verify(streamExecutor, never()).execute(any(Runnable.class));
        }

        @Test
        @DisplayName("推导失败就不派发：没有说明书可验")
        void notDispatchedWhenDeriveFails() {
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(List.of());

            assertFalse(service.derive(CONNECTOR_ID).isOk());

            verify(semanticStageExecutor, never()).execute(any(Runnable.class));
        }
    }

    // ================================================================ S3 结论落库

    @Nested
    @DisplayName("S3 表关系验证结论落库")
    class JoinPersistence {

        @Test
        @DisplayName("★ 不成立的关系必须真的写成 REJECTED（注入层就是靠这一列不展示它的）")
        void rejectedIsPersistedAsRejected() {
            defaultSnapshot();
            valueProfilerDisabled();
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(
                    joinRow(1L, "orders", "cid", "users", "id",
                            ConnectorSemanticService.SOURCE_INFERRED, ConnectorSemanticService.V_NONE)));
            validatorReturns(SemanticJoinValidator.OUT_RAN,
                    verdict("orders", "cid", "users", "id",
                            ConnectorSemanticService.V_REJECTED, null, true));

            ConnectorSemanticDeriveService.ValidationResult r = service.validate(CONNECTOR_ID);

            assertTrue(r.isOk());
            assertEquals(1, r.getJoinRejected());
            ArgumentCaptor<ConnectorSemantic> cap = ArgumentCaptor.forClass(ConnectorSemantic.class);
            verify(semanticMapper).updateById(cap.capture());
            ConnectorSemantic u = cap.getValue();
            assertEquals(1L, u.getId());
            // 只把细账并进 detail_json、不动 verified，等于验证跑完了、客户的配额花完了，
            // 而模型眼里什么都没变。
            assertEquals(ConnectorSemanticService.V_REJECTED, u.getVerified());
            assertTrue(u.getGloss().contains("不成立"), "gloss 里那句「未经数据验证」验完还留着就是说反话");
        }

        @Test
        @DisplayName("★「没探查」（V_NONE）又没有结构判定时一个字都不写：写了会抹掉这条关系唯一的来源线索")
        void notProbedVerdictWritesNothing() {
            defaultSnapshot();
            valueProfilerDisabled();
            ConnectorSemantic row = joinRow(1L, "orders", "cid", "users", "id",
                    ConnectorSemanticService.SOURCE_INFERRED, ConnectorSemanticService.V_NONE);
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(row));
            validatorReturns(SemanticJoinValidator.OUT_TIER_BLOCKED,
                    verdict("orders", "cid", "users", "id",
                            ConnectorSemanticService.V_NONE, null, false));

            ConnectorSemanticDeriveService.ValidationResult r = service.validate(CONNECTOR_ID);

            // V_NONE = 没查；V_UNDECIDABLE = 查了判不出来。两者绝不能混。
            assertEquals(1, r.getJoinNotProbed());
            assertEquals(0, r.getJoinUndecidable());
            assertEquals(0, r.getJoinRowsUpdated());
            verify(semanticMapper, never()).updateById(any());
        }

        @Test
        @DisplayName("★ 重跑时跳过已决过的关系（验证器没有跨轮次游标，过滤是接线方的活）")
        void alreadyDecidedRowsAreNotReprobed() {
            defaultSnapshot();
            valueProfilerDisabled();
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(
                    joinRow(1L, "orders", "cid", "users", "id",
                            ConnectorSemanticService.SOURCE_INFERRED, ConnectorSemanticService.V_CONFIRMED),
                    joinRow(2L, "orders", "st", "shops", "id",
                            ConnectorSemanticService.SOURCE_INFERRED, ConnectorSemanticService.V_REJECTED)));

            ConnectorSemanticDeriveService.ValidationResult r = service.validate(CONNECTOR_ID);

            assertTrue(r.isOk());
            assertEquals(0, r.getJoinCandidates());
            // 不过滤的话，每点一次「验证表关系」就把客户的库重新扫一遍，而结论一条都不会变。
            verify(joinValidator, never()).validate(anyLong(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("★ source=HUMAN 的关系一行不碰，也不进候选")
        void humanRowsAreNeverTouched() {
            defaultSnapshot();
            valueProfilerDisabled();
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(
                    joinRow(9L, "orders", "cid", "users", "id",
                            ConnectorSemanticService.SOURCE_HUMAN, ConnectorSemanticService.V_NONE)));

            ConnectorSemanticDeriveService.ValidationResult r = service.validate(CONNECTOR_ID);

            assertEquals(0, r.getJoinCandidates());
            verify(joinValidator, never()).validate(anyLong(), any(), any(), any(), any());
            verify(semanticMapper, never()).updateById(any());
        }

        @Test
        @DisplayName("★ 验完基数仍判不出来时，把模型上一轮猜的那个基数删掉，而不是留着")
        void staleModelCardinalityIsDroppedAfterProbing() {
            defaultSnapshot();
            valueProfilerDisabled();
            ConnectorSemantic row = joinRow(1L, "orders", "cid", "users", "id",
                    ConnectorSemanticService.SOURCE_INFERRED, ConnectorSemanticService.V_NONE);
            Map<String, Object> d = detailOf(row);
            d.put("cardinality", "N:1");     // 模型自己标的
            try {
                row.setDetailJson(CommonUtil.getObjectMapper().writeValueAsString(d));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(row));
            validatorReturns(SemanticJoinValidator.OUT_RAN,
                    verdict("orders", "cid", "users", "id",
                            ConnectorSemanticService.V_WEAK, null, true));

            service.validate(CONNECTOR_ID);

            ArgumentCaptor<ConnectorSemantic> cap = ArgumentCaptor.forClass(ConnectorSemantic.class);
            verify(semanticMapper).updateById(cap.capture());
            // 一条写着「已采样验证」、却带着模型猜出来的 N:1 的关系，比没验证过更危险：
            // 自动 join 正是按基数决定的，一次 fan-out 会让 SUM 出来的金额凭空变大且不报错。
            assertFalse(detailOf(cap.getValue()).containsKey("cardinality"));
        }

        @Test
        @DisplayName("语料写进 source_sql 的来源，在 S3 覆盖 basis 之后仍然活着")
        void corpusProvenanceSurvivesVerification() {
            defaultSnapshot();
            valueProfilerDisabled();
            ConnectorSemantic row = joinRow(1L, "orders", "cid", "users", "id",
                    ConnectorSemanticService.SOURCE_INFERRED, ConnectorSemanticService.V_NONE);
            Map<String, Object> d = detailOf(row);
            d.put("source_sql", "来自视图 v_order_user 的定义");
            try {
                row.setDetailJson(CommonUtil.getObjectMapper().writeValueAsString(d));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(row));
            validatorReturns(SemanticJoinValidator.OUT_RAN,
                    verdict("orders", "cid", "users", "id",
                            ConnectorSemanticService.V_CONFIRMED, SemanticJoinValidator.CARD_MANY_ONE, true));

            service.validate(CONNECTOR_ID);

            ArgumentCaptor<ConnectorSemantic> cap = ArgumentCaptor.forClass(ConnectorSemantic.class);
            verify(semanticMapper).updateById(cap.capture());
            Map<String, Object> after = detailOf(cap.getValue());
            // detailPatch 会覆盖 basis。来源若只活在那个键上，就会被这次覆盖一起抹掉。
            assertEquals("来自视图 v_order_user 的定义", after.get("source_sql"));
            assertTrue(String.valueOf(after.get("basis")).contains("包含率"));
            assertTrue(cap.getValue().getGloss().contains("v_order_user"));
        }
    }

    // ================================================================ S4 值域落库

    @Nested
    @DisplayName("S4 列取值域落库")
    class ValuePersistence {

        private SemanticValueProfiler.ColumnValueDomain enumerated(String obj, String field, String... values) {
            return SemanticValueProfiler.ColumnValueDomain.builder()
                    .objectName(obj).fieldName(field)
                    .outcome(SemanticValueProfiler.Outcome.ENUMERATED)
                    .distinctCount((long) values.length)
                    .values(List.of(values))
                    .note("取值共 " + values.length + " 种")
                    .build();
        }

        private void profilerReturns(int statements, SemanticValueProfiler.ColumnValueDomain... domains) {
            when(valueProfiler.profile(anyLong(), any())).thenReturn(
                    SemanticValueProfiler.ValueProfile.builder()
                            .tierAllowed(true)
                            .tierStatement("第 3 档")
                            .domains(List.of(domains))
                            .attemptedColumns(domains.length)
                            .enumeratedColumns(domains.length)
                            .statements(statements)
                            .build());
        }

        @Test
        @DisplayName("★ 档位不允许时一行都不写：几百次 UPDATE 换一句「这里没有答案」，而缺键本来就是这个意思")
        void tierDisabledWritesNothing() {
            defaultSnapshot();
            valueProfilerDisabled();
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());

            ConnectorSemanticDeriveService.ValidationResult r = service.validate(CONNECTOR_ID);

            assertEquals(0, r.getValueColumns());
            verify(semanticMapper, never()).updateById(any());
            verify(semanticMapper, never()).insert(any());
            // 一条语句都没发就不该留审计痕迹，否则真的那些会被淹掉。
            verify(auditService, never()).recordPlatformStage(anyLong(), anyString(), anyString(),
                    any(), anyString(), anyString(), anyInt(), anyLong(), any(Boolean.class));
        }

        @Test
        @DisplayName("已有字段行：只并进 value_domain 一个键")
        void patchesExistingFieldRow() {
            defaultSnapshot();
            ConnectorSemantic field = new ConnectorSemantic();
            field.setId(5L);
            field.setScope(ConnectorSemanticService.SCOPE_FIELD);
            field.setObjectName("orders");
            field.setFieldName("st");
            field.setSource(ConnectorSemanticService.SOURCE_INFERRED);
            field.setDetailJson("{\"keep\":\"me\"}");
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(field));
            profilerReturns(4, enumerated("orders", "st", "0", "1", "2"));

            service.validate(CONNECTOR_ID);

            ArgumentCaptor<ConnectorSemantic> cap = ArgumentCaptor.forClass(ConnectorSemantic.class);
            verify(semanticMapper).updateById(cap.capture());
            Map<String, Object> d = detailOf(cap.getValue());
            assertEquals("me", d.get("keep"), "原有的键不能被顺手抹掉");
            assertNotNull(d.get(SemanticValueProfiler.DETAIL_KEY));
            assertNull(cap.getValue().getGloss(), "字段含义是模型写的，值域阶段不该去改它");
        }

        @Test
        @DisplayName("★ 没有字段行的列：拿到完整取值集合时补一行，否则 S4 对这列白跑")
        void createsFieldRowWhenMissing() {
            defaultSnapshot();
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());
            profilerReturns(2, enumerated("orders", "st", "0", "1", "2", "3"));

            ConnectorSemanticDeriveService.ValidationResult r = service.validate(CONNECTOR_ID);

            assertEquals(1, r.getValueRowsCreated());
            ArgumentCaptor<ConnectorSemantic> cap = ArgumentCaptor.forClass(ConnectorSemantic.class);
            verify(semanticMapper).insert(cap.capture());
            ConnectorSemantic row = cap.getValue();
            assertEquals(ConnectorSemanticService.SCOPE_FIELD, row.getScope());
            assertEquals(TENANT, row.getTenantId());
            assertEquals(CONNECTOR_ID, row.getConnectorId());
            assertEquals(ConnectorSemanticService.SOURCE_INFERRED, row.getSource());
            // 「在数据里查得到」正是 DATA 这一档的定义。
            assertEquals(ConnectorSemanticService.EV_DATA, row.getEvidence());
            assertEquals(ConnectorSemanticService.ANCHOR_FIELD, row.getAnchorKind());
            assertNotNull(row.getAnchorHash());
            // ★ K-3：取值只进 value_domain，gloss 一个取值都不复述——那句话没有档位闸。
            assertFalse(row.getGloss().contains("0、1"), row.getGloss());
            assertEquals("value_profile", detailOf(row).get("origin"));
            assertTrue(String.valueOf(detailOf(row).get(SemanticValueProfiler.DETAIL_KEY)).contains("3"));
        }

        @Test
        @DisplayName("没有字段行、又不是完整集合的列：不补行（一行「它取值太多」是纯噪音）")
        void doesNotCreateRowForIncompleteDomain() {
            defaultSnapshot();
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());
            when(valueProfiler.profile(anyLong(), any())).thenReturn(
                    SemanticValueProfiler.ValueProfile.builder()
                            .tierAllowed(true).tierStatement("第 3 档")
                            .domains(List.of(SemanticValueProfiler.ColumnValueDomain.builder()
                                    .objectName("orders").fieldName("st")
                                    .outcome(SemanticValueProfiler.Outcome.HIGH_CARDINALITY)
                                    .note("取值太多").build()))
                            .attemptedColumns(1).statements(1).build());

            service.validate(CONNECTOR_ID);

            verify(semanticMapper, never()).insert(any());
        }

        @Test
        @DisplayName("★ 真发了语句就补一条 platform.semantic_values 审计（第 3 档要和第 2 档分得开）")
        void recordsValueStageAudit() {
            defaultSnapshot();
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());
            profilerReturns(6, enumerated("orders", "st", "0", "1"));

            service.validate(CONNECTOR_ID);

            verify(auditService).recordPlatformStage(eq(CONNECTOR_ID), eq(TENANT), eq("客户生产库"),
                    eq(Capability.QUERY), eq(ConnectorAuditService.OP_SEMANTIC_VALUES),
                    anyString(), eq(6), anyLong(), eq(true));
        }
    }

    // ================================================================ note 与并发

    @Nested
    @DisplayName("semantic_note 与重入")
    class NoteAndReentry {

        @Test
        @DisplayName("★ 阶段只改 note，不碰 semantic_status（占着 RUNNING 半小时会堵死推导的重试入口）")
        void stageNeverTouchesStatus() {
            defaultSnapshot();
            valueProfilerDisabled();
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());

            service.validate(CONNECTOR_ID);

            ArgumentCaptor<Connection> cap = ArgumentCaptor.forClass(Connection.class);
            verify(connectionMapper).update(cap.capture(), any());
            assertNull(cap.getValue().getSemanticStatus(), "status 归推导所有");
            assertNotNull(cap.getValue().getSemanticNote());
        }

        @Test
        @DisplayName("★ note 上的推导结论不会被阶段文字挤掉，而且重跑多次也不会越接越长")
        void derivationNoteIsKeptAndNotAccumulated() {
            Connection conn = new Connection();
            conn.setId(CONNECTOR_ID);
            conn.setName("客户生产库");
            conn.setTenantId(TENANT);
            conn.setSemanticNote("覆盖 3/3 个对象：表用途 3 ｜ 正在验证表关系 5/57……");
            when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(conn);
            defaultSnapshot();
            valueProfilerDisabled();
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());

            service.validate(CONNECTOR_ID);

            ArgumentCaptor<Connection> cap = ArgumentCaptor.forClass(Connection.class);
            verify(connectionMapper).update(cap.capture(), any());
            String note = cap.getValue().getSemanticNote();
            assertTrue(note.startsWith("覆盖 3/3 个对象：表用途 3"), "推导结论要留着");
            // 不切的话 note 会变成 A ｜ B ｜ C ｜ …，然后被 varchar(512) 从尾巴截掉，
            // 而尾巴上正是最新那一段。
            assertEquals(1, note.chars().filter(c -> c == '｜').count());
        }
    }
}
