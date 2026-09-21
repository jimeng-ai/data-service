package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.model.CatalogEntry;
import com.jimeng.dataserver.ai.connector.model.CatalogView;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorGateway;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.cap.DescribeCapable;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结构刷新时探一次「这张表有没有行」，结论盖进 {@code detail_json}（缺陷 B19）。
 *
 * <p>要挡住的事故：POC 环境里 {@code D1_COMPANYCODE} 是 0 行，模型 join 到它拿回空集，
 * 把「没有数据」当成业务答案报给用户——查询成功、没有报错、数字静默地错。
 *
 * <p>本组最要紧的一条是 {@link #探测失败不等于空表()}：<b>「没探到」被写成「是空的」，
 * 比不写更糟</b>——那会让模型凭空告诉用户「这张表里一行数据都没有」。
 */
class ConnectorSchemaRowPresenceTest {

    private ConnectionMapper connectionMapper;
    private ConnectorSchemaMapper schemaMapper;
    private ConnectorGateway gateway;
    private ConnectorSchemaService service;
    private Connection row;

    /** 会话桩：表名 → 探测结论（不放 = 探不到）。 */
    private final Map<String, Boolean> presence = new HashMap<>();
    /** 探测被调用时收到的名字与预算。 */
    private List<String> probedNames;
    private Long probedBudget;
    /** 探测这一步要不要抛异常。 */
    private RuntimeException probeFailure;
    /** 探测返回 null（连接器答不了）。 */
    private boolean probeUnsupported;

    @BeforeEach
    void setUp() {
        connectionMapper = mock(ConnectionMapper.class);
        schemaMapper = mock(ConnectorSchemaMapper.class);
        gateway = mock(ConnectorGateway.class);

        row = new Connection();
        row.setId(100L);
        row.setTenantId("t1");
        row.setName("erp");
        row.setKind("MYSQL");
        row.setStatus("ACTIVE");
        row.setTransport("direct");
        when(connectionMapper.selectById(100L)).thenReturn(row);

        Connector connector = mock(Connector.class);
        when(connector.declaredCapabilities()).thenReturn(Set.of(Capability.QUERY, Capability.DESCRIBE));
        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        when(registry.require("MYSQL")).thenReturn(connector);
        service = new ConnectorSchemaService(connectionMapper, schemaMapper, registry,
                mock(ConnectorSemanticService.class), mock(PlatformTransactionManager.class), gateway,
                mock(ObjectProvider.class), mock(org.redisson.api.RedissonClient.class));
        // @PostConstruct 在单测里不会被调用，txTemplate 得自己初始化。
        service.initTx();

        TenantContext.set("t1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 两张表：{@code D1_COMPANYCODE}（POC 里的空表）和 {@code t_ord}。 */
    @SuppressWarnings("unchecked")
    private void givenCatalog(String... names) {
        FakeDescribe session = new FakeDescribe(List.of(names));
        when(gateway.executeAsPlatform(eq(100L), eq(Capability.DESCRIBE), anyString(), any()))
                .thenAnswer(inv -> ((ConnectorGateway.Op<Object>) inv.getArgument(3)).apply(session));
    }

    private Map<String, String> refreshAndReadMarkers(int expectedRows) {
        service.refresh(100L);
        ArgumentCaptor<ConnectorSchema> inserted = ArgumentCaptor.forClass(ConnectorSchema.class);
        verify(schemaMapper, times(expectedRows)).insert(inserted.capture());
        return markers(inserted.getAllValues());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> markers(List<ConnectorSchema> rows) {
        Map<String, String> out = new HashMap<>();
        for (ConnectorSchema r : rows) {
            try {
                Map<String, Object> m = CommonUtil.getObjectMapper().readValue(r.getDetailJson(), Map.class);
                Object v = m.get(RowPresence.KEY);
                if (v != null) {
                    out.put(r.getObjectName(), String.valueOf(v));
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    // ================================================================ 正常两态

    @Test
    @DisplayName("探到空表 → detail_json 里盖 EMPTY；探到有行 → NON_EMPTY")
    void 两态各自落库() {
        givenCatalog("D1_COMPANYCODE", "t_ord");
        presence.put("D1_COMPANYCODE", false);
        presence.put("t_ord", true);

        Map<String, String> m = refreshAndReadMarkers(2);

        assertEquals(RowPresence.EMPTY, m.get("D1_COMPANYCODE"));
        assertEquals(RowPresence.NON_EMPTY, m.get("t_ord"));
    }

    // ================================================================ ★ 探测失败 ≠ 空表

    /**
     * ★ 本组的核心。探测这一步整个炸了（驱动抛未受检异常、连接池挂了），
     * 每一张表都必须落成「键不在」——也就是「不知道」。
     *
     * <p>写成 {@code EMPTY} 的后果不是少一个提醒，是<b>多一句谎话</b>：
     * 模型会据此告诉用户「这张表里一行数据都没有」，而那张表可能有一千万行。
     */
    @Test
    @DisplayName("★ 探测整批失败 → 一个标记都不盖（不知道），绝不盖成空表")
    void 探测失败不等于空表() {
        givenCatalog("D1_COMPANYCODE", "t_ord");
        probeFailure = new IllegalStateException("驱动炸了");

        Map<String, String> m = refreshAndReadMarkers(2);

        assertTrue(m.isEmpty(), "没探到就什么都不说。实际盖了: " + m);
    }

    /** 连接器压根答不了（HTTP 连接器、老实现）：同样是「不知道」，不是「全都空」。 */
    @Test
    @DisplayName("★ 连接器答不了（返回 null）→ 一个标记都不盖")
    void 连接器答不了时不盖标记() {
        givenCatalog("D1_COMPANYCODE", "t_ord");
        probeUnsupported = true;

        assertTrue(refreshAndReadMarkers(2).isEmpty());
    }

    /** 整批里少了某一张的答案（那一张超时 / 没权限 / 预算用完）：只有它是「不知道」。 */
    @Test
    @DisplayName("★ 单张没探到 → 只有它没标记，其余照常")
    void 单张没探到只影响自己() {
        givenCatalog("D1_COMPANYCODE", "no_grant", "t_ord");
        presence.put("D1_COMPANYCODE", false);
        presence.put("t_ord", true);
        // no_grant 不放进 presence = 连接器没给出答案

        Map<String, String> m = refreshAndReadMarkers(3);

        assertEquals(RowPresence.EMPTY, m.get("D1_COMPANYCODE"));
        assertEquals(RowPresence.NON_EMPTY, m.get("t_ord"));
        assertFalse(m.containsKey("no_grant"), "没拿到答案的那一张必须保持沉默");
    }

    // ================================================================ 探什么、探多久

    /**
     * 描述失败的表在快照里只有一条名字行。对它们发探测语句几乎必然同样失败，
     * 只是把预算烧在注定拿不到答案的地方；而一张连结构都没取到的表，
     * 本来就不该有任何「它是空的」的结论。
     */
    @Test
    @DisplayName("describe 失败的对象不参与探测")
    void 描述失败的对象不探() {
        givenCatalog("t_ord", "no_grant");

        service.refresh(100L);

        assertEquals(List.of("t_ord"), probedNames);
    }

    /** 上限写在明处：一次刷新的最坏增量是这个预算，不随表数增长。 */
    @Test
    @DisplayName("★ 探测带着整批挂钟预算下去，不是「表数 × 超时」")
    void 探测带预算() {
        givenCatalog("t_ord");
        presence.put("t_ord", true);

        service.refresh(100L);

        assertEquals(ConnectorSchemaService.ROW_PRESENCE_BUDGET_MILLIS, probedBudget);
    }

    // ================================================================ 出库档位

    /**
     * ★ 「有没有行」只是一个布尔，不是真实取值，所以与第 3 档（样本值）无关。
     * 但它<b>确实是从数据算出来的</b>：第 1 档对客户的承诺原话是「不做任何聚合，
     * 没有一条业务记录参与运算」，而这条语句要去碰行。所以按第 2 档（派生统计）处理。
     */
    @Test
    @DisplayName("★ 第 1 档（纯元数据）整批不探——每张表都是「不知道」")
    void 第一档不探() {
        row.setSemanticDataTier(SemanticDataTier.METADATA_ONLY.name());
        givenCatalog("D1_COMPANYCODE");

        Map<String, String> m = refreshAndReadMarkers(1);

        assertTrue(m.isEmpty(), "第 1 档不探，结果是「不知道」，既不是「都空」也不是「都不空」");
        assertEquals(null, probedNames, "第 1 档连探测都不该被调用");
    }

    @Test
    @DisplayName("第 2 档（默认）照常探")
    void 第二档照常探() {
        row.setSemanticDataTier(SemanticDataTier.DERIVED_STATS.name());
        givenCatalog("D1_COMPANYCODE");
        presence.put("D1_COMPANYCODE", false);

        assertEquals(RowPresence.EMPTY, refreshAndReadMarkers(1).get("D1_COMPANYCODE"));
    }

    /** 档位这一格是 NULL（加列之前建的连接）= 没选过，落到默认档，照常探。 */
    @Test
    @DisplayName("档位为空按默认档处理，照常探")
    void 档位为空按默认档() {
        row.setSemanticDataTier(null);
        givenCatalog("D1_COMPANYCODE");
        presence.put("D1_COMPANYCODE", false);

        assertEquals(RowPresence.EMPTY, refreshAndReadMarkers(1).get("D1_COMPANYCODE"));
    }

    // ================================================================ 不能碰坏的两样东西

    /**
     * ★ 一张表从空变成非空<b>不是结构漂移</b>。算进 {@code content_hash}，
     * 客户往空表里插第一行就会让挂在它上面的说明书整批被标 STALE，
     * 管理台叫业务方去重答一张好好存在的表上的口径。
     */
    @Test
    @DisplayName("★ 空/非空不进 content_hash：表被灌进数据不会报成结构漂移")
    void 空表标记不进内容哈希() {
        givenCatalog("D1_COMPANYCODE");
        presence.put("D1_COMPANYCODE", false);
        service.refresh(100L);
        ArgumentCaptor<ConnectorSchema> first = ArgumentCaptor.forClass(ConnectorSchema.class);
        verify(schemaMapper).insert(first.capture());
        String hashWhenEmpty = first.getValue().getContentHash();

        setUp();
        givenCatalog("D1_COMPANYCODE");
        presence.put("D1_COMPANYCODE", true);
        service.refresh(100L);
        ArgumentCaptor<ConnectorSchema> second = ArgumentCaptor.forClass(ConnectorSchema.class);
        verify(schemaMapper).insert(second.capture());

        assertEquals(hashWhenEmpty, second.getValue().getContentHash());
    }

    /**
     * 重要性排名是<b>目录位置</b>。改动引入了「先描述、后探测、再拼行」的两段式，
     * 排名必须还是那个位置——描述失败的对象同样占位，跳过它会让后面每一张表错一位。
     */
    @Test
    @DisplayName("排名仍等于目录位置，描述失败的对象照常占位")
    void 排名不受重构影响() {
        givenCatalog("t_ord", "no_grant", "a_dict");

        service.refresh(100L);

        ArgumentCaptor<ConnectorSchema> inserted = ArgumentCaptor.forClass(ConnectorSchema.class);
        verify(schemaMapper, times(3)).insert(inserted.capture());
        assertEquals(List.of("t_ord", "no_grant", "a_dict"),
                inserted.getAllValues().stream().map(ConnectorSchema::getObjectName).toList());
        assertEquals(List.of(1, 2, 3),
                inserted.getAllValues().stream().map(ConnectorSchema::getImportanceRank).toList());
    }

    /** 探测炸了也不能把一次成功的结构拉取拖下水——快照是事实，探测是附注。 */
    @Test
    @DisplayName("探测失败不影响结构快照本身落库")
    void 探测失败不影响快照落库() {
        givenCatalog("t_ord");
        probeFailure = new IllegalStateException("驱动炸了");

        ConnectorSchemaService.SnapshotResult r = service.refresh(100L);

        assertEquals(1, r.getObjectCount(), "快照是事实，探测是附注；附注失败不能把事实弄丢");
        verify(schemaMapper, times(1)).insert(any(ConnectorSchema.class));
    }

    // ================================================================

    /** 名字里带 {@code no_grant} 的表 describe 会失败，其余正常返回一列。 */
    private final class FakeDescribe implements ConnectorSession, DescribeCapable {

        private final List<String> names;

        FakeDescribe(List<String> names) {
            this.names = names;
        }

        @Override
        public CatalogView catalog() {
            return new CatalogView("TABLE",
                    names.stream().map(n -> new CatalogEntry(n, "BASE TABLE", null)).toList(),
                    false, names.size(), "按估算行数的数量级降序");
        }

        @Override
        public ObjectDetail describe(String object) {
            if (object.contains("no_grant")) {
                throw ConnectorException.of(ConnectorErrorCode.FORBIDDEN, "没有这张表的权限");
            }
            return new ObjectDetail(object, "BASE TABLE", null,
                    List.of(new FieldDetail("id", "bigint", false, null, null)), Map.of());
        }

        @Override
        public Map<String, Boolean> probeRowPresence(java.util.Collection<String> asked, long budgetMillis) {
            probedNames = List.copyOf(asked);
            probedBudget = budgetMillis;
            if (probeFailure != null) {
                throw probeFailure;
            }
            if (probeUnsupported) {
                return null;
            }
            Map<String, Boolean> out = new HashMap<>();
            for (String n : asked) {
                if (presence.containsKey(n)) {
                    out.put(n, presence.get(n));
                }
            }
            return out;
        }

        @Override
        public void ping() {
        }

        @Override
        public Set<Capability> probeCapabilities() {
            return Set.of(Capability.DESCRIBE);
        }

        @Override
        public com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict verifyReadOnly() {
            return null;
        }

        @Override
        public void close() {
        }
    }
}
