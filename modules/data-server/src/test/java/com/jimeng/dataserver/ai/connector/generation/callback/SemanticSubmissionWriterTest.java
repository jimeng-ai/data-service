package com.jimeng.dataserver.ai.connector.generation.callback;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.connector.generation.SemanticConnectionClaim;
import com.jimeng.dataserver.ai.connector.generation.consistency.ProposedEntry;
import com.jimeng.dataserver.ai.connector.generation.consistency.RuleContext;
import com.jimeng.dataserver.ai.connector.generation.consistency.RuleViolation;
import com.jimeng.dataserver.ai.connector.generation.consistency.SemanticConsistencyRule;
import com.jimeng.dataserver.ai.connector.generation.consistency.SemanticConsistencyRuleRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.entity.ConnectorSemanticGenerationTable;
import com.jimeng.persistence.entity.ConnectorSemanticStaged;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationTableMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticStagedMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.SavepointManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 设计文档 7.8、8.5-8.7：逐表提交在一个事务内完成确认、复核、替换与登记。 */
class SemanticSubmissionWriterTest {

    private static final long GEN_ID = 9101L;
    private static final long CONNECTOR_ID = 9201L;
    private static final String TENANT = "tenant-a";
    private static final String TABLE = "t_order";

    private ConnectorSemanticGenerationMapper generationMapper;
    private ConnectorSemanticGenerationTableMapper tableMapper;
    private ConnectorSchemaMapper schemaMapper;
    private ConnectorSemanticMapper semanticMapper;
    private ConnectorSemanticStagedMapper stagedMapper;
    private ConnectorSemanticService semanticService;
    private SemanticConnectionClaim connectionClaim;
    private ConnectorProperties properties;
    private SemanticSubmissionWriter writer;
    private ConnectorSchema snapshot;
    private ConnectorSemanticGenerationTable batch;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ConnectorSemanticGeneration.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemanticGenerationTable.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSchema.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemantic.class);
        TableInfoHelper.initTableInfo(assistant, ConnectorSemanticStaged.class);

        generationMapper = mock(ConnectorSemanticGenerationMapper.class);
        tableMapper = mock(ConnectorSemanticGenerationTableMapper.class);
        schemaMapper = mock(ConnectorSchemaMapper.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        stagedMapper = mock(ConnectorSemanticStagedMapper.class);
        semanticService = mock(ConnectorSemanticService.class);
        connectionClaim = mock(SemanticConnectionClaim.class);
        properties = new ConnectorProperties();
        writer = new SemanticSubmissionWriter(generationMapper, tableMapper, schemaMapper, semanticMapper,
                stagedMapper, properties, new SemanticConsistencyRuleRegistry(List.of()), semanticService,
                connectionClaim);

        Connection connection = new Connection();
        connection.setId(CONNECTOR_ID);
        connection.setTenantId(TENANT);
        when(semanticService.requireOwned(CONNECTOR_ID)).thenReturn(connection);

        ConnectorSemanticGeneration generation = new ConnectorSemanticGeneration();
        generation.setId(GEN_ID);
        generation.setTenantId(TENANT);
        generation.setConnectorId(CONNECTOR_ID);
        generation.setStatus("RUNNING");
        generation.setCurrentRunId("run-3");
        generation.setTotalTables(2);
        generation.setDoneTables(0);
        when(generationMapper.update(any(), any())).thenReturn(1);
        when(generationMapper.selectOne(any())).thenReturn(generation);
        when(generationMapper.selectList(any())).thenReturn(List.of(generation));

        snapshot = new ConnectorSchema();
        snapshot.setTenantId(TENANT);
        snapshot.setConnectorId(CONNECTOR_ID);
        snapshot.setObjectName(TABLE);
        snapshot.setObjectType("TABLE");
        snapshot.setImportanceRank(1);
        snapshot.setDetailJson("{\"fields\":[{\"name\":\"id\",\"type\":\"bigint\",\"nullable\":false,\"comment\":\"主键\",\"extra\":\"\"}]}");
        when(schemaMapper.selectList(any())).thenReturn(List.of(snapshot));

        batch = new ConnectorSemanticGenerationTable();
        batch.setId(9301L);
        batch.setTenantId(TENANT);
        batch.setConnectorId(CONNECTOR_ID);
        batch.setGenerationId(GEN_ID);
        batch.setObjectName(TABLE);
        batch.setImportanceRank(1);
        batch.setSliceNo(3);
        batch.setStatus("DISPATCHED");
        batch.setSubmitCount(0);
        when(tableMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(batch)));
        when(tableMapper.update(any(), any())).thenReturn(1);
        when(semanticMapper.selectList(any())).thenReturn(List.of());
        when(stagedMapper.selectList(any())).thenReturn(List.of());
        when(semanticMapper.insert(any())).thenReturn(1);
        when(stagedMapper.insert(any())).thenReturn(1);
    }

    @Test
    @DisplayName("DIRECT 先锁 connection 再确认批次，只替换本表 INFERRED 并返回计数")
    void direct只替换本表且锁序正确() {
        SubmitResultView out = writer.submit(principal("DIRECT"), request(validSubmission()));

        assertEquals("ACCEPTED", out.getStatus());
        assertEquals(1, out.getSubmitCount());
        assertEquals(1, out.getWritten().getObject());
        assertEquals(1, out.getWritten().getFields());
        assertEquals(List.of(), out.getEntries());
        verify(semanticMapper).physicalDeleteInferredOfObject(TENANT, CONNECTOR_ID, TABLE);
        verify(semanticMapper, times(2)).insert(any());
        verify(stagedMapper, never()).insert(any());

        InOrder order = inOrder(connectionClaim, semanticService, generationMapper);
        order.verify(connectionClaim).lockRow(CONNECTOR_ID);
        order.verify(semanticService).requireOwned(CONNECTOR_ID);
        order.verify(generationMapper).selectList(any());
    }

    @Test
    @DisplayName("同表重交每次整体替换 INFERRED，HUMAN/IMPORTED 冲突行始终不碰")
    void 同表重交整体替换且不碰人工导入() {
        ConnectorSemantic human = SemanticRowAssembler.base(ConnectorSemanticService.SCOPE_FIELD, TABLE, "id", "");
        human.setSource(ConnectorSemanticService.SOURCE_HUMAN);
        when(semanticMapper.selectList(any())).thenReturn(List.of(human));

        SubmitResultView first = writer.submit(principal("DIRECT"), request(validSubmission()));
        SubmitResultView second = writer.submit(principal("DIRECT"), request(validSubmission()));

        assertEquals("HUMAN_ROW_EXISTS", first.getEntries().get(0).getCode());
        assertEquals("HUMAN_ROW_EXISTS", second.getEntries().get(0).getCode());
        verify(semanticMapper, times(2)).physicalDeleteInferredOfObject(TENANT, CONNECTOR_ID, TABLE);
        verify(semanticMapper, times(2)).insert(any()); // 两次都只写 OBJECT；冲突的人工 FIELD 不写。
    }

    @Test
    @DisplayName("STAGED 只替换本表暂存行，不锁 connection、不动主表")
    void staged只写暂存() {
        SubmitResultView out = writer.submit(principal("STAGED"), request(validSubmission()));

        assertEquals("ACCEPTED", out.getStatus());
        verify(connectionClaim, never()).lockRow(anyLong());
        verify(semanticMapper, never()).physicalDeleteInferredOfObject(any(), any(), any());
        verify(semanticMapper, never()).insert(any());
        verify(stagedMapper).physicalDeleteOwnedRows(TENANT, GEN_ID, TABLE);
        verify(stagedMapper, times(2)).insert(any());
    }

    @Test
    @DisplayName("REJECTED 零可写行时不写不删并保持 DISPATCHED")
    void rejected不写不删() {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("table", TABLE);
        submission.put("object", Map.of("evidence", "COMMENT"));

        SubmitResultView out = writer.submit(principal("DIRECT"), request(submission));

        assertEquals("REJECTED", out.getStatus());
        assertEquals("MISSING_REQUIRED", out.getEntries().get(0).getCode());
        verify(semanticMapper, never()).physicalDeleteInferredOfObject(any(), any(), any());
        verify(semanticMapper, never()).insert(any());
        assertTrue(capturedTableCas().getSqlSet().contains("submit_count = submit_count + 1"));
    }

    @Test
    @DisplayName("SKIPPED 仍整体替换为空并登记 agent 跳过原因")
    void skipped删除此前行() {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("table", TABLE);
        submission.put("skip_reason", "本表没有业务含义");

        SubmitResultView out = writer.submit(principal("DIRECT"), request(submission));

        assertEquals("SKIPPED", out.getStatus());
        verify(semanticMapper).physicalDeleteInferredOfObject(TENANT, CONNECTOR_ID, TABLE);
        TableUpdate update = capturedTableUpdate();
        assertEquals("DONE", update.entity().getStatus());
        assertTrue(update.wrapper().getParamNameValuePairs().values().stream()
                .anyMatch(value -> String.valueOf(value).contains("AGENT_SKIPPED")));
    }

    @Test
    @DisplayName("结构指纹不一致回 5007，运行确认已由外层独立事务完成且 submit_count 不增加")
    void 指纹不一致不计提交() {
        SubmitRequest request = request(validSubmission());
        request.setStructureStamp("stale");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> writer.submit(principal("DIRECT"), request));

        assertEquals(ExceptionCode.INVALID_REQUEST.getResultCode(), ex.getRespCode());
        verify(generationMapper).selectList(any());
        verify(tableMapper, never()).update(any(), any());
        verify(semanticMapper, never()).physicalDeleteInferredOfObject(any(), any(), any());
    }

    @Test
    @DisplayName("表不在本片回 5007且不计提交")
    void 表不在本片() {
        batch.setSliceNo(4);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> writer.submit(principal("DIRECT"), request(validSubmission())));

        assertEquals(ExceptionCode.INVALID_REQUEST.getResultCode(), ex.getRespCode());
        verify(tableMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("已达提交上限在 B 步回 5007，不再执行表结果 CAS")
    void 已达提交上限() {
        batch.setSubmitCount(3);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> writer.submit(principal("DIRECT"), request(validSubmission())));

        assertEquals(ExceptionCode.INVALID_REQUEST.getResultCode(), ex.getRespCode());
        verify(tableMapper, never()).update(any(), any());
        verify(semanticMapper, never()).physicalDeleteInferredOfObject(any(), any(), any());
    }

    @Test
    @DisplayName("第三次 REJECTED 提交把未完成表置 GAVE_UP")
    void 提交次数用尽gaveUp() {
        batch.setSubmitCount(2);
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("table", TABLE);
        submission.put("object", Map.of("evidence", "COMMENT"));

        SubmitResultView out = writer.submit(principal("DIRECT"), request(submission));

        assertEquals("REJECTED", out.getStatus());
        TableUpdate update = capturedTableUpdate();
        assertEquals("GAVE_UP", update.entity().getStatus());
        assertTrue(update.wrapper().getParamNameValuePairs().values().stream()
                .anyMatch(value -> String.valueOf(value).contains("SUBMIT_LIMIT")));
    }

    @Test
    @DisplayName("人工行与 GUESS 都逐条 dropped，人工行不被覆盖")
    void human与guess冲突逐条丢弃() {
        ConnectorSemantic human = SemanticRowAssembler.base(ConnectorSemanticService.SCOPE_FIELD, TABLE, "id", "");
        human.setSource(ConnectorSemanticService.SOURCE_HUMAN);
        when(semanticMapper.selectList(any())).thenReturn(List.of(human));
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("table", TABLE);
        submission.put("fields", List.of(
                Map.of("name", "id", "gloss", "主键", "evidence", "COMMENT"),
                Map.of("name", "id", "gloss", "猜的", "evidence", "GUESS")));

        SubmitResultView out = writer.submit(principal("DIRECT"), request(submission));

        assertEquals("ACCEPTED", out.getStatus());
        assertEquals(List.of("HUMAN_ROW_EXISTS", "EVIDENCE_GUESS").stream().sorted().toList(),
                out.getEntries().stream().map(EntryOutcomeView::getCode).sorted().toList());
        verify(semanticMapper, never()).insert(any());
    }

    @Test
    @DisplayName("名字对不上快照时与有效条目组成 PARTIAL，仍整体替换有效部分")
    void name错误形成partial() {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("table", TABLE);
        submission.put("object", Map.of("gloss", "订单表", "evidence", "COMMENT"));
        submission.put("fields", List.of(Map.of(
                "name", "invented", "gloss", "不存在", "evidence", "COMMENT")));

        RecordingTransactionManager transactions = new RecordingTransactionManager();
        SubmitResultView out = transactionalProxy(transactions).submit(principal("DIRECT"), request(submission));

        assertEquals("PARTIAL", out.getStatus());
        assertEquals(1, out.getWritten().getObject());
        assertEquals("NAME_NOT_IN_SNAPSHOT", out.getEntries().get(0).getCode());
        verify(semanticMapper).physicalDeleteInferredOfObject(TENANT, CONNECTOR_ID, TABLE);
        assertEquals(1, transactions.savepointsCreated);
        assertEquals(0, transactions.savepointsRolledBack);
        assertEquals(1, transactions.savepointsReleased);
    }

    @Test
    @DisplayName("规则注册表返回的全部违规都回显，同一条只从候选里移除一次")
    void 规则全收集() {
        SemanticConsistencyRule rule = new SemanticConsistencyRule() {
            @Override public String code() { return "TWO_FINDINGS"; }
            @Override public Set<String> kinds() { return Set.of(SemanticRowAssembler.KIND_FIELDS); }
            @Override public String summary() { return "测试规则"; }
            @Override public List<RuleViolation> check(ProposedEntry entry, RuleContext ctx) {
                return List.of(RuleViolation.reject("可修正"), RuleViolation.drop("无需重提"));
            }
        };
        writer = newWriter(new SemanticConsistencyRuleRegistry(List.of(rule)));

        RecordingTransactionManager transactions = new RecordingTransactionManager();
        SubmitResultView out = transactionalProxy(transactions)
                .submit(principal("DIRECT"), request(validSubmission()));

        assertEquals("PARTIAL", out.getStatus());
        assertEquals(1, out.getWritten().getObject());
        assertEquals(List.of("dropped", "rejected"),
                out.getEntries().stream().map(EntryOutcomeView::getStatus).sorted().toList());
        assertEquals(1, capturedTableUpdate().entity().getDroppedRows());
        assertEquals(1, transactions.savepointsCreated);
        assertEquals(0, transactions.savepointsRolledBack);
    }

    @Test
    @DisplayName("已由人回答的 CAVEAT 被丢弃，不调用 merge")
    void 已答口径丢弃() {
        ConnectorSemantic metric = SemanticRowAssembler.base(
                ConnectorSemanticService.SCOPE_METRIC, "", "", "销售额");
        metric.setSource(ConnectorSemanticService.SOURCE_HUMAN);
        metric.setStatus(ConnectorSemanticService.ST_CONFIRMED);
        when(semanticMapper.selectList(any())).thenReturn(List.of(metric));
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("table", TABLE);
        submission.put("ambiguities", List.of(Map.of("term", "销售额", "question", "是否扣退款")));

        SubmitResultView out = writer.submit(principal("DIRECT"), request(submission));

        assertEquals("ACCEPTED", out.getStatus());
        assertEquals("CAVEAT_TERM_ANSWERED", out.getEntries().get(0).getCode());
        verify(semanticService, never()).mergeInferredCaveat(anyLong(), any());
    }

    @Test
    @DisplayName("单行唯一键冲突记 WRITE_CONFLICT，不回滚其余行")
    void 单行撞键不回滚() {
        when(semanticMapper.insert(any())).thenThrow(new DuplicateKeyException("race")).thenReturn(1);

        SubmitResultView out = writer.submit(principal("DIRECT"), request(validSubmission()));

        assertEquals("ACCEPTED", out.getStatus());
        assertEquals(1, out.getEntries().size());
        assertEquals("WRITE_CONFLICT", out.getEntries().get(0).getCode());
        assertEquals(1, out.getWritten().getFields() + out.getWritten().getObject());
        verify(tableMapper).update(any(), any());
    }

    @Test
    @DisplayName("DIRECT 的 CAVEAT 逐条调用公共 merge API")
    void caveat调用merge() {
        when(semanticService.mergeInferredCaveat(eq(CONNECTOR_ID), any()))
                .thenReturn(ConnectorSemanticService.CaveatMergeResult.INSERTED);
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("table", TABLE);
        submission.put("ambiguities", List.of(Map.of("term", "销售额", "question", "是否扣退款")));

        SubmitResultView out = writer.submit(principal("DIRECT"), request(submission));

        assertEquals(1, out.getWritten().getAmbiguities());
        verify(semanticService).mergeInferredCaveat(eq(CONNECTOR_ID), any());
    }

    @Test
    @DisplayName("ambiguities 自动补本表且 applies_to 总数最多 10")
    void caveat自动补本表且上限十张() {
        when(semanticService.mergeInferredCaveat(eq(CONNECTOR_ID), any()))
                .thenReturn(ConnectorSemanticService.CaveatMergeResult.INSERTED);
        List<String> otherTables = new ArrayList<>();
        for (int i = 0; i < 10; i++) otherTables.add("t_" + i);
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("table", TABLE);
        submission.put("ambiguities", List.of(Map.of(
                "term", "销售额", "question", "是否扣退款", "applies_to", otherTables)));

        writer.submit(principal("DIRECT"), request(submission));

        ArgumentCaptor<ConnectorSemantic> caveat = ArgumentCaptor.forClass(ConnectorSemantic.class);
        verify(semanticService).mergeInferredCaveat(eq(CONNECTOR_ID), caveat.capture());
        String detail = caveat.getValue().getDetailJson();
        assertTrue(detail.contains(TABLE));
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = assertDoesNotThrow(
                () -> com.jimeng.common.core.utils.CommonUtil.getObjectMapper().readValue(detail, Map.class));
        assertEquals(10, ((List<?>) parsed.get("applies_to")).size());
    }

    @Test
    @DisplayName("STAGED 的 CAVEAT 在暂存内按 term 合并 applies_to，保留首个 owner 和问题")
    void stagedCaveat合并() {
        ConnectorSemanticStaged first = new ConnectorSemanticStaged();
        first.setId(9401L);
        first.setTenantId(TENANT);
        first.setConnectorId(CONNECTOR_ID);
        first.setGenerationId(GEN_ID);
        first.setOwnerObject("t_first");
        first.setScope(ConnectorSemanticService.SCOPE_CAVEAT);
        first.setObjectName("");
        first.setFieldName("");
        first.setTerm("销售额");
        first.setGloss("先到的问题");
        first.setDetailJson("{\"applies_to\":[\"t_first\"]}");
        when(stagedMapper.selectList(any())).thenReturn(List.of(first));
        when(stagedMapper.update(any(), any())).thenReturn(1);
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("table", TABLE);
        submission.put("ambiguities", List.of(Map.of("term", "销售额", "question", "后到的问题")));

        SubmitResultView out = writer.submit(principal("STAGED"), request(submission));

        assertEquals(1, out.getWritten().getAmbiguities());
        verify(stagedMapper, never()).insert(any());
        ArgumentCaptor<ConnectorSemanticStaged> update = ArgumentCaptor.forClass(ConnectorSemanticStaged.class);
        verify(stagedMapper).update(update.capture(), any());
        assertTrue(update.getValue().getDetailJson().contains("t_first"));
        assertTrue(update.getValue().getDetailJson().contains(TABLE));
        assertEquals("t_first", first.getOwnerObject());
        assertEquals("先到的问题", first.getGloss());
    }

    @Test
    @DisplayName("响应进度按权威表状态投影，sliceRemaining 按批次顺序返回")
    void 响应进度与剩余表() {
        ConnectorSemanticGenerationTable remaining = new ConnectorSemanticGenerationTable();
        remaining.setId(9302L);
        remaining.setTenantId(TENANT);
        remaining.setConnectorId(CONNECTOR_ID);
        remaining.setGenerationId(GEN_ID);
        remaining.setObjectName("t_remaining");
        remaining.setImportanceRank(2);
        remaining.setSliceNo(3);
        remaining.setStatus("DISPATCHED");
        remaining.setSubmitCount(0);
        when(tableMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(remaining, batch)));

        SubmitResultView out = writer.submit(principal("DIRECT"), request(validSubmission()));

        assertEquals(1, out.getProgress().getDoneTables());
        assertEquals(2, out.getProgress().getTotalTables());
        assertEquals(List.of("t_remaining"), out.getSliceRemaining());
    }

    @Test
    @DisplayName("独立确认后运行记录消失时 4090，且不再读快照或表批次")
    void 批次非running() {
        when(generationMapper.selectList(any())).thenReturn(List.of());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> writer.submit(principal("DIRECT"), request(validSubmission())));

        assertEquals(ExceptionCode.SEMANTIC_GENERATION_CLOSED.getResultCode(), ex.getRespCode());
        verify(schemaMapper, never()).selectList(any());
        verify(tableMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("主提交事务以完整可信条件 FOR UPDATE 锁住运行批次且不重复计数")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void 主事务条件查询锁住run不重复计数() {
        writer.submit(principal("STAGED"), request(validSubmission()));

        ArgumentCaptor<Wrapper<ConnectorSemanticGeneration>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(generationMapper).selectList(captor.capture());
        String where = captor.getValue().getSqlSegment();
        assertTrue(where.contains("tenant_id"), where);
        assertTrue(where.contains("connector_id"), where);
        assertTrue(where.contains("status"), where);
        assertTrue(where.contains("current_run_id"), where);
        assertTrue(where.contains("id"), where);
        assertTrue(where.contains("FOR UPDATE"), where);
        verify(generationMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("主事务重新锁定时 run 已过期则回 4090，且不读写提交内容")
    void 主事务锁不到当前run不写() {
        when(generationMapper.selectList(any())).thenReturn(List.of());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> writer.submit(principal("DIRECT"), request(validSubmission())));

        assertEquals(ExceptionCode.SEMANTIC_GENERATION_CLOSED.getResultCode(), ex.getRespCode());
        verify(schemaMapper, never()).selectList(any());
        verify(tableMapper, never()).selectList(any());
        verify(semanticMapper, never()).physicalDeleteInferredOfObject(any(), any(), any());
        verify(stagedMapper, never()).physicalDeleteOwnedRows(any(), any(), any());
    }

    @Test
    @DisplayName("已有 rejected 且全部候选写冲突时回滚 G 到保存点，REJECTED 保留旧整表")
    void rejected加候选全写冲突回滚替换() {
        SemanticConsistencyRule rejectField = new SemanticConsistencyRule() {
            @Override public String code() { return "REJECT_FIELD"; }
            @Override public Set<String> kinds() { return Set.of(SemanticRowAssembler.KIND_FIELDS); }
            @Override public String summary() { return "测试退回字段"; }
            @Override public List<RuleViolation> check(ProposedEntry entry, RuleContext ctx) {
                return List.of(RuleViolation.reject("字段待修正"));
            }
        };
        writer = newWriter(new SemanticConsistencyRuleRegistry(List.of(rejectField)));
        when(semanticMapper.insert(any())).thenThrow(new DuplicateKeyException("collation race"));

        RecordingTransactionManager transactions = new RecordingTransactionManager();
        SubmitResultView out = transactionalProxy(transactions)
                .submit(principal("DIRECT"), request(validSubmission()));

        assertEquals("REJECTED", out.getStatus());
        assertEquals(0, out.getWritten().getObject());
        assertEquals(List.of("REJECT_FIELD", "WRITE_CONFLICT"),
                out.getEntries().stream().map(EntryOutcomeView::getCode).toList());
        verify(semanticMapper).physicalDeleteInferredOfObject(TENANT, CONNECTOR_ID, TABLE);
        assertEquals(1, transactions.savepointsCreated);
        assertEquals(1, transactions.savepointsRolledBack);
        assertEquals(1, transactions.savepointsReleased);
        assertEquals(1, transactions.commits);
        assertEquals("DISPATCHED", capturedTableUpdate().entity().getStatus());
    }

    @Test
    @DisplayName("H 步 CAS 影响 0 行抛 4090")
    void h步cas零行() {
        when(tableMapper.update(any(), any())).thenReturn(0);
        RecordingTransactionManager transactions = new RecordingTransactionManager();

        ServiceException ex = assertThrows(ServiceException.class,
                () -> transactionalProxy(transactions).submit(principal("DIRECT"), request(validSubmission())));

        assertEquals(ExceptionCode.SEMANTIC_GENERATION_CLOSED.getResultCode(), ex.getRespCode());
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
    }

    @Test
    @DisplayName("成功提交在真实事务代理下 commit，防止回滚测试假阳性")
    void 成功提交事务commit() {
        RecordingTransactionManager transactions = new RecordingTransactionManager();

        SubmitResultView out = transactionalProxy(transactions)
                .submit(principal("DIRECT"), request(validSubmission()));

        assertEquals("ACCEPTED", out.getStatus());
        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
    }

    private SemanticAgentPrincipal principal(String mode) {
        return new SemanticAgentPrincipal(TENANT, "42", GEN_ID, CONNECTOR_ID, 3, "run-3", mode);
    }

    private SubmitRequest request(Map<String, Object> submission) {
        SubmitRequest request = new SubmitRequest();
        request.setStructureStamp(ConnectorSemanticService.tableStamp(TABLE,
                SemanticRowAssembler.parseFields(List.of(snapshot)).get(TABLE)));
        request.setSubmission(submission);
        return request;
    }

    private Map<String, Object> validSubmission() {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("table", TABLE);
        submission.put("object", Map.of("gloss", "订单表", "evidence", "COMMENT", "confidence", 90));
        submission.put("fields", List.of(Map.of(
                "name", "id", "gloss", "订单主键", "evidence", "COMMENT", "confidence", 90)));
        return submission;
    }

    private SemanticSubmissionWriter newWriter(SemanticConsistencyRuleRegistry registry) {
        return new SemanticSubmissionWriter(generationMapper, tableMapper, schemaMapper, semanticMapper,
                stagedMapper, properties, registry, semanticService, connectionClaim);
    }

    private SemanticSubmissionWriter transactionalProxy(RecordingTransactionManager transactionManager) {
        ProxyFactory factory = new ProxyFactory(writer);
        factory.setProxyTargetClass(true);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        factory.addAdvice(interceptor);
        return (SemanticSubmissionWriter) factory.getProxy();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private LambdaUpdateWrapper<ConnectorSemanticGenerationTable> capturedTableCas() {
        return capturedTableUpdate().wrapper();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private TableUpdate capturedTableUpdate() {
        ArgumentCaptor<ConnectorSemanticGenerationTable> entity =
                ArgumentCaptor.forClass(ConnectorSemanticGenerationTable.class);
        ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemanticGenerationTable>> captor =
                ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(tableMapper).update(entity.capture(), captor.capture());
        return new TableUpdate(entity.getValue(), captor.getValue());
    }

    private record TableUpdate(ConnectorSemanticGenerationTable entity,
                               LambdaUpdateWrapper<ConnectorSemanticGenerationTable> wrapper) {
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;
        private int savepointsCreated;
        private int savepointsRolledBack;
        private int savepointsReleased;

        @Override
        protected Object doGetTransaction() {
            return new RecordingSavepointTransaction(this);
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // 这里只验证 Spring 事务拦截器的 commit / rollback 分支，不需要真实连接。
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }

    private static final class RecordingSavepointTransaction implements SavepointManager {
        private final RecordingTransactionManager owner;
        private int sequence;

        private RecordingSavepointTransaction(RecordingTransactionManager owner) {
            this.owner = owner;
        }

        @Override
        public Object createSavepoint() {
            owner.savepointsCreated++;
            return ++sequence;
        }

        @Override
        public void rollbackToSavepoint(Object savepoint) {
            owner.savepointsRolledBack++;
        }

        @Override
        public void releaseSavepoint(Object savepoint) {
            owner.savepointsReleased++;
        }
    }
}
