package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ANCHOR_FIELD;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ANCHOR_JOIN;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ANCHOR_NONE;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.CATALOG_GLOSS_MAX;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.EV_COMMENT;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.EV_GUESS;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.EV_NAME;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.HISTORY_MAX;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SCOPE_FIELD;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SCOPE_JOIN;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SCOPE_METRIC;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SCOPE_OBJECT;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SOURCE_HUMAN;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SOURCE_IMPORTED;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.SOURCE_INFERRED;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ST_CONFIRMED;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ST_DRAFT;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.ST_STALE;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.V_NONE;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.V_WEAK;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.fieldAnchor;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.joinAnchor;
import static com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService.shortGloss;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语义层的读写与漂移处置。
 *
 * <p><b>这个类里每一条失败都是静默的。</b>语义层错了不会抛异常、不会进审计、不会让任何一次
 * conn_query 变红——它只是让模型读到一句听起来很合理的假话，然后给出一个看起来很正常的错数字。
 * 所以这里测的不是「方法有没有返回值」，而是四类会悄悄发生的事故：
 *
 * <ol>
 *   <li><b>假过期</b>：客户加了一个无关列，「这张表是订单主表」被标成 STALE。锚点粒度一旦退化成
 *       整表指纹，第一次刷新就会把整条连接的语义层清空，而日志里只有一行「标记过期=137」。</li>
 *   <li><b>漏过期</b>：列的含义变了而锚点没跟着变，语义层继续把旧说明喂给模型——这是唯一
 *       比没有语义层更糟的状态。</li>
 *   <li><b>人答的口径被机器覆盖</b>：重新推导把 HUMAN 行冲掉，或者推断路径能写出 HUMAN 行。</li>
 *   <li><b>越租户删除</b>：{@code physicalDeleteInferred} 是一条<b>不带 tenant_id 条件</b>的裸 DELETE，
 *       归属校验一旦挪到它后面，传错一个 connectorId 就抹掉别家的语义层，且没有任何报错。</li>
 * </ol>
 *
 * <p>纯单测：不起 Spring、不连库、不叫模型。{@code @Transactional} 在直接 new 出来的实例上没有代理，
 * 正好也把「事务边界」这个与本类逻辑无关的维度排除掉。
 */
class ConnectorSemanticServiceTest {

    private static final Long CONN_ID = 100L;
    private static final String TENANT = "t1";

    private ConnectorSemanticMapper semanticMapper;
    private ConnectionMapper connectionMapper;
    private ConnectorSemanticService service;

    /**
     * MyBatis-Plus 的 lambda 列缓存平时由 Spring 扫 mapper 时建立，纯单测里没有 Spring，
     * 于是 {@code LambdaQueryWrapper.eq(Entity::getX, ...)} 会直接抛
     * 「can not find lambda cache for this entity」。这里手工建一次。
     *
     * <p>顺带的好处：实体上压根没有那个属性时在这里就会炸，而不是等到真连库才发现。
     */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ConnectorSemantic.class);
    }

    @BeforeEach
    void setUp() {
        semanticMapper = mock(ConnectorSemanticMapper.class);
        connectionMapper = mock(ConnectionMapper.class);
        service = new ConnectorSemanticService(semanticMapper, connectionMapper);
        // 回写一律带「仍是读到时的样子」为条件；单测里没有并发写者，条件总成立。
        when(semanticMapper.update(any(), any())).thenReturn(1);
    }

    /** 连接存在且属于本租户。不 stub 它就等于「连接不存在」，那是另一组用例。 */
    private void givenConnectionExists() {
        Connection c = new Connection();
        c.setId(CONN_ID);
        c.setTenantId(TENANT);
        c.setName("crm");
        when(connectionMapper.selectById(CONN_ID)).thenReturn(c);
    }

    // ================================================================ 客户库的列（测试夹具）

    private static final FieldDetail ORD_ID =
            new FieldDetail("id", "bigint", false, "订单ID", "PK");
    private static final FieldDetail ORD_USER_ID =
            new FieldDetail("user_id", "bigint", true, "下单人", null);
    /** 刻意照抄不推断枚举含义的写法：语义层允许说「取值 0/1/2/3」，不允许说「0=待支付」。 */
    private static final FieldDetail ORD_STATUS =
            new FieldDetail("status", "tinyint", true, "状态码，取值 0/1/2/3，含义需确认", null);
    private static final FieldDetail USR_ID =
            new FieldDetail("id", "bigint", false, "用户ID", "PK");

    private static Map<String, FieldDetail> cols(FieldDetail... fs) {
        Map<String, FieldDetail> m = new LinkedHashMap<>();
        for (FieldDetail f : fs) {
            m.put(f.name(), f);
        }
        return m;
    }

    /** 刷新后只剩 orders 一张表的常态结构。 */
    private static Map<String, Map<String, FieldDetail>> ordersOnly(FieldDetail... fs) {
        Map<String, Map<String, FieldDetail>> m = new LinkedHashMap<>();
        m.put("orders", cols(fs));
        return m;
    }

    private static Map<String, Map<String, FieldDetail>> ordersAndUsers(
            Map<String, FieldDetail> orders, Map<String, FieldDetail> users) {
        Map<String, Map<String, FieldDetail>> m = new LinkedHashMap<>();
        m.put("orders", orders);
        m.put("users", users);
        return m;
    }

    // ================================================================ 语义行（测试夹具）

    private static long seq = 1;

    private static ConnectorSemantic base(String scope, String objectName, String fieldName) {
        ConnectorSemantic r = new ConnectorSemantic();
        r.setId(seq++);
        r.setTenantId(TENANT);
        r.setConnectorId(CONN_ID);
        r.setScope(scope);
        r.setObjectName(objectName);
        r.setFieldName(fieldName);
        r.setSource(SOURCE_INFERRED);
        r.setStatus(ST_DRAFT);
        r.setVerified(V_NONE);
        return r;
    }

    /** OBJECT 行：不锚结构。 */
    private static ConnectorSemantic objectRow(String table) {
        ConnectorSemantic r = base(SCOPE_OBJECT, table, "");
        r.setGloss("订单主表");
        r.setEvidence(EV_COMMENT);
        r.setAnchorKind(ANCHOR_NONE);
        r.setAnchorHash(null);
        return r;
    }

    /** FIELD 行：锚自己那一列写入时的样子。 */
    private static ConnectorSemantic fieldRow(String table, FieldDetail anchoredOn) {
        ConnectorSemantic r = base(SCOPE_FIELD, table, anchoredOn.name());
        r.setGloss("字段含义");
        r.setEvidence(EV_COMMENT);
        r.setAnchorKind(ANCHOR_FIELD);
        r.setAnchorHash(fieldAnchor(anchoredOn));
        return r;
    }

    /** JOIN 行：锚两端列写入时的样子，对端表/列记在 detail_json 里。 */
    private static ConnectorSemantic joinRow(String table, FieldDetail left,
                                             String toTable, FieldDetail right) {
        ConnectorSemantic r = base(SCOPE_JOIN, table, left.name());
        r.setGloss("关联 " + toTable + "." + right.name() + "。本条未经数据验证。");
        r.setEvidence(EV_NAME);
        r.setDetailJson(joinDetail(toTable, right.name()));
        r.setAnchorKind(ANCHOR_JOIN);
        r.setAnchorHash(joinAnchor(left, right));
        return r;
    }

    /** METRIC 行：口径挂在整条连接上，object_name / field_name 都是空串。 */
    private static ConnectorSemantic metricRow(String term, String gloss) {
        ConnectorSemantic r = base(SCOPE_METRIC, "", "");
        r.setTerm(term);
        r.setGloss(gloss);
        r.setSource(SOURCE_HUMAN);
        r.setEvidence(EV_GUESS);
        r.setStatus(ST_CONFIRMED);
        r.setAnchorKind(ANCHOR_NONE);
        return r;
    }

    private static String joinDetail(String toObject, String toColumn) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("to_object", toObject);
        d.put("to_column", toColumn);
        d.put("basis", "未经数据验证");
        return json(d);
    }

    private static String json(Object o) {
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<?> parseList(String s) {
        try {
            return CommonUtil.getObjectMapper().readValue(s, List.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void givenRows(ConnectorSemantic... rows) {
        when(semanticMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(rows)));
    }

    // ================================================================================
    // 锚点指纹
    // ================================================================================

    /**
     * 锚点是整套漂移检测的地基：它算进去的东西变了，语义就被标过期；没算进去的东西变了，
     * 语义就继续被当成事实喂给模型。所以这一组测的是<b>边界画在哪</b>，而不是「哈希能不能算出来」。
     */
    @Nested
    @DisplayName("锚点指纹")
    class Anchors {

        @Test
        @DisplayName("列类型变了，字段锚点就变")
        void 列类型变化改变字段锚点() {
            FieldDetail before = new FieldDetail("amount", "decimal(12,2)", true, "订单金额", null);
            FieldDetail after = new FieldDetail("amount", "decimal(14,4)", true, "订单金额", null);
            assertNotEquals(fieldAnchor(before), fieldAnchor(after));
        }

        /**
         * 可空性是会改变查询正确性的：一列从 NOT NULL 变成可空，之前「这列一定有值」的说明
         * 就不再成立，而 COUNT / JOIN 的结果会跟着悄悄变。
         */
        @Test
        @DisplayName("可空性变了，字段锚点就变")
        void 可空性变化改变字段锚点() {
            FieldDetail before = new FieldDetail("amount", "decimal(12,2)", false, "订单金额", null);
            FieldDetail after = new FieldDetail("amount", "decimal(12,2)", true, "订单金额", null);
            assertNotEquals(fieldAnchor(before), fieldAnchor(after));
        }

        /** 列注释是语义的来源。客户改注释往往正是口径变了，这时候语义层必须先失效再说。 */
        @Test
        @DisplayName("列注释变了，字段锚点就变")
        void 列注释变化改变字段锚点() {
            FieldDetail before = new FieldDetail("amount", "decimal(12,2)", true, "订单金额（含税）", null);
            FieldDetail after = new FieldDetail("amount", "decimal(12,2)", true, "订单金额（不含税）", null);
            assertNotEquals(fieldAnchor(before), fieldAnchor(after));
        }

        /**
         * 没有注释与空注释是同一件事。不做这个归一，同一列在两个驱动下（一个返回 null、
         * 一个返回空串）会算出两个锚点，于是每次刷新都报一次假过期。
         */
        @Test
        @DisplayName("注释从 null 变空串不算变化")
        void 空注释与无注释同一个锚点() {
            FieldDetail nul = new FieldDetail("amount", "decimal(12,2)", true, null, null);
            FieldDetail empty = new FieldDetail("amount", "decimal(12,2)", true, "", null);
            assertEquals(fieldAnchor(nul), fieldAnchor(empty));
        }

        /**
         * ★ 锚点与 {@code ConnectorSchemaService.structureFingerprint} 的每列那一段<b>刻意同形</b>。
         *
         * <p>两边判据一旦分叉，出现的是一类不会报错的怪状态：刷新报了 CHANGED，而挂在这张表上的
         * 语义一条都没失效（漏过期）；或者反过来，刷新说没变而语义全被标过期（假过期）。
         * 两种都只能靠人肉比对发现。这条用矩阵把「同形」钉死，而不是钉死某个具体字符串。
         */
        @Test
        @DisplayName("字段锚点与结构指纹对「什么算结构变了」判断一致")
        void 锚点与结构指纹同形() {
            List<FieldDetail> variants = List.of(
                    new FieldDetail("amount", "decimal(12,2)", true, "订单金额", "默认 0"),
                    // 只有 extra 不同：两边都不该认为这是结构变化
                    new FieldDetail("amount", "decimal(12,2)", true, "订单金额", "无默认值"),
                    new FieldDetail("amount", "decimal(14,4)", true, "订单金额", "默认 0"),
                    new FieldDetail("amount", "decimal(12,2)", false, "订单金额", "默认 0"),
                    new FieldDetail("amount", "decimal(12,2)", true, "订单金额（不含税）", "默认 0"),
                    new FieldDetail("total", "decimal(12,2)", true, "订单金额", "默认 0"));

            for (FieldDetail a : variants) {
                for (FieldDetail b : variants) {
                    boolean sameAnchor = fieldAnchor(a).equals(fieldAnchor(b));
                    boolean samePrint = fingerprintOf(a).equals(fingerprintOf(b));
                    assertEquals(samePrint, sameAnchor,
                            "锚点与结构指纹对这两列的判断分叉了：" + a + " / " + b);
                }
            }
        }

        private String fingerprintOf(FieldDetail f) {
            return ConnectorSchemaService.structureFingerprint(
                    new ObjectDetail("orders", "BASE TABLE", null, List.of(f), Map.of()));
        }

        @Test
        @DisplayName("关联锚点：左端列变了就变")
        void 左端变化改变关联锚点() {
            FieldDetail leftAfter = new FieldDetail("user_id", "varchar(32)", true, "下单人", null);
            assertNotEquals(joinAnchor(ORD_USER_ID, USR_ID), joinAnchor(leftAfter, USR_ID));
        }

        /**
         * ★ 右端在另一张表上。只锚左端的话，客户把 users.id 从 bigint 改成 varchar，
         * 这条关联会继续被当成可 join 的关系注入，而 join 出来的结果是空集或笛卡尔积。
         */
        @Test
        @DisplayName("关联锚点：右端列变了也变")
        void 右端变化改变关联锚点() {
            FieldDetail rightAfter = new FieldDetail("id", "varchar(32)", false, "用户ID", "PK");
            assertNotEquals(joinAnchor(ORD_USER_ID, USR_ID), joinAnchor(ORD_USER_ID, rightAfter));
        }

        /** 关联是有方向的：orders.user_id → users.id 和反过来不是同一条断言。 */
        @Test
        @DisplayName("关联锚点区分左右方向")
        void 关联锚点区分方向() {
            assertNotEquals(joinAnchor(ORD_USER_ID, USR_ID), joinAnchor(USR_ID, ORD_USER_ID));
        }
    }

    // ================================================================================
    // 漂移
    // ================================================================================

    /**
     * <b>本测试类的核心。</b>锚点粒度按 scope 分，是整个设计里最容易被「统一按表哈希」简化掉的一处，
     * 而简化掉之后的表现是：客户加一个无关列，整条连接的语义层被一次刷新全部标成过期，
     * 日志里只有一行「标记过期=137」，没人会去看。所以下面每一条「该标」都配一条「长得像但不该标」。
     */
    @Nested
    @DisplayName("漂移：锚点粒度按 scope 分")
    class Drift {

        @BeforeEach
        void ownConnection() {
            givenConnectionExists();
        }

        /**
         * ★ 全类最重要的一条。
         *
         * <p>「这张表是订单主表」不会因为客户加了一个 channel 列而变成假话。OBJECT 行若锚整表指纹
         * （{@code connector_schema.content_hash} 正是这种整表指纹），加一列就会把它标成过期——
         * 这正是上游设计里搞错的那一处。
         */
        @Test
        @DisplayName("OBJECT 行不因无关列新增而过期")
        void OBJECT行不因加列过期() {
            ConnectorSemantic obj = objectRow("orders");
            givenRows(obj);

            FieldDetail added = new FieldDetail("channel", "varchar(32)", true, "下单渠道", null);
            ConnectorSemanticService.StaleResult r = service.applyDrift(
                    CONN_ID, ordersOnly(ORD_ID, ORD_STATUS, added), List.of());

            assertEquals(0, r.staled());
            assertEquals(ST_DRAFT, obj.getStatus());
            // 一行都不该写库：每次刷新都 UPDATE 一遍全表语义，本身也是个问题。
            verify(semanticMapper, never()).update(any(), any());
        }

        /** 表整个没了，挂在它上面的一切都失效——这时候锚点粒度不参与判断。 */
        @Test
        @DisplayName("表消失时 OBJECT 行过期")
        void 表消失时OBJECT行过期() {
            ConnectorSemantic obj = objectRow("orders");
            givenRows(obj);

            ConnectorSemanticService.StaleResult r =
                    service.applyDrift(CONN_ID, Map.of(), List.of("orders"));

            assertEquals(1, r.staled());
            assertEquals(ST_STALE, obj.getStatus());
        }

        @Test
        @DisplayName("FIELD 行在自己那一列变了时过期")
        void FIELD行自己那列变了就过期() {
            ConnectorSemantic f = fieldRow("orders", ORD_STATUS);
            givenRows(f);

            FieldDetail changed = new FieldDetail("status", "varchar(16)", true,
                    "状态码，取值 0/1/2/3，含义需确认", null);
            ConnectorSemanticService.StaleResult r =
                    service.applyDrift(CONN_ID, ordersOnly(ORD_ID, changed), List.of());

            assertEquals(1, r.staled());
            assertEquals(ST_STALE, f.getStatus());
        }

        /** ★ 与上一条配对：同一张表、同一次刷新，变的是别的列。 */
        @Test
        @DisplayName("FIELD 行不因同表其它列变化而过期")
        void FIELD行不因别的列过期() {
            ConnectorSemantic f = fieldRow("orders", ORD_STATUS);
            givenRows(f);

            FieldDetail otherChanged = new FieldDetail("id", "varchar(32)", false, "订单ID", "PK");
            ConnectorSemanticService.StaleResult r =
                    service.applyDrift(CONN_ID, ordersOnly(otherChanged, ORD_STATUS), List.of());

            assertEquals(0, r.staled());
            assertEquals(ST_DRAFT, f.getStatus());
        }

        /** 列被删掉时算不出锚点。算不出来必须按过期处理，不能因为「没法比」就放过。 */
        @Test
        @DisplayName("FIELD 行的列被删掉时过期")
        void FIELD行的列被删就过期() {
            ConnectorSemantic f = fieldRow("orders", ORD_STATUS);
            givenRows(f);

            ConnectorSemanticService.StaleResult r =
                    service.applyDrift(CONN_ID, ordersOnly(ORD_ID), List.of());

            assertEquals(1, r.staled());
            assertEquals(ST_STALE, f.getStatus());
        }

        @Test
        @DisplayName("JOIN 行两端都没变时不过期")
        void JOIN行两端不变不过期() {
            ConnectorSemantic j = joinRow("orders", ORD_USER_ID, "users", USR_ID);
            givenRows(j);

            ConnectorSemanticService.StaleResult r = service.applyDrift(CONN_ID,
                    ordersAndUsers(cols(ORD_ID, ORD_USER_ID), cols(USR_ID)), List.of());

            assertEquals(0, r.staled());
            assertEquals(ST_DRAFT, j.getStatus());
        }

        /**
         * ★ 对端在<b>另一张表</b>上，而这条语义行的 object_name 是 orders。
         * 只看本表的实现会完全漏掉这种变化：orders 一个字没改，语义却已经不成立了。
         */
        @Test
        @DisplayName("JOIN 行在对端列变了时过期")
        void JOIN行对端变了就过期() {
            ConnectorSemantic j = joinRow("orders", ORD_USER_ID, "users", USR_ID);
            givenRows(j);

            FieldDetail usrIdChanged = new FieldDetail("id", "varchar(32)", false, "用户ID", "PK");
            ConnectorSemanticService.StaleResult r = service.applyDrift(CONN_ID,
                    ordersAndUsers(cols(ORD_ID, ORD_USER_ID), cols(usrIdChanged)), List.of());

            assertEquals(1, r.staled());
            assertEquals(ST_STALE, j.getStatus());
        }

        /** 对端表整个消失：removedObjects 里是 users，而这条行挂在 orders 上，只能靠锚点算不出来兜住。 */
        @Test
        @DisplayName("JOIN 行在对端表消失时过期")
        void JOIN行对端表消失就过期() {
            ConnectorSemantic j = joinRow("orders", ORD_USER_ID, "users", USR_ID);
            givenRows(j);

            ConnectorSemanticService.StaleResult r = service.applyDrift(
                    CONN_ID, ordersOnly(ORD_ID, ORD_USER_ID), List.of("users"));

            assertEquals(1, r.staled());
            assertEquals(ST_STALE, j.getStatus());
        }

        /** detail 坏掉时对端是未知的，这条关系就无法再被确认——按过期处理，不按「没变」处理。 */
        @Test
        @DisplayName("JOIN 行 detail 坏掉时过期而不是当作没变")
        void JOIN行detail坏掉就过期() {
            ConnectorSemantic j = joinRow("orders", ORD_USER_ID, "users", USR_ID);
            j.setDetailJson("{坏掉的");
            givenRows(j);

            ConnectorSemanticService.StaleResult r = service.applyDrift(CONN_ID,
                    ordersAndUsers(cols(ORD_ID, ORD_USER_ID), cols(USR_ID)), List.of());

            assertEquals(1, r.staled());
            assertEquals(ST_STALE, j.getStatus());
        }

        /**
         * METRIC 行的 object_name 是空串。少了 {@code !objectName.isEmpty()} 那道判断，
         * 任何一次「消失表名列表里混进空串」都会把整条连接的业务口径全部标成过期——
         * 而口径恰恰是唯一由人答出来、重推不回来的那部分。
         */
        @Test
        @DisplayName("表消失不牵连挂在连接上的口径行")
        void 表消失不牵连口径行() {
            ConnectorSemantic metric = metricRow("销售额", "已支付订单的实付金额合计，扣退款");
            ConnectorSemantic obj = objectRow("orders");
            givenRows(metric, obj);

            ConnectorSemanticService.StaleResult r = service.applyDrift(
                    CONN_ID, Map.of(), new ArrayList<>(List.of("orders", "")));

            assertEquals(1, r.staled());
            assertEquals(ST_STALE, obj.getStatus());
            assertEquals(ST_CONFIRMED, metric.getStatus(), "口径行不该被表的消失牵连");
        }

        // ──────────────────────────────────────────── 可撤销

        /**
         * ★ STALE 必须可撤销。做不到的话，一次假过期（比如某张表 describe 授权临时缺失）
         * 会把语义永久钉死，而语义层没有任何人工恢复入口——那等于永久损坏。
         */
        @Test
        @DisplayName("结构恢复后 INFERRED 行回到 DRAFT")
        void 结构恢复后推断行回到DRAFT() {
            ConnectorSemantic f = fieldRow("orders", ORD_STATUS);
            f.setStatus(ST_STALE);
            givenRows(f);

            ConnectorSemanticService.StaleResult r =
                    service.applyDrift(CONN_ID, ordersOnly(ORD_ID, ORD_STATUS), List.of());

            assertEquals(0, r.staled());
            assertEquals(1, r.revived());
            assertEquals(ST_DRAFT, f.getStatus());
        }

        /**
         * ★ 人确认过的行回到 CONFIRMED，不是 DRAFT。
         *
         * <p>退回 DRAFT 等于把一次结构抖动变成「人工确认被撤销」——那份确认是人花时间给的，
         * 结构改回来之后它依然成立。而且 DRAFT/CONFIRMED 在注入层的措辞不同，
         * 降级会让模型对一条其实已确认的口径继续说「待确认」。
         */
        @Test
        @DisplayName("结构恢复后 HUMAN 行回到 CONFIRMED 而不是 DRAFT")
        void 结构恢复后人工行回到CONFIRMED() {
            ConnectorSemantic f = fieldRow("orders", ORD_STATUS);
            f.setSource(SOURCE_HUMAN);
            f.setStatus(ST_STALE);
            givenRows(f);

            service.applyDrift(CONN_ID, ordersOnly(ORD_ID, ORD_STATUS), List.of());

            assertEquals(ST_CONFIRMED, f.getStatus());
        }

        /** IMPORTED 不是 HUMAN：它是采信客户库注释得来的，注释变了本来就该重走推断，回到 DRAFT。 */
        @Test
        @DisplayName("结构恢复后 IMPORTED 行回到 DRAFT")
        void 结构恢复后导入行回到DRAFT() {
            ConnectorSemantic f = fieldRow("orders", ORD_STATUS);
            f.setSource(SOURCE_IMPORTED);
            f.setStatus(ST_STALE);
            givenRows(f);

            service.applyDrift(CONN_ID, ordersOnly(ORD_ID, ORD_STATUS), List.of());

            assertEquals(ST_DRAFT, f.getStatus());
        }

        /** 已经是 STALE 而且依然对不上的行不该再写一次库：刷新是周期性的，写放大会一直累计。 */
        @Test
        @DisplayName("仍然对不上的 STALE 行不重复写库")
        void 仍然过期的行不重复写库() {
            ConnectorSemantic f = fieldRow("orders", ORD_STATUS);
            f.setStatus(ST_STALE);
            givenRows(f);

            ConnectorSemanticService.StaleResult r =
                    service.applyDrift(CONN_ID, ordersOnly(ORD_ID), List.of());

            assertEquals(0, r.staled());
            assertEquals(0, r.revived());
            verify(semanticMapper, never()).update(any(), any());
        }

        /** 一次刷新里两种变化同时发生，计数要分得开——否则报表里看不出到底出了什么事。 */
        @Test
        @DisplayName("一次漂移里分别统计标记与恢复")
        void 标记与恢复分别计数() {
            ConnectorSemantic willStale = fieldRow("orders", ORD_STATUS);
            ConnectorSemantic willRevive = fieldRow("orders", ORD_ID);
            willRevive.setStatus(ST_STALE);
            givenRows(willStale, willRevive);

            FieldDetail statusChanged = new FieldDetail("status", "varchar(16)", true,
                    "状态码，取值 0/1/2/3，含义需确认", null);
            ConnectorSemanticService.StaleResult r =
                    service.applyDrift(CONN_ID, ordersOnly(ORD_ID, statusChanged), List.of());

            assertEquals(1, r.staled());
            assertEquals(1, r.revived());
            assertEquals(ST_STALE, willStale.getStatus());
            assertEquals(ST_DRAFT, willRevive.getStatus());
            verify(semanticMapper, times(2)).update(any(), any());
        }

        /** 归属校验在读之前。连接不存在就不该有任何后续动作。 */
        @Test
        @DisplayName("连接不存在时不碰语义表")
        void 连接不存在时不碰语义表() {
            when(connectionMapper.selectById(CONN_ID)).thenReturn(null);

            assertThrows(ServiceException.class,
                    () -> service.applyDrift(CONN_ID, ordersOnly(ORD_ID), List.of()));
            verify(semanticMapper, never()).selectList(any());
            verify(semanticMapper, never()).update(any(), any());
        }
    }

    // ================================================================================
    // 口径沉淀
    // ================================================================================

    /**
     * 口径是整个语义层里<b>唯一不允许被推断出来</b>的那一类：模型看到 {@code amount} 会非常自信地
     * 认为「销售额 = SUM(amount)」，而扣不扣退款、算不算运费这些只有业务方知道。
     * 所以这条路径的价值全在「问完之后记住」，以及「记住之后不被悄悄改掉」。
     */
    @Nested
    @DisplayName("口径沉淀")
    class DefineMetric {

        @BeforeEach
        void ownConnection() {
            givenConnectionExists();
        }

        private ConnectorSemantic captureInsert() {
            ArgumentCaptor<ConnectorSemantic> cap = ArgumentCaptor.forClass(ConnectorSemantic.class);
            verify(semanticMapper).insert(cap.capture());
            return cap.getValue();
        }

        @Test
        @DisplayName("新建口径落成 HUMAN / CONFIRMED / GUESS 的 METRIC 行")
        void 新建口径的形状() {
            when(semanticMapper.selectOne(any())).thenReturn(null);

            Date before = new Date();
            service.defineMetric(CONN_ID, "销售额", "已支付订单的实付金额合计，扣退款",
                    Map.of("excludes", List.of("退款")), "u9", "张三", "trace-1");

            ConnectorSemantic row = captureInsert();
            assertEquals(SCOPE_METRIC, row.getScope());
            // 口径挂在整条连接上，不挂某张表：空串而不是 null，唯一键才对得上。
            assertEquals("", row.getObjectName());
            assertEquals("", row.getFieldName());
            assertEquals("销售额", row.getTerm());
            assertEquals("已支付订单的实付金额合计，扣退款", row.getGloss());
            // 租户从连接行上取，不从调用方拿。
            assertEquals(TENANT, row.getTenantId());
            assertEquals(CONN_ID, row.getConnectorId());
            assertEquals(SOURCE_HUMAN, row.getSource());
            assertEquals(ST_CONFIRMED, row.getStatus());
            // 人答的口径依据就是「人说的」：它恰恰是库里查不到答案的那一类，不是 COMMENT 也不是 DATA。
            assertEquals(EV_GUESS, row.getEvidence());
            assertEquals(V_NONE, row.getVerified());
            // 口径不锚任何结构：客户加一列不会让「销售额扣退款」这句话失效。
            assertEquals(ANCHOR_NONE, row.getAnchorKind());
            assertNull(row.getAnchorHash());
            assertTrue(row.getDetailJson().contains("excludes"));
            assertEquals("u9", row.getAnsweredBy());
            assertEquals("张三", row.getAnsweredName());
            assertNotNull(row.getAnsweredAt());
            assertTrue(row.getAnsweredAt().getTime() >= before.getTime());
            assertEquals("trace-1", row.getTraceId());
            // 第一次沉淀没有旧值可留痕。
            assertNull(row.getHistoryJson());
        }

        /**
         * 确定性改写靠 term <b>精确匹配</b>，不做模糊识别。词条两端带空格会变成一条查不到也
         * 覆盖不了的孤儿行，下一次同样的问答会再插一条，直到撞唯一键。
         */
        @Test
        @DisplayName("词条与说明两端空白被裁掉")
        void 词条与说明被裁剪() {
            when(semanticMapper.selectOne(any())).thenReturn(null);

            service.defineMetric(CONN_ID, "  销售额  ", "  实付金额合计  ", null, "u9", "张三", null);

            ConnectorSemantic row = captureInsert();
            assertEquals("销售额", row.getTerm());
            assertEquals("实付金额合计", row.getGloss());
        }

        // ──────────────────────────────────────────── 覆盖

        private ConnectorSemantic existingMetric() {
            ConnectorSemantic e = metricRow("销售额", "订单金额合计");
            e.setDetailJson(json(Map.of("excludes", List.of())));
            e.setAnsweredBy("u1");
            e.setAnsweredName("李四");
            e.setAnsweredAt(new Date(1_700_000_000_000L));
            e.setTraceId("trace-old");
            when(semanticMapper.selectOne(any())).thenReturn(e);
            return e;
        }

        /**
         * ★ 覆盖必须<b>原地改</b>，不能再插一行。
         *
         * <p>{@code uk_connector_semantic} 不含 deleted，本表也没有软删入口——插第二行会直接撞键，
         * 表现是一次对话里模型的 {@code conn_define_metric} 无解释地失败。
         */
        @Test
        @DisplayName("覆盖既有口径时原地更新，不插新行")
        void 覆盖是原地更新() {
            ConnectorSemantic e = existingMetric();

            ConnectorSemantic returned = service.defineMetric(
                    CONN_ID, "销售额", "已支付订单的实付金额合计，扣退款", null, "u9", "张三", "trace-2");

            assertSame(e, returned);
            verify(semanticMapper).update(any(), any());
            verify(semanticMapper, never()).insert(any());
            assertEquals("已支付订单的实付金额合计，扣退款", e.getGloss());
        }

        /**
         * ★ 旧口径进留痕。
         *
         * <p>平台不提供任何管理台的口径纠正入口，任何能对话的人都能覆盖口径、且不做权限区分。
         * 这是那个已知代价的唯一取证材料：等到发现口径被改坏，没有历史连「什么时候开始错的」都查不出来。
         */
        @Test
        @DisplayName("覆盖时把旧说明追加进留痕")
        void 覆盖时旧说明进留痕() {
            ConnectorSemantic e = existingMetric();
            String oldDetail = e.getDetailJson();

            service.defineMetric(CONN_ID, "销售额", "已支付订单的实付金额合计，扣退款",
                    Map.of("excludes", List.of("退款")), "u9", "张三", "trace-2");

            List<?> hist = parseList(e.getHistoryJson());
            assertEquals(1, hist.size());
            Map<?, ?> entry = (Map<?, ?>) hist.get(0);
            // 留痕只存旧值——现值就在行上，不必重复。
            assertEquals("订单金额合计", entry.get("from_gloss"));
            assertEquals(oldDetail, entry.get("from_detail"));
            assertNotNull(entry.get("at"));
        }

        @Test
        @DisplayName("覆盖后回答人与回答时间换成这次答的人")
        void 覆盖后换成新的回答人() {
            ConnectorSemantic e = existingMetric();
            Date oldAt = e.getAnsweredAt();

            service.defineMetric(CONN_ID, "销售额", "新口径", null, "u9", "张三", "trace-2");

            assertEquals("u9", e.getAnsweredBy());
            assertEquals("张三", e.getAnsweredName());
            assertEquals("trace-2", e.getTraceId());
            assertTrue(e.getAnsweredAt().after(oldAt));
            // 覆盖过的口径依然是人答的、依然是已确认的。
            assertEquals(SOURCE_HUMAN, e.getSource());
            assertEquals(ST_CONFIRMED, e.getStatus());
        }

        /**
         * ★ 不传 detail 不等于「把 detail 清空」。
         *
         * <p>模型在对话里往往只补一句话、不重发结构化细节。把 null 当成清空，
         * 上一次好不容易问出来的口径边界（扣哪些、算哪些）会无声消失，
         * 而 gloss 还在，看起来一切正常。
         */
        @Test
        @DisplayName("覆盖时不传 detail 不清空既有 detail")
        void 不传detail不清空既有detail() {
            ConnectorSemantic e = existingMetric();
            String oldDetail = e.getDetailJson();

            service.defineMetric(CONN_ID, "销售额", "新口径", null, "u9", "张三", null);

            assertEquals(oldDetail, e.getDetailJson());
        }

        /** 留痕是取证材料，不是时间机器：只保留最近 HISTORY_MAX 条，挤掉最老的那条。 */
        @Test
        @DisplayName("留痕按 HISTORY_MAX 封顶，最老一条被挤掉")
        void 留痕按上限封顶() {
            ConnectorSemantic e = existingMetric();
            List<Map<String, Object>> seed = new ArrayList<>();
            for (int i = 0; i < HISTORY_MAX; i++) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("from_gloss", "第" + i + "版");
                seed.add(one);
            }
            e.setHistoryJson(json(seed));
            e.setGloss("最新一版");

            service.defineMetric(CONN_ID, "销售额", "更新的一版", null, "u9", "张三", null);

            List<?> hist = parseList(e.getHistoryJson());
            assertEquals(HISTORY_MAX, hist.size());
            // 最老的「第0版」被挤掉，「第1版」成了队首。
            assertEquals("第1版", ((Map<?, ?>) hist.get(0)).get("from_gloss"));
            assertEquals("最新一版", ((Map<?, ?>) hist.get(hist.size() - 1)).get("from_gloss"));
        }

        /** 留痕坏了不该挡住这次覆盖：它是取证材料，不是前置条件。 */
        @Test
        @DisplayName("留痕损坏时从空开始，不挡住这次覆盖")
        void 留痕损坏不挡覆盖() {
            ConnectorSemantic e = existingMetric();
            e.setHistoryJson("[{坏掉的");

            service.defineMetric(CONN_ID, "销售额", "新口径", null, "u9", "张三", null);

            assertEquals("新口径", e.getGloss());
            List<?> hist = parseList(e.getHistoryJson());
            assertEquals(1, hist.size());
            assertEquals("订单金额合计", ((Map<?, ?>) hist.get(0)).get("from_gloss"));
        }

        // ──────────────────────────────────────────── 拒绝

        /** 空词条会变成一条谁也查不到的口径行，而模型下次还会再问一遍同一个问题。 */
        @Test
        @DisplayName("空词条被拒且不写库")
        void 空词条被拒() {
            assertThrows(ServiceException.class,
                    () -> service.defineMetric(CONN_ID, "  ", "实付金额合计", null, "u9", "张三", null));
            verify(semanticMapper, never()).insert(any());
            verify(semanticMapper, never()).update(any(), any());
        }

        /** 空说明比没有这条口径更糟：注入层会把一句空话当成已澄清的口径喂给模型。 */
        @Test
        @DisplayName("空说明被拒且不写库")
        void 空说明被拒() {
            assertThrows(ServiceException.class,
                    () -> service.defineMetric(CONN_ID, "销售额", "   ", null, "u9", "张三", null));
            verify(semanticMapper, never()).insert(any());
            verify(semanticMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("连接不存在时不写库")
        void 连接不存在时不写库() {
            when(connectionMapper.selectById(CONN_ID)).thenReturn(null);

            assertThrows(ServiceException.class, () -> service.defineMetric(
                    CONN_ID, "销售额", "实付金额合计", null, "u9", "张三", null));
            verify(semanticMapper, never()).insert(any());
            verify(semanticMapper, never()).update(any(), any());
        }
    }

    // ================================================================================
    // 重新推导
    // ================================================================================

    /**
     * 重推是唯一会<b>删</b>语义行的路径，而它删的那条 SQL 在 {@code runAsSystem} 下
     * 不带任何 tenant_id 条件。这一组测的是那条 DELETE 前后的两道纪律：
     * 先确认归属再删，以及删完插进来的东西一定是 INFERRED。
     */
    @Nested
    @DisplayName("重新推导")
    class ReplaceInferred {

        private ConnectorSemantic fresh(String scope, String objectName, String fieldName) {
            ConnectorSemantic r = new ConnectorSemantic();
            r.setScope(scope);
            r.setObjectName(objectName);
            r.setFieldName(fieldName);
            r.setGloss("推出来的一句话");
            r.setEvidence(EV_COMMENT);
            r.setAnchorKind(ANCHOR_NONE);
            return r;
        }

        private List<ConnectorSemantic> captureInserts(int n) {
            ArgumentCaptor<ConnectorSemantic> cap = ArgumentCaptor.forClass(ConnectorSemantic.class);
            verify(semanticMapper, times(n)).insert(cap.capture());
            return cap.getAllValues();
        }

        /**
         * ★ 归属校验必须在那条裸 DELETE <b>之前</b>。
         *
         * <p>{@code physicalDeleteInferred} 是 {@code DELETE ... WHERE connector_id = ?}，
         * 推导跑在后台线程、通常在 {@code runAsSystem} 里，租户拦截器此时直接放行，
         * 这条语句上不会有任何 tenant_id 条件。校验一旦挪到它后面，传错一个 connectorId
         * 就抹掉别家租户的整个语义层，而且不报错。
         */
        @Test
        @DisplayName("连接不存在时不发那条无租户条件的 DELETE")
        void 连接不存在时不删() {
            when(connectionMapper.selectById(CONN_ID)).thenReturn(null);

            assertThrows(ServiceException.class, () -> service.replaceInferred(
                    CONN_ID, List.of(fresh(SCOPE_OBJECT, "orders", ""))));
            verify(semanticMapper, never()).physicalDeleteInferred(any());
            verify(semanticMapper, never()).insert(any());
        }

        /** 先删后插，且删的是「只删 INFERRED」那一条，不是清空这条连接的语义行。 */
        @Test
        @DisplayName("先删 INFERRED 再整批重插")
        void 先删后插() {
            givenConnectionExists();

            int n = service.replaceInferred(CONN_ID, List.of(
                    fresh(SCOPE_OBJECT, "orders", ""), fresh(SCOPE_FIELD, "orders", "status")));

            assertEquals(2, n);
            InOrder order = inOrder(semanticMapper);
            order.verify(semanticMapper).physicalDeleteInferred(CONN_ID);
            order.verify(semanticMapper, times(2)).insert(any());
        }

        /** 租户从连接行上取。信调用方传进来的 tenantId，一次推导就能把行写进别人的租户里。 */
        @Test
        @DisplayName("tenantId 取自连接行，不信调用方传进来的")
        void 租户取自连接行() {
            givenConnectionExists();
            ConnectorSemantic r = fresh(SCOPE_OBJECT, "orders", "");
            r.setTenantId("别家租户");
            r.setConnectorId(999L);

            service.replaceInferred(CONN_ID, List.of(r));

            ConnectorSemantic saved = captureInserts(1).get(0);
            assertEquals(TENANT, saved.getTenantId());
            assertEquals(CONN_ID, saved.getConnectorId());
        }

        /**
         * ★ source 被强制成 INFERRED，哪怕调用方传的是 HUMAN。
         *
         * <p>推断路径写出一行 HUMAN，效果是双向的灾难：它免疫下一次 {@code physicalDeleteInferred}，
         * 于是这条机器猜的断言会永久留在说明书里、并顶着「人确认过」的措辞被注入；
         * 同时它还会在漂移恢复时回到 CONFIRMED 而不是 DRAFT。
         */
        @Test
        @DisplayName("source 被强制为 INFERRED，推断路径写不出 HUMAN 行")
        void source被强制为INFERRED() {
            givenConnectionExists();
            ConnectorSemantic r = fresh(SCOPE_OBJECT, "orders", "");
            r.setSource(SOURCE_HUMAN);

            service.replaceInferred(CONN_ID, List.of(r));

            assertEquals(SOURCE_INFERRED, captureInserts(1).get(0).getSource());
        }

        /** 带着 id 进来的行会走成 UPDATE 或撞主键；这里必须清成 null 让雪花重新发。 */
        @Test
        @DisplayName("传进来的 id 被清空")
        void id被清空() {
            givenConnectionExists();
            ConnectorSemantic r = fresh(SCOPE_OBJECT, "orders", "");
            r.setId(123456L);

            service.replaceInferred(CONN_ID, List.of(r));

            assertNull(captureInserts(1).get(0).getId());
        }

        /** 推断结果默认是草稿、未验证；已经带了值的不被抹掉。 */
        @Test
        @DisplayName("status / verified 缺省补 DRAFT / NONE，已给的不覆盖")
        void 状态缺省补齐() {
            givenConnectionExists();
            ConnectorSemantic blank = fresh(SCOPE_OBJECT, "orders", "");
            ConnectorSemantic filled = fresh(SCOPE_JOIN, "orders", "user_id");
            filled.setStatus(ST_CONFIRMED);
            filled.setVerified(V_WEAK);

            service.replaceInferred(CONN_ID, List.of(blank, filled));

            List<ConnectorSemantic> saved = captureInserts(2);
            assertEquals(ST_DRAFT, saved.get(0).getStatus());
            assertEquals(V_NONE, saved.get(0).getVerified());
            assertEquals(ST_CONFIRMED, saved.get(1).getStatus());
            assertEquals(V_WEAK, saved.get(1).getVerified());
        }

        /**
         * ★ 「只删 INFERRED」这条纪律的<b>物理保证</b>在那条 SQL 里，Java 侧一点都看不见。
         *
         * <p>纯单测碰不到真库，但这条语句一旦被「顺手简化」掉 source 条件，表现是：
         * 每一次周期性重推都把客户在对话里答出来的口径<b>永久</b>抹掉，
         * 而重推本身是成功的、日志是绿的、审计里没有一条记录——本表也没有软删可以回捞。
         * 所以这里直接把它钉在注解上，这是离线唯一够得着的地方。
         */
        @Test
        @DisplayName("那条物理删除只删 INFERRED，且按 connectorId 收口")
        void 物理删除只删推断行() throws Exception {
            Delete sql = ConnectorSemanticMapper.class
                    .getMethod("physicalDeleteInferred", Long.class).getAnnotation(Delete.class);
            assertNotNull(sql);
            String s = sql.value()[0].replaceAll("\\s+", " ");
            assertTrue(s.contains("source = 'INFERRED'"),
                    "少了这个条件，重推会抹掉人在对话里答出来的口径：" + s);
            assertTrue(s.contains("connector_id = #{connectorId}"),
                    "这条 DELETE 在 runAsSystem 下没有 tenant_id 条件，必须按 connectorId 收口：" + s);
        }

        /** 一条都没推出来时仍然要把旧的 INFERRED 清掉，否则上一轮的错说明会一直留着。 */
        @Test
        @DisplayName("空批次仍然清掉旧的推断结果")
        void 空批次也清旧的() {
            givenConnectionExists();

            assertEquals(0, service.replaceInferred(CONN_ID, List.of()));
            verify(semanticMapper).physicalDeleteInferred(CONN_ID);
            verify(semanticMapper, never()).insert(any());
        }
    }

    // ================================================================================
    // 注入路径的读
    // ================================================================================

    /**
     * 注入是<b>叠加注解，不是一道闸</b>。用一个可选增强的故障去否决一个必要功能是错的：
     * 语义层读不出来时 conn_catalog / conn_describe 必须照常可用，只是少了那份说明书。
     */
    @Nested
    @DisplayName("注入路径的读")
    class Read {

        @Test
        @DisplayName("catalog：OBJECT 按表名索引，METRIC 单独成表")
        void catalog分开两类() {
            ConnectorSemantic obj = objectRow("orders");
            ConnectorSemantic metric = metricRow("销售额", "实付金额合计");
            givenRows(obj, metric);

            ConnectorSemanticService.CatalogSemantics c = service.forCatalog(CONN_ID);

            assertEquals(1, c.getObjects().size());
            assertSame(obj, c.getObjects().get("orders"));
            assertEquals(List.of(metric), c.getGlossary());
        }

        @Test
        @DisplayName("describe：FIELD 按列名索引，JOIN 单独成表")
        void describe分开两类() {
            ConnectorSemantic f = fieldRow("orders", ORD_STATUS);
            ConnectorSemantic j = joinRow("orders", ORD_USER_ID, "users", USR_ID);
            givenRows(f, j);

            ConnectorSemanticService.ObjectSemantics o = service.forObject(CONN_ID, "orders");

            assertEquals(1, o.getFields().size());
            assertSame(f, o.getFields().get("status"));
            assertEquals(List.of(j), o.getJoins());
        }

        /** ★ 读挂了就当没有语义，绝不能让「看目录」整个失败。 */
        @Test
        @DisplayName("catalog：读失败退化成没有语义，不抛")
        void catalog读失败退化为空() {
            when(semanticMapper.selectList(any())).thenThrow(new RuntimeException("连接池满了"));

            ConnectorSemanticService.CatalogSemantics c = service.forCatalog(CONN_ID);

            assertNotNull(c);
            assertTrue(c.getObjects().isEmpty());
            assertTrue(c.getGlossary().isEmpty());
        }

        @Test
        @DisplayName("describe：读失败退化成没有语义，不抛")
        void describe读失败退化为空() {
            when(semanticMapper.selectList(any())).thenThrow(new RuntimeException("连接池满了"));

            ConnectorSemanticService.ObjectSemantics o = service.forObject(CONN_ID, "orders");

            assertNotNull(o);
            assertTrue(o.getFields().isEmpty());
            assertTrue(o.getJoins().isEmpty());
        }
    }

    // ================================================================================
    // 截断
    // ================================================================================

    /**
     * conn_catalog 会把每张表的说明都带上，选表那一刻需要的就是一句话；
     * 不截断的话几十张表的完整说明会把上下文挤爆，而完整版在 conn_describe 里照样拿得到。
     */
    @Nested
    @DisplayName("catalog 说明截断")
    class ShortGloss {

        @Test
        @DisplayName("null 原样返回")
        void null原样返回() {
            assertNull(shortGloss(null));
        }

        @Test
        @DisplayName("恰好到上限不截断、不加省略号")
        void 恰好到上限不截断() {
            String g = "词".repeat(CATALOG_GLOSS_MAX);
            assertEquals(g, shortGloss(g));
        }

        @Test
        @DisplayName("超出一个字就截断并标省略号")
        void 超一个字就截断() {
            String g = "词".repeat(CATALOG_GLOSS_MAX + 1);
            String s = shortGloss(g);
            assertEquals("词".repeat(CATALOG_GLOSS_MAX) + "…", s);
            assertEquals(CATALOG_GLOSS_MAX + 1, s.length());
        }

        /** 先 trim 再量长度：否则模型多打两个空格就会让一句正好合规的说明被截掉尾巴。 */
        @Test
        @DisplayName("先裁空白再量长度")
        void 先裁空白再量长度() {
            String g = "词".repeat(CATALOG_GLOSS_MAX);
            assertEquals(g, shortGloss("  " + g + "  "));
        }
    }
}
