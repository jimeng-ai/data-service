package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.claude.service.ClaudeService;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语义层推导：从「模型吐出来的一段文本」到「能落进 connector_semantic 的行」这一段。
 *
 * <h3>为什么走 {@code derive()} 这个公开入口，而不是去反射那几个 private 方法</h3>
 * {@code buildDigest / parseJson / toRows} 都是 private 实例方法，但它们的<b>全部</b>输入都来自
 * 构造器里的协作者：结构快照来自 {@code schemaService.currentRows}，模型那一段文本来自
 * {@code claudeService.messagesInternal}，而产出唯一的出口是 {@code semanticService.replaceInferred} 的入参。
 * 把这三个 mock 掉、再用 {@code ArgumentCaptor} 接住入参，等于拿到了这三个私有方法的真实输入输出，
 * 而且<b>不用为了测试放宽任何一处可见性</b>。整条链上没有 Spring、没有库、没有网络。
 *
 * <p>唯一直接调用的内部缝是 {@code repairTruncatedJson}——它本来就是 package-private static。
 *
 * <h3>这个类真正在钉的东西</h3>
 * 推导的失效方式几乎全是<b>静默</b>的：一条 GUESS 落了库，模型就把它当事实用；两条关系撞了唯一键，
 * 整批插入回滚、一行都不剩却只在日志里留一句；摘要截断没人说，管理台就把「沉默」读成「都覆盖了」。
 * 下面每一条测试对应的都是一种「跑起来不报错、结果却是错的」的情形。
 */
class ConnectorSemanticDeriveServiceTest {

    private static final Long CONNECTOR_ID = 1L;

    private ConnectorSchemaService schemaService;
    private ConnectorSemanticService semanticService;
    private ConnectionMapper connectionMapper;
    private ClaudeService claudeService;
    private ConnectorProperties properties;
    private ConnectorSemanticDeriveService service;

    @BeforeEach
    void setUp() {
        schemaService = mock(ConnectorSchemaService.class);
        semanticService = mock(ConnectorSemanticService.class);
        connectionMapper = mock(ConnectionMapper.class);
        claudeService = mock(ClaudeService.class);
        // 护栏参数用真对象而不是 mock：这些默认值本身就是被测行为的一部分。
        properties = new ConnectorProperties();
        // S1/S3/S4 与阶段池在本类里一律给 mock：这些用例钉的是「模型产出 → 语义行」那一段。
        // corpusReader.read 的 mock 默认返回 null，生产代码把它当「这次没挖到」处理，不影响任何一条。
        service = new ConnectorSemanticDeriveService(schemaService, semanticService, connectionMapper,
                claudeService, properties,
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
        // 推导开工前要先「认领」这条连接（把 semantic_status 抢成 RUNNING），认领就是这条带条件的
        // UPDATE，返回 0 表示没抢到、本次不跑。默认让它抢得到；并发那一组会单独改成 0。
        when(connectionMapper.update(any(), any())).thenReturn(1);
    }

    /** {@code runDerive} 会往当前线程塞 TenantContext，不清掉会漏给下一个用例。 */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ================================================================ 夹具

    private static FieldDetail col(String name, String type, String comment) {
        return new FieldDetail(name, type, true, comment, null);
    }

    /** 造一行真实形状的结构快照：detail_json 走生产那条 {@code toDetailMap}，不手写 JSON。 */
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

    private void snapshot(ConnectorSchema... rows) {
        when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(List.of(rows));
    }

    /** 三张表的常用底座：orders(id, cid, st, amt) / users(id, name) / shops(id)。 */
    private void defaultSnapshot() {
        snapshot(
                snapshotOf("orders", "订单表",
                        col("id", "bigint", "主键"),
                        col("cid", "bigint", null),
                        col("st", "tinyint", null),
                        col("amt", "decimal(12,2)", "金额")),
                snapshotOf("users", "用户表", col("id", "bigint", "主键"), col("name", "varchar(64)", null)),
                snapshotOf("shops", null, col("id", "bigint", "主键")));
    }

    /** 让模型「返回」这段文本，外面裹一层 Anthropic messages 响应的形状。 */
    private void modelOutputs(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("content", List.of(block));
        when(claudeService.messagesInternal(any(), any())).thenReturn(resp);
    }

    private ConnectorSemanticDeriveService.DeriveResult run() {
        return service.derive(CONNECTOR_ID);
    }

    /** 进库前那一批行——也就是 {@code toRows} 的真实产出。 */
    private List<ConnectorSemantic> persisted() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConnectorSemantic>> cap = ArgumentCaptor.forClass(List.class);
        verify(semanticService).replaceInferred(eq(CONNECTOR_ID), cap.capture());
        return cap.getValue();
    }

    private static List<ConnectorSemantic> scoped(List<ConnectorSemantic> rows, String scope) {
        return rows.stream().filter(r -> scope.equals(r.getScope())).toList();
    }

    private static ConnectorSemantic only(List<ConnectorSemantic> rows, String scope) {
        List<ConnectorSemantic> hit = scoped(rows, scope);
        assertEquals(1, hit.size(), "期望恰好一条 " + scope + " 行，实际 " + hit.size());
        return hit.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        try {
            return CommonUtil.getObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new AssertionError("这段 JSON 解析不了：" + json, e);
        }
    }

    private static Map<String, Object> detailOf(ConnectorSemantic row) {
        assertNotNull(row.getDetailJson());
        return parse(row.getDetailJson());
    }

    /** 送给模型的那段用户提示词。 */
    private String promptSent() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(claudeService).messagesInternal(cap.capture(), any());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) cap.getValue().get("messages");
        return String.valueOf(messages.get(0).get("content"));
    }

    /**
     * 提示词里两条分隔线之间那段——<b>只有它是结构摘要</b>。
     * 被丢掉的表名会作为「这份材料不完整」写在分隔线之外，两者必须分开断言，
     * 否则「摘要里没有这张表」会被提示词里那句告知误判成通过。
     */
    private String digestBlock() {
        String content = promptSent();
        String sep = "----------------------------------------";
        int a = content.indexOf(sep) + sep.length();
        int b = content.indexOf(sep, a);
        assertTrue(a > 0 && b > a, "提示词里没找到结构摘要的分隔线");
        return content.substring(a, b);
    }

    /**
     * 最后一次状态回写。走的是 {@code update(实体, 带条件的 wrapper)} 而不是 {@code updateById}：
     * 终态回写是一次 CAS——只有仍然持有本次认领的那个线程才写得进去，否则一次跑得慢的失败
     * 会把后面那次成功盖回 FAILED。实体里仍然只有 semantic_* 三列，所以照样能这样断言。
     */
    private Connection lastStatusWrite() {
        ArgumentCaptor<Connection> cap = ArgumentCaptor.forClass(Connection.class);
        verify(connectionMapper, atLeastOnce()).update(cap.capture(), any());
        List<Connection> all = cap.getAllValues();
        return all.get(all.size() - 1);
    }

    /** 空但形状完整的产出：四个键都在、都是空数组。提示词明确要求「键必须都在」。 */
    private static final String EMPTY_BUT_WELL_FORMED =
            "{\"objects\":[],\"fields\":[],\"joins\":[],\"ambiguities\":[]}";

    // ================================================================ 模型调用：内部调用 + 夹紧后的超时

    /**
     * 推导必须走 {@code messagesInternal}（不带工具、不套 Agent 上下文），并把<b>夹紧后</b>的超时交下去。
     *
     * <p>走 {@code messages} 的后果是真实发生过的：请求体里被塞进 web_search 等 6 个工具（客户的表名列名可能被拿去公网搜索），
     * 而且只能用全局 180 秒读超时，41 张表的推导必然超时。夹紧的上限必须小于认领过期的 30 分钟，
     * 否则模型还没回来，认领就被另一次推导抢走。
     */
    @Nested
    @DisplayName("模型调用：走 messagesInternal，超时夹紧后交下去")
    class ModelCallTimeout {

        private Duration timeoutSent() {
            ArgumentCaptor<Duration> cap = ArgumentCaptor.forClass(Duration.class);
            verify(claudeService).messagesInternal(any(), cap.capture());
            verify(claudeService, never()).messages(any());
            return cap.getValue();
        }

        @Test
        @DisplayName("★ 默认 900 秒原样交给 messagesInternal，不走 messages")
        void 默认超时() {
            defaultSnapshot();
            modelOutputs(EMPTY_BUT_WELL_FORMED);

            run();

            assertEquals(Duration.ofSeconds(900), timeoutSent());
        }

        @Test
        @DisplayName("配得太小夹到 60 秒：个位数的超时等于每一次推导都必然失败")
        void 太小夹到下限() {
            properties.getSemantic().setModelTimeoutSeconds(5);
            defaultSnapshot();
            modelOutputs(EMPTY_BUT_WELL_FORMED);

            run();

            assertEquals(Duration.ofSeconds(60), timeoutSent());
        }

        @Test
        @DisplayName("★ 配得太大夹到 25 分钟：不能追上认领过期的 30 分钟")
        void 太大夹到上限() {
            properties.getSemantic().setModelTimeoutSeconds(3600);
            defaultSnapshot();
            modelOutputs(EMPTY_BUT_WELL_FORMED);

            run();

            assertEquals(Duration.ofMinutes(25), timeoutSent());
        }

        @Test
        @DisplayName("区间内的值原样用；夹紧上限加上 HTTP 层整通调用余量，仍小于认领过期的 30 分钟")
        void 区间内原样与上限不变式() {
            ConnectorProperties.Semantic cfg = new ConnectorProperties.Semantic();
            cfg.setModelTimeoutSeconds(600);
            assertEquals(Duration.ofSeconds(600), ConnectorSemanticDeriveService.modelTimeout(cfg));
            cfg.setModelTimeoutSeconds(ConnectorSemanticDeriveService.MODEL_TIMEOUT_MIN_SECONDS);
            assertEquals(Duration.ofSeconds(60), ConnectorSemanticDeriveService.modelTimeout(cfg));
            cfg.setModelTimeoutSeconds(ConnectorSemanticDeriveService.MODEL_TIMEOUT_MAX_SECONDS);
            assertEquals(Duration.ofMinutes(25), ConnectorSemanticDeriveService.modelTimeout(cfg));

            // 30 = CLAIM_STALE_MINUTES（private）= ConnectorHealthJob.DERIVE_CLAIM_LIVE_MINUTES。
            long worstCaseSeconds = ConnectorSemanticDeriveService.MODEL_TIMEOUT_MAX_SECONDS
                    + RequestService.CALL_TIMEOUT_GRACE.getSeconds();
            assertTrue(worstCaseSeconds < 30 * 60,
                    "模型调用最坏占用 " + worstCaseSeconds + " 秒，追上了推导认领的过期时间");
        }
    }

    // ================================================================ ★ GUESS 直接丢

    /**
     * ★ 整套设计的第一条不变式：<b>没有外部依据的断言不进说明书</b>。
     *
     * <p>模型看到 {@code st tinyint} 会非常自信地写出「0=待支付」——那段话完全合理、完全可能错，
     * 而且落库之后没有任何地方能发现它错：查询照常成功，只是数字不对。
     * 分界线是「有没有依据」，不是「模型说它确不确定」，所以判的是 evidence 标签而不是 confidence。
     */
    @Nested
    @DisplayName("GUESS 依据的断言直接丢弃")
    class GuessDropped {

        @Test
        void 字段含义标GUESS的不落库() {
            defaultSnapshot();
            modelOutputs("""
                    {"fields":[
                      {"object":"orders","name":"amt","gloss":"订单金额","evidence":"COMMENT"},
                      {"object":"orders","name":"st","gloss":"0=待支付 1=已支付 2=已取消","evidence":"GUESS","confidence":95}
                    ]}
                    """);
            var r = run();

            List<ConnectorSemantic> rows = persisted();
            assertEquals(1, scoped(rows, ConnectorSemanticService.SCOPE_FIELD).size());
            assertEquals("amt", only(rows, ConnectorSemanticService.SCOPE_FIELD).getFieldName());
            // 那段枚举值含义一个字都不能出现在要进库的行里。
            assertTrue(rows.stream().noneMatch(x -> x.getGloss() != null && x.getGloss().contains("待支付")));
            assertEquals(1, r.getDroppedGuess());
        }

        /** 认不出的依据标签一律当 GUESS：宁可少一条，也不要一条来路不明的。 */
        @Test
        void 认不出的依据标签当GUESS丢掉() {
            defaultSnapshot();
            modelOutputs("""
                    {"fields":[{"object":"orders","name":"amt","gloss":"订单金额","evidence":"VIBES"}]}
                    """);
            var r = run();

            assertTrue(persisted().isEmpty());
            assertEquals(1, r.getDroppedGuess());
        }

        @Test
        void 表用途缺依据标签按GUESS处理() {
            defaultSnapshot();
            modelOutputs("""
                    {"objects":[
                      {"name":"orders","gloss":"订单主表","evidence":"COMMENT"},
                      {"name":"shops","gloss":"门店表"}
                    ]}
                    """);
            var r = run();

            List<ConnectorSemantic> rows = persisted();
            assertEquals(1, scoped(rows, ConnectorSemanticService.SCOPE_OBJECT).size());
            assertEquals("orders", only(rows, ConnectorSemanticService.SCOPE_OBJECT).getObjectName());
            assertEquals(1, r.getDroppedGuess());
        }

        /**
         * ★ 刻意的不对称，别把它「修」成一致：关系缺标签默认 NAME、留下来，字段缺标签当 GUESS、丢掉。
         *
         * <p>理由是代价不对称。一条关系带着 verified=NONE 和「未经数据验证」一起注入，读它的模型知道
         * 要自己核；而一条字段含义是被当成事实读的，错了没有任何地方看得出来。
         */
        @Test
        void 关系缺依据标签默认NAME而不是GUESS() {
            defaultSnapshot();
            modelOutputs("""
                    {"joins":[{"object":"orders","column":"cid","to_object":"users","to_column":"id"}]}
                    """);
            var r = run();

            ConnectorSemantic join = only(persisted(), ConnectorSemanticService.SCOPE_JOIN);
            assertEquals(ConnectorSemanticService.EV_NAME, join.getEvidence());
            assertEquals(0, r.getDroppedGuess());
        }

        /** 关系自己标了 GUESS 还是要丢——默认值只在「没标」时兜底。 */
        @Test
        void 关系自己标GUESS仍然丢() {
            defaultSnapshot();
            modelOutputs("""
                    {"joins":[{"object":"orders","column":"cid","to_object":"users","to_column":"id","evidence":"GUESS"}]}
                    """);
            var r = run();

            assertTrue(persisted().isEmpty());
            assertEquals(1, r.getDroppedGuess());
        }
    }

    // ================================================================ ★ 名字对不上快照

    /**
     * 模型发明出来的表 / 列既算不出锚点，本身也就是幻觉。
     * 这一项偏高说明摘要给少了或者模型在编，所以它有独立计数、要能被看见。
     */
    @Nested
    @DisplayName("表名列名对不上快照的条目丢弃")
    class UnknownNameDropped {

        @Test
        void 快照里没有的表其表用途被丢() {
            defaultSnapshot();
            modelOutputs("""
                    {"objects":[
                      {"name":"orders","gloss":"订单主表","evidence":"COMMENT"},
                      {"name":"t_ghost","gloss":"我猜有一张幽灵表","evidence":"NAME"}
                    ]}
                    """);
            var r = run();

            assertEquals(1, scoped(persisted(), ConnectorSemanticService.SCOPE_OBJECT).size());
            assertEquals(1, r.getDroppedUnknown());
        }

        @Test
        void 表在快照里但列不在的字段含义被丢() {
            defaultSnapshot();
            modelOutputs("""
                    {"fields":[{"object":"orders","name":"total_fee","gloss":"订单总额","evidence":"COMMENT"}]}
                    """);
            var r = run();

            assertTrue(persisted().isEmpty());
            assertEquals(1, r.getDroppedUnknown());
        }

        /** 名字对不上先于依据判定：哪怕它自称 COMMENT，对不上就是幻觉。 */
        @Test
        void 幽灵表上的字段含义即使标COMMENT也丢() {
            defaultSnapshot();
            modelOutputs("""
                    {"fields":[{"object":"t_ghost","name":"x","gloss":"什么什么","evidence":"COMMENT"}]}
                    """);
            var r = run();

            assertTrue(persisted().isEmpty());
            assertEquals(1, r.getDroppedUnknown());
            assertEquals(0, r.getDroppedGuess());
        }

        @Test
        void 关系任一端对不上就整条丢() {
            defaultSnapshot();
            modelOutputs("""
                    {"joins":[
                      {"object":"orders","column":"cid","to_object":"users","to_column":"uid","evidence":"NAME"},
                      {"object":"orders","column":"buyer_id","to_object":"users","to_column":"id","evidence":"NAME"}
                    ]}
                    """);
            var r = run();

            assertTrue(persisted().isEmpty());
            assertEquals(2, r.getDroppedUnknown());
        }
    }

    // ================================================================ ★ 唯一键去重

    /**
     * ★ 这一组是整个类里代价最大的一条。
     *
     * <p>{@code uk_connector_semantic} 是 (租户, 连接, scope, 表, 字段, 词条)，JOIN 行的键里
     * <b>只有左侧列</b>，同一列指向两张表就会撞。撞了不是「少一行」——{@code replaceInferred}
     * 是一个事务，重复键让它整个回滚，旧行已经删掉、新行一条也没插进去：
     * 一次几十秒的推导最后表现为「语义层空了」。所以必须在进库之前先合并。
     */
    @Nested
    @DisplayName("按唯一键去重，绝不把重复键送进那个事务")
    class DedupeBeforeInsert {

        @Test
        void 同一左侧列的两条关系只落一条() {
            defaultSnapshot();
            modelOutputs("""
                    {"joins":[
                      {"object":"orders","column":"cid","to_object":"users","to_column":"id","evidence":"NAME","confidence":60},
                      {"object":"orders","column":"cid","to_object":"shops","to_column":"id","evidence":"NAME","confidence":90}
                    ]}
                    """);
            var r = run();

            List<ConnectorSemantic> rows = persisted();
            ConnectorSemantic join = only(rows, ConnectorSemanticService.SCOPE_JOIN);
            // 留的是置信度高的那条，而不是先到的那条。
            assertEquals("shops", detailOf(join).get("to_object"));
            assertTrue(r.getNote().contains("重复 1"), "丢了什么必须出现在管理台那行字里：" + r.getNote());
        }

        @Test
        void 撞键时留下置信度高的那条_先到的更高也不会被顶掉() {
            defaultSnapshot();
            modelOutputs("""
                    {"joins":[
                      {"object":"orders","column":"cid","to_object":"users","to_column":"id","evidence":"NAME","confidence":90},
                      {"object":"orders","column":"cid","to_object":"shops","to_column":"id","evidence":"NAME","confidence":60}
                    ]}
                    """);
            run();

            assertEquals("users", detailOf(only(persisted(), ConnectorSemanticService.SCOPE_JOIN)).get("to_object"));
        }

        /** 同一列上的字段含义和关系 scope 不同，键就不同，两条都要留——去重不能过头。 */
        @Test
        void 同一列的字段含义与关系互不顶掉() {
            defaultSnapshot();
            modelOutputs("""
                    {"fields":[{"object":"orders","name":"cid","gloss":"下单用户 id","evidence":"NAME"}],
                     "joins":[{"object":"orders","column":"cid","to_object":"users","to_column":"id","evidence":"NAME"}]}
                    """);
            run();

            List<ConnectorSemantic> rows = persisted();
            assertEquals(1, scoped(rows, ConnectorSemanticService.SCOPE_FIELD).size());
            assertEquals(1, scoped(rows, ConnectorSemanticService.SCOPE_JOIN).size());
        }

        /** 重复的表用途、字段含义、歧义词条同样要合并。 */
        @Test
        void 表用途与歧义词条重复时也合并() {
            defaultSnapshot();
            modelOutputs("""
                    {"objects":[
                      {"name":"orders","gloss":"订单主表","evidence":"COMMENT","confidence":70},
                      {"name":"orders","gloss":"订单流水表","evidence":"COMMENT","confidence":95}
                    ],
                     "ambiguities":[
                      {"term":"销售额","question":"含不含退款？"},
                      {"term":"销售额","question":"含不含运费？"}
                    ]}
                    """);
            run();

            List<ConnectorSemantic> rows = persisted();
            assertEquals("订单流水表", only(rows, ConnectorSemanticService.SCOPE_OBJECT).getGloss());
            assertEquals(1, scoped(rows, ConnectorSemanticService.SCOPE_CAVEAT).size());
        }

        /** 兜底：无论模型吐什么，交给 replaceInferred 的那一批里不能有两行共用一个唯一键。 */
        @Test
        void 交给事务的那一批里唯一键两两不同() {
            defaultSnapshot();
            modelOutputs("""
                    {"objects":[
                      {"name":"orders","gloss":"订单表","evidence":"COMMENT"},
                      {"name":"orders","gloss":"订单表（重复）","evidence":"COMMENT"}
                    ],
                     "fields":[
                      {"object":"orders","name":"amt","gloss":"金额","evidence":"COMMENT"},
                      {"object":"orders","name":"amt","gloss":"金额（重复）","evidence":"COMMENT"}
                    ],
                     "joins":[
                      {"object":"orders","column":"cid","to_object":"users","to_column":"id","evidence":"NAME"},
                      {"object":"orders","column":"cid","to_object":"shops","to_column":"id","evidence":"NAME"}
                    ],
                     "ambiguities":[{"term":"销售额","question":"含不含退款？"},{"term":"销售额","question":"重复"}]}
                    """);
            run();

            List<ConnectorSemantic> rows = persisted();
            long distinct = rows.stream()
                    .map(x -> x.getScope() + '/' + x.getObjectName() + '/' + x.getFieldName() + '/' + x.getTerm())
                    .distinct().count();
            assertEquals(rows.size(), distinct);
            assertEquals(4, rows.size());
        }
    }

    // ================================================================ ★ JSON 修复

    /**
     * ★ 被 max_tokens 切断的输出。
     *
     * <p>一次推导要几十秒，因为最后一条 field 被切掉就整份丢弃太亏；但「把残缺当完整」正是这套设计
     * 从头到尾在防的事。所以修复必须<b>出声</b>，而且一个完整条目都没有时宁可失败也不编一个空壳。
     */
    @Nested
    @DisplayName("被截断的模型输出：修得回来就出声修，修不回来就失败")
    class JsonRepair {

        private String repair(String s) {
            return ConnectorSemanticDeriveService.repairTruncatedJson(s);
        }

        @Test
        void 完整JSON原样返回() {
            String s = "{\"objects\":[{\"name\":\"orders\",\"gloss\":\"订单表\"}],\"fields\":[]}";
            assertEquals(s, repair(s));
        }

        @Test
        void 对象中间被截断_收到最后一个完整条目() {
            String s = "{\"fields\":[{\"object\":\"orders\",\"name\":\"amt\",\"gloss\":\"金额\"},"
                    + "{\"object\":\"orders\",\"na";
            String r = repair(s);
            List<?> fields = (List<?>) parse(r).get("fields");
            assertEquals(1, fields.size());
        }

        @Test
        void 逗号之后被截断() {
            String s = "{\"fields\":[{\"name\":\"a\"},{\"name\":\"b\"},";
            List<?> fields = (List<?>) parse(repair(s)).get("fields");
            assertEquals(2, fields.size());
        }

        /** 字符串里的花括号不是结构，数进括号栈会把切点算到一个不合法的位置上。 */
        @Test
        void 字符串里的花括号不算结构() {
            String s = "{\"fields\":[{\"gloss\":\"金额（口径写成 {含税} 的那个）\"},{\"gloss\":\"这条被切了 {";
            Map<String, Object> m = parse(repair(s));
            List<?> fields = (List<?>) m.get("fields");
            assertEquals(1, fields.size());
            assertTrue(String.valueOf(((Map<?, ?>) fields.get(0)).get("gloss")).contains("{含税}"));
        }

        /**
         * ★ 转义引号。{@code \"} 不结束字符串——判错了会把后面的 {@code }]}} 当成真的结构闭合，
         * 于是「栈已经空了」，函数认为这串 JSON 是完整的、<b>原样返回一段解析不了的文本</b>。
         */
        @Test
        void 转义引号中间被截断仍能修回可解析的JSON() {
            String s = "{\"fields\":[{\"g\":\"x\"},{\"g\":\"他说 \\\"}]}";
            String r = repair(s);
            List<?> fields = (List<?>) parse(r).get("fields");
            assertEquals(1, fields.size());
        }

        @Test
        void 含转义引号的完整JSON不会被误判成截断() {
            String s = "{\"fields\":[{\"gloss\":\"他说 \\\"含税\\\" 的那个口径\"}]}";
            assertEquals(s, repair(s));
            assertEquals(1, ((List<?>) parse(s).get("fields")).size());
        }

        /** 一个完整条目都没有：原样返回，让调用方按失败处理。绝不补一对括号编个空壳出来。 */
        @Test
        void 一个完整条目都没有时拒绝修复() {
            String s = "{\"objects\":[{\"name\":\"orders\",\"gloss\":\"订单";
            assertEquals(s, repair(s));
        }

        // ---- 端到端：修复要出声，修不好要失败 ----

        @Test
        void 修复成功也必须写进note() {
            defaultSnapshot();
            modelOutputs("{\"fields\":[{\"object\":\"orders\",\"name\":\"amt\",\"gloss\":\"订单金额\",\"evidence\":\"COMMENT\"},"
                    + "{\"object\":\"orders\",\"name\":\"st");
            var r = run();

            assertTrue(r.isOk());
            assertEquals(1, persisted().size());
            assertTrue(r.getNote().contains("截断"), "修复过就必须说出来：" + r.getNote());
        }

        /**
         * ★ 修不回来时：整次推导失败，而且<b>一行都不写</b>。
         *
         * <p>{@code replaceInferred} 是先删后插，拿一份空产出去调它等于把上一次推好的说明书清空，
         * 换来的还是一个「看起来成功了」的状态。
         */
        @Test
        void 修不回来时整次失败且不碰已有语义() {
            defaultSnapshot();
            modelOutputs("{\"objects\":[{\"name\":\"orders\",\"gloss\":\"订单");
            var r = run();

            assertFalse(r.isOk());
            assertTrue(r.getNote().contains("推导失败"), r.getNote());
            verify(semanticService, never()).replaceInferred(any(), any());
            assertEquals(ConnectorSemanticDeriveService.SEM_FAILED, lastStatusWrite().getSemanticStatus());
        }

        @Test
        void markdown围栏被剥掉() {
            defaultSnapshot();
            modelOutputs("```json\n{\"fields\":[{\"object\":\"orders\",\"name\":\"amt\",\"gloss\":\"金额\",\"evidence\":\"COMMENT\"}]}\n```");
            var r = run();

            assertTrue(r.isOk());
            assertEquals(1, persisted().size());
            assertFalse(r.getNote().contains("截断"), "没截断就不该说截断：" + r.getNote());
        }

        @Test
        void 正文前面的闲话被剥掉() {
            defaultSnapshot();
            modelOutputs("我看了一下这套结构，产出如下：\n"
                    + "{\"fields\":[{\"object\":\"orders\",\"name\":\"amt\",\"gloss\":\"金额\",\"evidence\":\"COMMENT\"}]}");
            var r = run();

            assertTrue(r.isOk());
            assertEquals(1, persisted().size());
        }
    }

    // ================================================================ ★ 结构摘要截断

    /**
     * ★ <b>截断按整张表，绝不截半张表。</b>
     *
     * <p>半张表比少一张表糟得多：模型会对着看不见的列写字段含义，还会拿残缺的列集合去判断这张表是
     * 干什么的——而这两条产出看起来和正常产出一模一样。丢了哪些必须同时出现在提示词里（让模型知道
     * 自己看的是残卷）和 note 里（让管理台知道覆盖面）。
     */
    @Nested
    @DisplayName("结构摘要超限：按整张表丢，并说清丢了哪些")
    class DigestTruncation {

        private ConnectorSchema wide() {
            FieldDetail[] cols = new FieldDetail[50];
            for (int i = 0; i < cols.length; i++) {
                cols[i] = col(String.format("c%02d", i), "varchar(32)", null);
            }
            return snapshotOf("t_wide", null, cols);
        }

        @Test
        void 超限时被丢掉的是整张表而不是半张() {
            properties.getSemantic().setMaxDigestChars(100);
            snapshot(wide(),
                    snapshotOf("zz_second", null, col("id", "bigint", null)),
                    snapshotOf("zz_third", null, col("id", "bigint", null)));
            modelOutputs(EMPTY_BUT_WELL_FORMED);
            var r = run();

            String digest = digestBlock();
            // 第一张表整张都在：第一列和最后一列都要在，中间没有被切掉。
            assertTrue(digest.contains("## t_wide"));
            assertTrue(digest.contains("c00|"));
            assertTrue(digest.contains("c49|"));
            // 后两张一个字节都不在摘要里——不能出现只有表头没有列、或者只有半截列的残块。
            assertFalse(digest.contains("zz_second"));
            assertFalse(digest.contains("zz_third"));
            assertTrue(r.isTruncated());
        }

        @Test
        void 丢了哪些表要同时告诉模型和管理台() {
            properties.getSemantic().setMaxDigestChars(100);
            snapshot(wide(),
                    snapshotOf("zz_second", null, col("id", "bigint", null)),
                    snapshotOf("zz_third", null, col("id", "bigint", null)));
            modelOutputs(EMPTY_BUT_WELL_FORMED);
            var r = run();

            // 给模型的那份：摘要之外必须有一句「这份材料不完整」并点名。
            String prompt = promptSent();
            assertTrue(prompt.contains("这份材料不完整"));
            assertTrue(prompt.contains("zz_second") && prompt.contains("zz_third"));
            // 给人看的那份。
            assertTrue(r.getNote().contains("2 个对象未送进模型"), r.getNote());
            assertTrue(r.getNote().contains("zz_second"), r.getNote());
        }

        /** 第一张表无论多大都要进去，否则遇到一张超宽的表会得到一份空摘要——而它同样不会报错。 */
        @Test
        void 第一张表单独超限也要进摘要并说明只覆盖了它() {
            properties.getSemantic().setMaxDigestChars(100);
            snapshot(wide(), snapshotOf("zz_second", null, col("id", "bigint", null)));
            modelOutputs(EMPTY_BUT_WELL_FORMED);
            var r = run();

            assertTrue(digestBlock().contains("c49|"));
            assertTrue(r.getNote().contains("第一个对象单独就超过了摘要上限"), r.getNote());
        }

        @Test
        void 没超限时三张表都在且不报截断() {
            snapshot(snapshotOf("t_a", null, col("id", "bigint", null)),
                    snapshotOf("t_b", null, col("id", "bigint", null)),
                    snapshotOf("t_c", null, col("id", "bigint", null)));
            modelOutputs(EMPTY_BUT_WELL_FORMED);
            var r = run();

            String digest = digestBlock();
            assertTrue(digest.contains("## t_a") && digest.contains("## t_b") && digest.contains("## t_c"));
            assertFalse(r.isTruncated());
            assertFalse(r.getNote().contains("未送进模型"), r.getNote());
        }

        /**
         * 快照自己就是按名字字母序截到前 {@code SCHEMA_SNAPSHOT_CAP} 个的，字母序靠后的表根本没进过
         * connector_schema。这一层的沉默会被管理台读成「都覆盖了」，所以必须说出来。
         */
        @Test
        void 快照本身被截到上限时也要说出来() {
            List<ConnectorSchema> rows = new ArrayList<>();
            for (int i = 0; i < ConnectorSemanticDeriveService.SCHEMA_SNAPSHOT_CAP; i++) {
                rows.add(snapshotOf(String.format("t%03d", i), null));
            }
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(rows);
            modelOutputs(EMPTY_BUT_WELL_FORMED);
            var r = run();

            assertTrue(r.getNote().contains(String.valueOf(ConnectorSemanticDeriveService.SCHEMA_SNAPSHOT_CAP)),
                    r.getNote());
            assertTrue(r.getNote().contains("字母序"), r.getNote());
        }

        /** 权限只到部分表时快照里会有「只有名字」的行，要如实说，不能让模型读成「这是张空表」。 */
        @Test
        void 取不到字段的表如实标注() {
            snapshot(snapshotOf("t_locked", null));
            modelOutputs(EMPTY_BUT_WELL_FORMED);
            run();

            assertTrue(digestBlock().contains("本表字段未取到"));
        }
    }

    // ================================================================ ★ 落成语义行的形状

    /**
     * 锚点粒度、口径不预填、关系的不确定措辞——这三件事都只在写行的这一刻定型，
     * 写错了之后所有下游（漂移检测、注入层的措辞）都跟着错，而且不会报错。
     */
    @Nested
    @DisplayName("语义行的形状：锚点粒度 / 口径不预填 / 未经数据验证")
    class RowShape {

        @Test
        void 表用途不锚结构_加一个无关列不该让它失效() {
            defaultSnapshot();
            modelOutputs("""
                    {"objects":[{"name":"orders","gloss":"订单主表","evidence":"COMMENT","confidence":0.9,
                                 "table_shape":"DETAIL","typical_questions":["本月订单数"]}]}
                    """);
            run();

            ConnectorSemantic row = only(persisted(), ConnectorSemanticService.SCOPE_OBJECT);
            // 锚了整表指纹的话，客户加一个无关列就会把「这张表是订单主表」标成 STALE。
            assertEquals(ConnectorSemanticService.ANCHOR_NONE, row.getAnchorKind());
            assertNull(row.getAnchorHash());
            assertEquals(Integer.valueOf(90), row.getConfidence(), "0.9 这种小数要归一到 0-100");
            assertEquals("DETAIL", detailOf(row).get("table_shape"));
            // 推导只看结构：来源必须标 MODEL，注入层据此说「未经数据测量」。
            assertEquals("MODEL", detailOf(row).get("table_shape_source"));
        }

        /**
         * ★ 表形态只认四个枚举值。「事实表」这种自由文本从前原样落库，读它的模型没法据此决定怎么聚合；
         * 更糟的是把认不出来的写成 OTHER——那等于替模型编了一个它没说过的判断。
         */
        @Test
        void 表形态只认四个枚举_中文标签归一_认不出来的不写() {
            defaultSnapshot();
            modelOutputs("""
                    {"objects":[
                      {"name":"orders","gloss":"订单主表","evidence":"COMMENT","table_shape":"键值对表"},
                      {"name":"users","gloss":"用户表","evidence":"COMMENT","table_shape":"事实表"},
                      {"name":"shops","gloss":"门店表","evidence":"NAME","table_shape":"multi_metric_period"}]}
                    """);
            run();

            List<ConnectorSemantic> objects = scoped(persisted(), ConnectorSemanticService.SCOPE_OBJECT);
            Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
            objects.forEach(o -> byName.put(o.getObjectName(), detailOf(o)));
            assertEquals("KEY_VALUE", byName.get("orders").get("table_shape"));
            assertEquals("MULTI_METRIC_PERIOD", byName.get("shops").get("table_shape"));
            assertFalse(byName.get("users").containsKey("table_shape"), "认不出来的表形态不落成任何值");
            assertFalse(byName.get("users").containsKey("table_shape_source"));
        }

        @Test
        void 字段锚自己那一列() {
            defaultSnapshot();
            modelOutputs("""
                    {"fields":[{"object":"orders","name":"amt","gloss":"订单金额","evidence":"COMMENT"}]}
                    """);
            run();

            ConnectorSemantic row = only(persisted(), ConnectorSemanticService.SCOPE_FIELD);
            assertEquals(ConnectorSemanticService.ANCHOR_FIELD, row.getAnchorKind());
            assertEquals(ConnectorSemanticService.fieldAnchor(col("amt", "decimal(12,2)", "金额")),
                    row.getAnchorHash());
        }

        @Test
        void 关系锚两端且写死未经数据验证() {
            defaultSnapshot();
            modelOutputs("""
                    {"joins":[{"object":"orders","column":"cid","to_object":"users","to_column":"id",
                               "cardinality":"n:1","evidence":"NAME","note":"两边都是 bigint"}]}
                    """);
            run();

            ConnectorSemantic row = only(persisted(), ConnectorSemanticService.SCOPE_JOIN);
            assertEquals(ConnectorSemanticService.ANCHOR_JOIN, row.getAnchorKind());
            assertEquals(ConnectorSemanticService.joinAnchor(
                            col("cid", "bigint", null), col("id", "bigint", "主键")),
                    row.getAnchorHash());
            // P1 没有采样验证，所以每一条都必须是 NONE + 「未经数据验证」，不能让模型当外键用。
            assertEquals(ConnectorSemanticService.V_NONE, row.getVerified());
            assertEquals("未经数据验证", detailOf(row).get("basis"));
            assertTrue(row.getGloss().contains("未经数据验证"));
            assertEquals("N:1", detailOf(row).get("cardinality"), "基数要归一成大写");
        }

        /**
         * 「未经数据验证」和「结构已变」是两种不同的不确定，措辞不能合流。
         * 刚推出来的行只可能是前者——后者是漂移检测在 STALE 时才加的标记。
         */
        @Test
        void 刚推出来的行不带结构已变的措辞() {
            defaultSnapshot();
            modelOutputs("""
                    {"joins":[{"object":"orders","column":"cid","to_object":"users","to_column":"id","evidence":"NAME"}]}
                    """);
            run();

            assertTrue(persisted().stream().noneMatch(
                    x -> x.getGloss() != null && x.getGloss().contains("结构已变")));
            assertTrue(persisted().stream().allMatch(
                    x -> ConnectorSemanticService.ST_DRAFT.equals(x.getStatus())));
        }

        @Test
        void 认不出的基数被丢掉而不是原样写进去() {
            defaultSnapshot();
            modelOutputs("""
                    {"joins":[{"object":"orders","column":"cid","to_object":"users","to_column":"id",
                               "cardinality":"many-to-one","evidence":"NAME"}]}
                    """);
            run();

            ConnectorSemantic row = only(persisted(), ConnectorSemanticService.SCOPE_JOIN);
            assertNull(detailOf(row).get("cardinality"));
            assertFalse(row.getGloss().contains("many-to-one"));
        }

        /**
         * ★ 口径不预填。歧义只能落成 CAVEAT（一个待人回答的问题），绝不能落成 METRIC
         * （一条已经澄清的口径）——那等于平台自己编了一条口径，是整套设计最不许发生的那件事。
         * 连模型主动吐一个 metrics 数组都不该有任何东西落地。
         */
        @Test
        void 歧义落成CAVEAT而不是METRIC() {
            defaultSnapshot();
            modelOutputs("""
                    {"ambiguities":[{"term":"销售额","question":"销售额是按订单金额还是按实收？退款扣不扣？",
                                     "applies_to":["orders.amt"]}],
                     "metrics":[{"term":"销售额","gloss":"sum(amt)","evidence":"NAME"}]}
                    """);
            run();

            List<ConnectorSemantic> rows = persisted();
            assertTrue(scoped(rows, ConnectorSemanticService.SCOPE_METRIC).isEmpty(),
                    "推导这条路径永远不许产出 METRIC");
            ConnectorSemantic caveat = only(rows, ConnectorSemanticService.SCOPE_CAVEAT);
            assertEquals("销售额", caveat.getTerm());
            assertTrue(caveat.getGloss().contains("退款扣不扣"));
            // 歧义不是一条断言，贴任何依据标签都是把话说反了。
            assertNull(caveat.getEvidence());
            assertEquals(ConnectorSemanticService.ANCHOR_NONE, caveat.getAnchorKind());
            assertEquals(List.of("orders.amt"), detailOf(caveat).get("applies_to"));
        }

        /** object_name / field_name / term 是 NOT NULL DEFAULT ''：写 null 会让它们在唯一键上不参与去重。 */
        @Test
        void 三个键列一律给空串不给null() {
            defaultSnapshot();
            modelOutputs("""
                    {"objects":[{"name":"orders","gloss":"订单主表","evidence":"COMMENT"}],
                     "fields":[{"object":"orders","name":"amt","gloss":"金额","evidence":"COMMENT"}],
                     "joins":[{"object":"orders","column":"cid","to_object":"users","to_column":"id","evidence":"NAME"}],
                     "ambiguities":[{"term":"销售额","question":"含不含退款？"}]}
                    """);
            run();

            List<ConnectorSemantic> rows = persisted();
            assertEquals(4, rows.size());
            for (ConnectorSemantic r : rows) {
                assertNotNull(r.getObjectName(), r.getScope() + " 行的 object_name 不能是 null");
                assertNotNull(r.getFieldName(), r.getScope() + " 行的 field_name 不能是 null");
                assertNotNull(r.getTerm(), r.getScope() + " 行的 term 不能是 null");
                assertEquals(ConnectorSemanticService.ST_DRAFT, r.getStatus());
                assertEquals(ConnectorSemanticService.V_NONE, r.getVerified());
            }
        }

        /** 模型有时给 0.8 有时给 80，两种都得认；越界的夹回 0-100，不认识的当没给。 */
        @Test
        void 置信度归一到0到100() {
            snapshot(snapshotOf("orders", null,
                    col("a", "int", null), col("b", "int", null),
                    col("c", "int", null), col("d", "int", null)));
            modelOutputs("""
                    {"fields":[
                      {"object":"orders","name":"a","gloss":"甲","evidence":"NAME","confidence":0.8},
                      {"object":"orders","name":"b","gloss":"乙","evidence":"NAME","confidence":80},
                      {"object":"orders","name":"c","gloss":"丙","evidence":"NAME","confidence":250},
                      {"object":"orders","name":"d","gloss":"丁","evidence":"NAME","confidence":"很高"}
                    ]}
                    """);
            run();

            Map<String, Integer> got = new LinkedHashMap<>();
            for (ConnectorSemantic r : persisted()) {
                got.put(r.getFieldName(), r.getConfidence());
            }
            assertEquals(Integer.valueOf(80), got.get("a"));
            assertEquals(Integer.valueOf(80), got.get("b"));
            assertEquals(Integer.valueOf(100), got.get("c"));
            assertNull(got.get("d"));
        }

        /** 缺 gloss / 缺 name 的半条产出直接跳过，不能落一行空壳。 */
        @Test
        void 缺关键字段的条目被跳过() {
            defaultSnapshot();
            modelOutputs("""
                    {"objects":[{"name":"orders","evidence":"COMMENT"},{"gloss":"没有名字","evidence":"COMMENT"}],
                     "fields":[{"object":"orders","gloss":"没有列名","evidence":"COMMENT"}],
                     "ambiguities":[{"term":"销售额"}]}
                    """);
            run();

            assertTrue(persisted().isEmpty());
        }
    }

    // ================================================================ ★ 失败不外抛

    /**
     * ★ 语义层是叠加的注解，不是连接可用的前提。推导失败只能体现在返回值和 connection 的三列上，
     * 绝不能把一次正常的建连/刷新整个搞失败。
     */
    @Nested
    @DisplayName("推导失败只记状态，绝不外抛，也绝不清空已有语义")
    class FailuresAreContained {

        @Test
        void 模型调用炸了不抛异常且不动已有语义() {
            defaultSnapshot();
            when(claudeService.messagesInternal(any(), any())).thenThrow(new RuntimeException("502 Bad Gateway"));

            var r = run();
            assertFalse(r.isOk());
            assertTrue(r.getNote().contains("推导失败") && r.getNote().contains("502"), r.getNote());
            verify(semanticService, never()).replaceInferred(any(), any());
            assertEquals(ConnectorSemanticDeriveService.SEM_FAILED, lastStatusWrite().getSemanticStatus());
        }

        /** 异常摘要要带类名：NPE 这类 message 为 null 的异常，否则界面上只剩一个孤零零的 "null"。 */
        @Test
        void message为null的异常也要留下线索() {
            defaultSnapshot();
            when(claudeService.messagesInternal(any(), any())).thenThrow(new NullPointerException());

            var r = run();
            assertTrue(r.getNote().contains("NullPointerException"), r.getNote());
            assertFalse(r.getNote().contains("null"), "不能只剩一个孤零零的 null：" + r.getNote());
        }

        /** 开关关着不是失败，但也不能什么都不写——「点了没反应」是最难查的一类。 */
        @Test
        void 开关关闭时不叫模型但要留下原因() {
            properties.getSemantic().setEnabled(false);

            var r = run();
            assertFalse(r.isOk());
            assertTrue(r.getNote().contains("关闭"), r.getNote());
            verify(claudeService, never()).messagesInternal(any(), any());
            assertNotNull(lastStatusWrite().getSemanticNote());
            // 不标 FAILED：开关关着不是失败。
            assertNull(lastStatusWrite().getSemanticStatus());
        }

        @Test
        void 快照为空时给出可操作的提示() {
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(List.of());

            var r = run();
            assertFalse(r.isOk());
            assertTrue(r.getNote().contains("刷新结构"), "提示要说清下一步怎么办：" + r.getNote());
            verify(claudeService, never()).messagesInternal(any(), any());
            assertEquals(ConnectorSemanticDeriveService.SEM_FAILED, lastStatusWrite().getSemanticStatus());
        }

        @Test
        void 连接不存在或不属于本租户时直接返回不推导() {
            when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(null);

            var r = run();
            assertFalse(r.isOk());
            assertEquals("连接不存在", r.getNote());
            verify(claudeService, never()).messagesInternal(any(), any());
        }

        /** 单张表的快照 JSON 坏了，这张表没字段可推，其余的照推——不能让整次推导失败。 */
        @Test
        void 单张表的快照JSON坏掉不影响其它表() {
            ConnectorSchema broken = new ConnectorSchema();
            broken.setObjectName("t_broken");
            broken.setObjectType("BASE TABLE");
            broken.setDetailJson("{坏掉的");
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(List.of(broken,
                    snapshotOf("orders", null, col("amt", "decimal(12,2)", "金额"))));
            modelOutputs("""
                    {"fields":[{"object":"orders","name":"amt","gloss":"订单金额","evidence":"COMMENT"}]}
                    """);

            var r = run();
            assertTrue(r.isOk());
            assertEquals(1, persisted().size());
        }

        /** 模型返回一段根本不是 JSON 的话：失败，而不是当成「没有任何语义」写进去。 */
        @Test
        void 模型返回非JSON时失败而不是写空() {
            defaultSnapshot();
            modelOutputs("我需要更多信息才能完成这个任务。");

            var r = run();
            assertFalse(r.isOk());
            verify(semanticService, never()).replaceInferred(any(), any());
        }
    }

    // ================================================================ ★ 空产出不许替换

    /**
     * ★ 这一组防的是<b>唯一一种会真的丢数据</b>的失败：产出为空却照样替换。
     *
     * <p>{@code replaceInferred} 是先物理删 INFERRED 再整批插。模型回一个 <code>{}</code> 时解析得动、
     * 只是一条也产不出来——旧说明书被删光，状态还写成 READY。那看起来是一次成功的重新生成
     * （「只是这次没生成出东西」），而人不会去查一次成功的操作。
     */
    @Nested
    @DisplayName("产出为空时不替换，保留上一版说明书")
    class RefuseEmptyReplace {

        /** 四个键一个都没有 = 模型根本没做这件事。哪怕以前一行都没有，也不能当成一次成功。 */
        @Test
        void 四个键一个都没有时不替换也不报READY() {
            defaultSnapshot();
            modelOutputs("{}");

            var r = run();

            assertFalse(r.isOk());
            verify(semanticService, never()).replaceInferred(any(), any());
            assertEquals(ConnectorSemanticDeriveService.SEM_FAILED, lastStatusWrite().getSemanticStatus());
            assertTrue(r.getNote().contains("保留"), "必须说清上一版还在：" + r.getNote());
        }

        /** 模型只回了一个我们不认的键，同样算「什么都没回」。 */
        @Test
        void 只回了认不出的键时不替换() {
            defaultSnapshot();
            modelOutputs("{\"tables\":[{\"name\":\"orders\"}]}");

            var r = run();

            assertFalse(r.isOk());
            verify(semanticService, never()).replaceInferred(any(), any());
        }

        /** 键都在但条目全被丢光，而上一版有推断行：宁可留旧的，也不要用空的去换。 */
        @Test
        void 条目全被丢光且上一版非空时保留旧的() {
            defaultSnapshot();
            ConnectorSemantic old = new ConnectorSemantic();
            old.setScope(ConnectorSemanticService.SCOPE_FIELD);
            old.setSource(ConnectorSemanticService.SOURCE_INFERRED);
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of(old));
            // 全都是 GUESS，一条也留不下来。
            modelOutputs("""
                    {"objects":[],"joins":[],"ambiguities":[],
                     "fields":[{"object":"orders","name":"amt","gloss":"金额","evidence":"GUESS"}]}
                    """);

            var r = run();

            assertFalse(r.isOk());
            verify(semanticService, never()).replaceInferred(any(), any());
            assertTrue(r.getNote().contains("不替换"), r.getNote());
            assertEquals(ConnectorSemanticDeriveService.SEM_FAILED, lastStatusWrite().getSemanticStatus());
        }

        /** 但上一版本来就是空的时候没有东西可丢，空产出照常落地，不该硬报一个失败。 */
        @Test
        void 上一版本来就空时允许落一批空的() {
            defaultSnapshot();
            when(semanticService.all(CONNECTOR_ID)).thenReturn(List.of());
            modelOutputs(EMPTY_BUT_WELL_FORMED);

            var r = run();

            assertTrue(r.isOk());
            assertTrue(persisted().isEmpty());
        }
    }

    // ================================================================ ★ 大小写与长度

    /**
     * ★ 唯一键落在 utf8mb4_unicode_ci 上，{@code ord_id} 和 {@code ORD_ID} 在库里是同一个键
     * （mysql:8.0 实测 ERROR 1062）。按大小写敏感去重会让两条都过闸，然后在那个事务里撞键——
     * 旧行已删、新行一条没插，整层语义当场清空。
     */
    @Nested
    @DisplayName("去重按 ci 排序规则折叠大小写；键列超长的整条丢")
    class CollationAndLength {

        /**
         * 快照里<b>真的会</b>同时出现 {@code T_Ord} 和 {@code t_ord}：MySQL 在 Linux 上
         * （{@code lower_case_table_names=0}）表名是区分大小写的，而唯一键的排序规则不区分。
         * 两条各自对得上快照、Java 的 Map 也认为它们是两张表，进了库却是同一个键。
         */
        @Test
        void 表名只有大小写不同的两条关系必须合并成一条() {
            snapshot(snapshotOf("T_Ord", null, col("ord_id", "bigint", null)),
                    snapshotOf("t_ord", null, col("ord_id", "bigint", null)),
                    snapshotOf("users", null, col("id", "bigint", null)),
                    snapshotOf("shops", null, col("id", "bigint", null)));
            modelOutputs("""
                    {"joins":[
                      {"object":"t_ord","column":"ord_id","to_object":"users","to_column":"id","evidence":"NAME","confidence":60},
                      {"object":"T_Ord","column":"ord_id","to_object":"shops","to_column":"id","evidence":"NAME","confidence":90}
                    ]}
                    """);
            var r = run();

            List<ConnectorSemantic> rows = persisted();
            ConnectorSemantic join = only(rows, ConnectorSemanticService.SCOPE_JOIN);
            // 留置信度高的那条；而且留下来的行里存的仍然是模型原样的大小写（要和快照对得上）。
            assertEquals("shops", detailOf(join).get("to_object"));
            assertEquals("T_Ord", join.getObjectName());
            assertTrue(r.getNote().contains("重复 1"), r.getNote());
        }

        @Test
        void 表名只有大小写不同的两条表用途也合并() {
            snapshot(snapshotOf("Orders", null, col("id", "bigint", null)),
                    snapshotOf("orders", null, col("id", "bigint", null)));
            modelOutputs("""
                    {"objects":[
                      {"name":"orders","gloss":"订单表","evidence":"COMMENT","confidence":70},
                      {"name":"Orders","gloss":"订单表（另一种写法）","evidence":"COMMENT","confidence":90}
                    ]}
                    """);
            run();

            assertEquals(1, scoped(persisted(), ConnectorSemanticService.SCOPE_OBJECT).size());
        }

        /** 词条是模型自由发挥的，同一次产出里冒出 {@code GMV} 和 {@code gmv} 完全正常。 */
        @Test
        void 词条只有大小写不同时也要合并() {
            defaultSnapshot();
            modelOutputs("""
                    {"ambiguities":[{"term":"GMV","question":"含不含取消单？"},
                                    {"term":"gmv","question":"含不含运费？"}]}
                    """);
            run();

            assertEquals(1, scoped(persisted(), ConnectorSemanticService.SCOPE_CAVEAT).size());
        }

        /**
         * term 是 varchar(191)，而且注入时靠它<b>精确匹配</b>。截短的词条永远匹配不上，
         * 却长得和一条真词条一模一样——宁可整条丢掉。
         */
        @Test
        void 超长词条整条丢弃而不是截断() {
            defaultSnapshot();
            String longTerm = "销".repeat(200);
            modelOutputs("{\"ambiguities\":[{\"term\":\"" + longTerm + "\",\"question\":\"这算什么？\"},"
                    + "{\"term\":\"销售额\",\"question\":\"含不含退款？\"}]}");

            var r = run();

            List<ConnectorSemantic> rows = persisted();
            ConnectorSemantic caveat = only(rows, ConnectorSemanticService.SCOPE_CAVEAT);
            assertEquals("销售额", caveat.getTerm());
            assertEquals(1, r.getDroppedTooLong());
            assertTrue(rows.stream().noneMatch(x -> x.getTerm() != null && x.getTerm().length() > 191));
        }
    }

    // ================================================================ ★ 已答过的口径不再重提

    /**
     * ★ 人在对话里答过「销售额扣退款」之后又重新推导一次，如果还把同一个问题当成待确认写回去，
     * conn_catalog 会把<b>答案和问题一起</b>注入，模型只能二选一——那是我们自己制造的矛盾，
     * 而且是在已经花过一次人力澄清之后制造的。
     */
    @Nested
    @DisplayName("人已经答过的口径不再作为待确认重提")
    class AnsweredAmbiguities {

        private ConnectorSemantic metric(String term, String status) {
            ConnectorSemantic m = new ConnectorSemantic();
            m.setScope(ConnectorSemanticService.SCOPE_METRIC);
            m.setTerm(term);
            m.setGloss("按实付金额，扣退款");
            m.setSource(ConnectorSemanticService.SOURCE_HUMAN);
            m.setStatus(status);
            return m;
        }

        @Test
        void 已确认的口径词条不再产出CAVEAT() {
            defaultSnapshot();
            when(semanticService.all(CONNECTOR_ID))
                    .thenReturn(List.of(metric("销售额", ConnectorSemanticService.ST_CONFIRMED)));
            modelOutputs("""
                    {"ambiguities":[{"term":"销售额","question":"含不含退款？"},
                                    {"term":"活跃用户","question":"按登录还是按下单？"}]}
                    """);

            var r = run();

            List<ConnectorSemantic> rows = persisted();
            ConnectorSemantic caveat = only(rows, ConnectorSemanticService.SCOPE_CAVEAT);
            assertEquals("活跃用户", caveat.getTerm());
            assertEquals(1, r.getSkippedAnswered());
        }

        /** 比对要折叠大小写和首尾空格，否则「GMV」和「gmv」会被当成两个词条。 */
        @Test
        void 词条比对忽略大小写与首尾空格() {
            defaultSnapshot();
            when(semanticService.all(CONNECTOR_ID))
                    .thenReturn(List.of(metric("GMV", ConnectorSemanticService.ST_CONFIRMED)));
            modelOutputs("""
                    {"ambiguities":[{"term":" gmv ","question":"含不含取消单？"}]}
                    """);

            run();

            assertTrue(scoped(persisted(), ConnectorSemanticService.SCOPE_CAVEAT).isEmpty());
        }

        /** 只有【人已经答过】才算数：一条 STALE 的口径说明它挂的结构变了，问题应该重新提出来。 */
        @Test
        void 口径已过期时问题要重新提出来() {
            defaultSnapshot();
            when(semanticService.all(CONNECTOR_ID))
                    .thenReturn(List.of(metric("销售额", ConnectorSemanticService.ST_STALE)));
            modelOutputs("""
                    {"ambiguities":[{"term":"销售额","question":"含不含退款？"}]}
                    """);

            var r = run();

            assertEquals(1, scoped(persisted(), ConnectorSemanticService.SCOPE_CAVEAT).size());
            assertEquals(0, r.getSkippedAnswered());
        }
    }

    // ================================================================ ★ 并发与结构漂移

    /**
     * ★ 两次推导同时在跑，状态和库里的行会各走各的：后完成的那次覆盖状态、先完成的那次的行留在库里。
     * 更糟的是一次<b>后完成的失败</b>能把一次成功盖回 FAILED，而这两件事都没有地方会报出来。
     */
    @Nested
    @DisplayName("同一条连接同一时刻只跑一次推导；推导期间结构被刷新则整批作废")
    class Concurrency {

        @Test
        void 认领不到时直接退出_不叫模型也不写行() {
            defaultSnapshot();
            // 带条件的 UPDATE 影响 0 行 = 这条连接已经是 RUNNING，另一次推导正在跑。
            when(connectionMapper.update(any(), any())).thenReturn(0);

            var r = run();

            assertFalse(r.isOk());
            assertTrue(r.getNote().contains("已有一次推导在进行中"), r.getNote());
            verify(claudeService, never()).messagesInternal(any(), any());
            verify(semanticService, never()).replaceInferred(any(), any());
        }

        /**
         * ★ 推导期间有人刷新了结构：这一批行锚的是<b>旧</b>结构，落库时 status 却是 DRAFT。
         * 那一轮漂移检测已经跑完了，不会回头看它们——这批错行永远不会被标成 STALE。
         * 宁可明说作废让人重来。
         */
        @Test
        void 推导期间结构被刷新则整批作废() {
            ConnectorSchema before = snapshotOf("orders", null, col("amt", "decimal(12,2)", "金额"));
            before.setSyncedAt(new Date(1_700_000_000_000L));
            ConnectorSchema after = snapshotOf("orders", null, col("amt", "decimal(12,2)", "金额"));
            after.setSyncedAt(new Date(1_700_000_600_000L));
            // 第一次读到旧快照，落库前再读已经是刷新过的那一份。
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(List.of(before), List.of(after));
            modelOutputs("""
                    {"fields":[{"object":"orders","name":"amt","gloss":"订单金额","evidence":"COMMENT"}]}
                    """);

            var r = run();

            assertFalse(r.isOk());
            assertTrue(r.getNote().contains("结构在推导期间已刷新"), r.getNote());
            verify(semanticService, never()).replaceInferred(any(), any());
            assertEquals(ConnectorSemanticDeriveService.SEM_FAILED, lastStatusWrite().getSemanticStatus());
        }
    }

    // ================================================================ ★ 接入即触发：快照为空时先补拉

    /**
     * ★ {@code ConnectorService.create()} 只插 connection 一行，全仓库写 connector_schema 的只有
     * 手动端点 {@code POST /{id}/schema/refresh}。没有这一步补拉，「接入即触发」的那次推导
     * <b>必然</b>读到空快照、必然写 FAILED——每一条新建的连接都显示「语义层：失败」，
     * 而 P1 的主打卖点正是「给个只读账号就能用」。
     *
     * <p>补拉只在<b>后台线程</b>那条路上做：它要打客户库（catalog + 最多 200 次 describe），
     * 放进建连的请求线程就是让建连接口等客户库跑完。
     */
    @Nested
    @DisplayName("后台推导：快照为空时先补拉一次结构")
    class BootstrapSnapshot {

        /** 后台任务那条路。它不返回结果（结果写在 connection 的 semantic_* 三列上供前端轮询）。 */
        private void backgroundRun() {
            service.runDerive(CONNECTOR_ID, "t1");
        }

        @Test
        void 快照为空时自动补拉一次再推导() {
            ConnectorSchema fresh = snapshotOf("orders", null, col("amt", "decimal(12,2)", "金额"));
            // 先是空的，补拉之后就有了。
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(List.of(), List.of(fresh));
            when(schemaService.refresh(CONNECTOR_ID)).thenReturn(
                    new ConnectorSchemaService.SnapshotResult(1, 1, false, null, true, List.of(), new Date(), null, null));
            modelOutputs("""
                    {"fields":[{"object":"orders","name":"amt","gloss":"订单金额","evidence":"COMMENT"}]}
                    """);

            backgroundRun();

            verify(schemaService).refresh(CONNECTOR_ID);
            assertEquals(1, persisted().size());
            assertEquals(ConnectorSemanticDeriveService.SEM_READY, lastStatusWrite().getSemanticStatus());
        }

        /** 补拉只做一次：补完还是空的，才是真的失败（客户账号可能一张表都看不到）。 */
        @Test
        void 补拉之后仍然为空才算失败() {
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(List.of());
            when(schemaService.refresh(CONNECTOR_ID)).thenReturn(
                    new ConnectorSchemaService.SnapshotResult(0, 0, false, null, true, List.of(), new Date(), null, null));

            backgroundRun();

            // 恰好一次：补拉是「补一次看看」，不是重试循环。
            verify(schemaService).refresh(CONNECTOR_ID);
            verify(claudeService, never()).messagesInternal(any(), any());
            Connection wrote = lastStatusWrite();
            assertEquals(ConnectorSemanticDeriveService.SEM_FAILED, wrote.getSemanticStatus());
            // 「拉过了但一张表都看不见」和「还没拉过」是两回事，提示里得说清下一步做什么。
            assertTrue(wrote.getSemanticNote().contains("授权"), wrote.getSemanticNote());
            assertTrue(wrote.getSemanticNote().contains("刷新结构"), wrote.getSemanticNote());
        }

        /**
         * ★ HTTP 连接器只声明 {INVOKE, HEALTH}，refresh 会抛「不支持自描述」。
         * 那不是故障——它是一条<b>完全健康</b>的连接，只是语义层对它不适用。
         * 标成「失败」会让人去修一个没坏的东西，而真失败的那几条混在假警报里没人看。
         */
        @Test
        void 不支持自描述的连接器记不适用而不是失败() {
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(List.of());
            when(schemaService.refresh(CONNECTOR_ID)).thenThrow(new ServiceException(
                    ExceptionCode.OPERATION_UNSUPPORTED, "这种连接器类型不支持自描述，无法拉取结构"));

            backgroundRun();

            Connection wrote = lastStatusWrite();
            assertEquals(ConnectorSemanticDeriveService.SEM_NOT_APPLICABLE, wrote.getSemanticStatus());
            assertTrue(wrote.getSemanticNote().contains("不适用"), wrote.getSemanticNote());
            verify(claudeService, never()).messagesInternal(any(), any());
        }

        /** 快照已经有了就<b>不碰客户库</b>：重跑对客户侧是零访问，这条不变量不能破。 */
        @Test
        void 快照非空时不补拉_一个字节都不发到客户那边() {
            defaultSnapshot();
            modelOutputs("""
                    {"fields":[{"object":"orders","name":"amt","gloss":"订单金额","evidence":"COMMENT"}]}
                    """);

            backgroundRun();

            verify(schemaService, never()).refresh(any());
        }

        /** 公开的同步入口（请求线程可能调它）永远不补拉：它只读快照。 */
        @Test
        void 同步入口不会去打客户库() {
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(List.of());

            var r = run();

            verify(schemaService, never()).refresh(any());
            assertFalse(r.isOk());
            assertTrue(r.getNote().contains("刷新结构"), r.getNote());
        }
    }

    // ================================================================ 后台单次推导的说明前缀

    @Nested
    @DisplayName("后台单次推导的说明前缀")
    class AsyncNotePrefix {

        private ConnectorSemanticDeriveService inlineAsyncService() {
            ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
            doAnswer(invocation -> {
                invocation.<Runnable>getArgument(0).run();
                return null;
            }).when(executor).execute(any(Runnable.class));
            return new ConnectorSemanticDeriveService(
                    schemaService, semanticService, connectionMapper, claudeService, properties,
                    mock(SemanticSqlCorpusReader.class), mock(SemanticJoinValidator.class),
                    mock(SemanticValueProfiler.class), mock(ConnectorSemanticMapper.class),
                    mock(ConnectorAuditService.class),
                    executor, mock(ThreadPoolTaskExecutor.class), mock(TableShapeDetector.class),
                    mock(org.redisson.api.RedissonClient.class));
        }

        private void runAsync(String prefix) {
            TenantContext.set("t1");
            inlineAsyncService().deriveAsync(CONNECTOR_ID, prefix);
        }

        @Test
        void 成功说明稳定以非空前缀开头() {
            defaultSnapshot();
            modelOutputs(EMPTY_BUT_WELL_FORMED);

            runAsync("降级原因：sandbox 未配置");

            assertTrue(lastStatusWrite().getSemanticNote().startsWith("降级原因：sandbox 未配置。"),
                    lastStatusWrite().getSemanticNote());
        }

        @Test
        void 禁用说明稳定以非空前缀开头() {
            properties.getSemantic().setEnabled(false);

            runAsync("手工前缀");

            assertEquals("手工前缀。语义层推导已关闭（connector.semantic.enabled=false）",
                    lastStatusWrite().getSemanticNote());
        }

        @Test
        void 失败说明稳定以非空前缀开头() {
            defaultSnapshot();
            when(claudeService.messagesInternal(any(), any())).thenThrow(new RuntimeException("上游超时"));

            runAsync("降级原因：健康检查失败");

            assertTrue(lastStatusWrite().getSemanticNote().startsWith("降级原因：健康检查失败。推导失败："),
                    lastStatusWrite().getSemanticNote());
        }

        @Test
        void 不适用说明稳定以非空前缀开头() {
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(List.of());
            when(schemaService.refresh(CONNECTOR_ID)).thenThrow(new ServiceException(
                    ExceptionCode.OPERATION_UNSUPPORTED, "这种连接器类型不支持自描述"));

            runAsync("降级原因：无 DESCRIBE");

            assertTrue(lastStatusWrite().getSemanticNote().startsWith("降级原因：无 DESCRIBE。"),
                    lastStatusWrite().getSemanticNote());
            assertEquals(ConnectorSemanticDeriveService.SEM_NOT_APPLICABLE,
                    lastStatusWrite().getSemanticStatus());
        }

        @Test
        void 旧重载委托空前缀且说明不变() {
            defaultSnapshot();
            modelOutputs(EMPTY_BUT_WELL_FORMED);
            TenantContext.set("t1");

            inlineAsyncService().deriveAsync(CONNECTOR_ID);

            assertTrue(lastStatusWrite().getSemanticNote().startsWith("覆盖 "),
                    lastStatusWrite().getSemanticNote());
        }
    }

    // ================================================================ 没有租户上下文时不落后台线程

    /**
     * 租户一旦丢了，推导写库是<b>静默</b>错的：查询照常成功，只是落在 {@code __no_tenant__} 哨兵上。
     * 所以没有租户上下文时宁可不推。
     */
    @Test
    @DisplayName("没有租户上下文时不把推导丢进后台线程")
    void 没有租户上下文时不排任务() {
        TenantContext.clear();
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        ConnectorSemanticDeriveService s = new ConnectorSemanticDeriveService(
                schemaService, semanticService, connectionMapper, claudeService, properties,
                mock(SemanticSqlCorpusReader.class), mock(SemanticJoinValidator.class),
                mock(SemanticValueProfiler.class), mock(ConnectorSemanticMapper.class),
                mock(ConnectorAuditService.class),
                executor, mock(ThreadPoolTaskExecutor.class), mock(TableShapeDetector.class), mock(org.redisson.api.RedissonClient.class));

        s.deriveAsync(CONNECTOR_ID);

        verify(executor, never()).execute(any(Runnable.class));
    }

    // ================================================================ ★ 认领戳与成功戳必须分家

    /**
     * 这一组钉的是一个<b>只在排查时才会被发现</b>的设计错误：如果认领凭据和「上次成功时间」
     * 挤在 {@code semantic_synced_at} 一列里，那么每次推导开始都会覆盖它、每次失败的重试也会覆盖它，
     * 于是「上次什么时候还是好的」这条信息——恰恰是出问题时最想要的那一条——正好没了。
     *
     * <p>所以分成两列：{@code semantic_claim_at} 是机器用的并发凭据，
     * {@code semantic_synced_at} 只在<b>成功</b>时盖戳。
     */
    @Nested
    @DisplayName("认领戳写 claim_at，成功戳只在 READY 时盖 synced_at")
    class Timestamps {

        /** 按写入顺序拿到每一次状态回写的实体。第一次必然是认领。 */
        private List<Connection> statusWrites() {
            ArgumentCaptor<Connection> cap = ArgumentCaptor.forClass(Connection.class);
            verify(connectionMapper, atLeastOnce()).update(cap.capture(), any());
            return cap.getAllValues();
        }

        @Test
        void 认领只写claim_at_不碰synced_at() {
            defaultSnapshot();
            modelOutputs(EMPTY_BUT_WELL_FORMED);

            run();

            Connection claim = statusWrites().get(0);
            assertEquals("RUNNING", claim.getSemanticStatus());
            assertNotNull(claim.getSemanticClaimAt(), "认领必须落下凭据，否则卡死的 RUNNING 永远抢不回来");
            assertNull(claim.getSemanticSyncedAt(),
                    "认领不是一次成功，绝不能盖 synced_at——那会把上次成功时间抹掉");
        }

        @Test
        void 推导成功才盖synced_at() {
            defaultSnapshot();
            modelOutputs("""
                    {"objects":[{"name":"orders","gloss":"订单主表，一行=一个订单头","evidence":"COMMENT"}],
                     "fields":[],"joins":[],"ambiguities":[]}
                    """);

            var r = run();

            assertTrue(r.isOk());
            Connection last = lastStatusWrite();
            assertEquals("READY", last.getSemanticStatus());
            assertNotNull(last.getSemanticSyncedAt());
        }

        /**
         * ★ 本组的重点。失败的重试<b>不动</b> synced_at：MyBatis-Plus 默认 NOT_NULL 更新策略，
         * 实体里这一列为 null 就不会进 SET 子句，库里上一次成功的时间原样留着。
         */
        @Test
        void 推导失败不盖synced_at_上次成功的时间得以保留() {
            defaultSnapshot();
            when(claudeService.messagesInternal(any(), any())).thenThrow(new RuntimeException("上游超时"));

            var r = run();

            assertFalse(r.isOk());
            Connection last = lastStatusWrite();
            assertEquals("FAILED", last.getSemanticStatus());
            assertNull(last.getSemanticSyncedAt(),
                    "失败盖戳等于删掉「上次什么时候还是好的」，而那正是排查时唯一有用的一条");
        }

        /** 「不适用」同理：它根本没生成过，更不该写一个成功时间。 */
        @Test
        void 不适用不盖synced_at() {
            when(schemaService.currentRows(CONNECTOR_ID)).thenReturn(List.of());
            when(schemaService.refresh(CONNECTOR_ID))
                    .thenThrow(new ServiceException(ExceptionCode.OPERATION_UNSUPPORTED, "该连接器不支持自描述"));

            service.runDerive(CONNECTOR_ID, "t1");

            Connection last = lastStatusWrite();
            assertEquals("NOT_APPLICABLE", last.getSemanticStatus());
            assertNull(last.getSemanticSyncedAt());
        }
    }
}
