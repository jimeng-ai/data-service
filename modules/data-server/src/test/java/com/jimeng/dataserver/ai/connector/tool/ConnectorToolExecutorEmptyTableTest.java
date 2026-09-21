package com.jimeng.dataserver.ai.connector.tool;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.dataserver.ai.connector.model.CatalogEntry;
import com.jimeng.dataserver.ai.connector.model.CatalogView;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorGateway;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.service.ConnectorRowPresenceService;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.LinkedHashMap;
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
import static org.mockito.Mockito.when;

/**
 * 空表标记怎么出现在 {@code conn_catalog} / {@code conn_describe} 的返回里（缺陷 B19）。
 *
 * <h3>它要扭转的那个事故</h3>
 * POC 环境里 {@code D1_COMPANYCODE} 是 0 行。清账按公司代码过滤 / 分组，join 到它就<b>安静地返回空集</b>：
 * 查询成功、没有报错，模型把「没有数据」当成业务答案报给用户。结构一个字都没错，
 * 错的是模型不知道这张表是空的。所以标记必须出现在它<b>选表和写 SQL 的那一刻</b>。
 *
 * <h3>另一半同样重要：没探到就一个字都不说</h3>
 * {@code "empty_table": false} 读起来是「平台确认这张表有数据」——那是我们从没说过的话。
 * 探测失败、第 1 档不探、表不在快照里，三种都只是「不知道」。
 */
class ConnectorToolExecutorEmptyTableTest {

    private static final Date OBSERVED = new Date(1_700_000_000_000L);

    private ConnectorRowPresenceService rowPresence;
    private ConnectorToolExecutor executor;

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
        when(gateway.execute(anyString(), any(Capability.class), anyString(), any()))
                .thenAnswer(inv -> ((ConnectorGateway.Op<Object>) inv.getArgument(3)).apply(new FakeSession()));

        ConnectionMapper connectionMapper = mock(ConnectionMapper.class);
        Connection conn = new Connection();
        conn.setId(1L);
        conn.setName("erp");
        when(connectionMapper.selectOne(any())).thenReturn(conn);

        ConnectorSemanticService semanticService = mock(ConnectorSemanticService.class);
        // 语义层在这一组里一律留空：要钉的是空表标记本身，不是它和口径怎么并排。
        when(semanticService.forCatalog(any())).thenReturn(
                ConnectorSemanticService.CatalogSemantics.builder().objects(Map.of()).glossary(List.of()).build());
        when(semanticService.forObject(any(), anyString())).thenReturn(
                ConnectorSemanticService.ObjectSemantics.builder().fields(Map.of()).joins(List.of()).build());

        rowPresence = mock(ConnectorRowPresenceService.class);
        executor = new ConnectorToolExecutor(gateway, new ConnectorProperties(), semanticService, connectionMapper,
                mock(ConnectorSemanticMapper.class),
                mock(com.jimeng.dataserver.admin.common.UserNameResolver.class), rowPresence,
                mock(com.jimeng.dataserver.ai.connector.service.ConnectorSnapshotColumnService.class));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> catalog() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("connector", "erp");
        return (Map<String, Object>) executor.execute(ConnectorToolExecutor.TOOL_CATALOG, args);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> describe(String object) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("connector", "erp");
        args.put("object", object);
        return (Map<String, Object>) executor.execute(ConnectorToolExecutor.TOOL_DESCRIBE, args);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entry(Map<String, Object> catalog, String name) {
        for (Map<String, Object> m : (List<Map<String, Object>>) catalog.get("entries")) {
            if (name.equals(m.get("name"))) {
                return m;
            }
        }
        throw new AssertionError("目录里没有 " + name);
    }

    // ================================================================ conn_catalog

    @Test
    @DisplayName("★ 空表在目录里就被标出来——选表那一刻，不是拿到 0 行之后")
    void 目录里标出空表() {
        when(rowPresence.emptyObjects(1L)).thenReturn(Map.of("D1_COMPANYCODE", OBSERVED));

        Map<String, Object> out = catalog();

        Map<String, Object> empty = entry(out, "D1_COMPANYCODE");
        assertEquals(true, empty.get(ConnectorToolExecutor.KEY_EMPTY_TABLE));
        assertTrue(String.valueOf(empty.get(ConnectorToolExecutor.KEY_EMPTY_TABLE_AT)).startsWith("20"),
                "标记必须带观测时刻，否则会被当成永恒真理。实际: " + empty);
        assertTrue(out.containsKey(ConnectorToolExecutor.KEY_EMPTY_TABLE_NOTE), "得有一句话说清这个标记怎么用");
    }

    /** ★ {@code "empty_table": false} 读起来是「平台确认它有数据」——那是我们没说过的话。 */
    @Test
    @DisplayName("★ 没探到的表一个 key 都不多出来")
    void 没探到的表保持沉默() {
        when(rowPresence.emptyObjects(1L)).thenReturn(Map.of("D1_COMPANYCODE", OBSERVED));

        Map<String, Object> out = catalog();

        Map<String, Object> other = entry(out, "t_ord");
        assertFalse(other.containsKey(ConnectorToolExecutor.KEY_EMPTY_TABLE),
                "没有标记 ≠ 有数据，所以这里连 false 都不能写");
        assertFalse(other.containsKey(ConnectorToolExecutor.KEY_EMPTY_TABLE_AT));
    }

    @Test
    @DisplayName("一张空表都没有时，整份目录一个字都不多")
    void 没有空表时目录不变() {
        when(rowPresence.emptyObjects(1L)).thenReturn(Map.of());

        Map<String, Object> out = catalog();

        assertFalse(out.containsKey(ConnectorToolExecutor.KEY_EMPTY_TABLE_NOTE));
        assertFalse(entry(out, "D1_COMPANYCODE").containsKey(ConnectorToolExecutor.KEY_EMPTY_TABLE));
    }

    // ================================================================ conn_describe

    @Test
    @DisplayName("★ conn_describe 顶层标出空表，并带上那句话")
    void describe顶层标出空表() {
        when(rowPresence.emptySince(1L, "D1_COMPANYCODE")).thenReturn(OBSERVED);

        Map<String, Object> out = describe("D1_COMPANYCODE");

        assertEquals(true, out.get(ConnectorToolExecutor.KEY_EMPTY_TABLE));
        assertTrue(String.valueOf(out.get(ConnectorToolExecutor.KEY_EMPTY_TABLE_AT)).startsWith("20"));
        assertTrue(out.containsKey(ConnectorToolExecutor.KEY_EMPTY_TABLE_NOTE));
    }

    @Test
    @DisplayName("没探到时 conn_describe 一个 key 都不多出来")
    void describe没探到时不多key() {
        when(rowPresence.emptySince(any(), anyString())).thenReturn(null);

        Map<String, Object> out = describe("t_ord");

        assertFalse(out.containsKey(ConnectorToolExecutor.KEY_EMPTY_TABLE));
        assertFalse(out.containsKey(ConnectorToolExecutor.KEY_EMPTY_TABLE_AT));
        assertFalse(out.containsKey(ConnectorToolExecutor.KEY_EMPTY_TABLE_NOTE));
    }

    // ================================================================ 那句话本身

    /**
     * ★ 措辞里三件事一件都不能少，少哪一件对应哪一种错，见
     * {@code ConnectorToolExecutor.EMPTY_TABLE_NOTE} 的注释。
     */
    @Test
    @DisplayName("★ 提示语要说清：是事实、带时刻、而且「没标记 ≠ 有数据」")
    void 提示语三件事都在() {
        when(rowPresence.emptyObjects(1L)).thenReturn(Map.of("D1_COMPANYCODE", OBSERVED));

        String note = String.valueOf(catalog().get(ConnectorToolExecutor.KEY_EMPTY_TABLE_NOTE));

        assertTrue(note.contains("一行数据都没有"), "要让模型能直接把这句话说给用户。实际: " + note);
        assertTrue(note.contains("不是"), "必须把「空表」和「业务答案」分开。实际: " + note);
        assertTrue(note.contains(ConnectorToolExecutor.KEY_EMPTY_TABLE_AT), "要指向观测时刻这一栏。实际: " + note);
        assertTrue(note.contains("没有这个标记不等于表里有数据"),
                "缺了这一句，模型会把「没标记」读成「平台确认有数据」。实际: " + note);
    }

    // ================================================================

    private static final class FakeSession implements ConnectorSession, DescribeCapable {

        @Override
        public CatalogView catalog() {
            return new CatalogView("TABLE", List.of(
                    new CatalogEntry("D1_COMPANYCODE", "TABLE", "公司代码"),
                    new CatalogEntry("t_ord", "TABLE", "订单表")),
                    false, 2, null);
        }

        @Override
        public ObjectDetail describe(String object) {
            return new ObjectDetail(object, "TABLE", null,
                    List.of(new FieldDetail("code", "varchar(16)", false, null, null)), Map.of());
        }

        @Override
        public void ping() {
        }

        @Override
        public Set<Capability> probeCapabilities() {
            return Set.of(Capability.DESCRIBE);
        }

        @Override
        public ReadOnlyVerdict verifyReadOnly() {
            return null;
        }

        @Override
        public void close() {
        }
    }
}
