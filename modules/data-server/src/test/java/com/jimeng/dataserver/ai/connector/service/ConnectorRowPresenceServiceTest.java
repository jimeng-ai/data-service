package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 读回空表标记（缺陷 B19）。
 *
 * <p>三件事各自对应一个不报错的失败：
 * <ul>
 *   <li>不做投影 → 每次 {@code conn_catalog} 多读几 MB 的 longtext，
 *       与 {@code ConnectorOverviewService.loadObjects} 那段注释同一条纪律；</li>
 *   <li>不做 LIKE 下推 → 同上；</li>
 *   <li>读失败抛出去 → 一个叠加提醒否决了一次正常的工具调用。</li>
 * </ul>
 */
class ConnectorRowPresenceServiceTest {

    private ConnectorSchemaMapper schemaMapper;
    private ConnectorRowPresenceService service;

    /** LambdaQueryWrapper 的列名解析要用 MP 的实体缓存；纯单测里没有 Spring，得自己灌一遍。 */
    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ConnectorSchema.class);
    }

    @BeforeEach
    void setUp() {
        schemaMapper = mock(ConnectorSchemaMapper.class);
        service = new ConnectorRowPresenceService(schemaMapper);
    }

    private static ConnectorSchema row(String name, Date syncedAt) {
        ConnectorSchema s = new ConnectorSchema();
        s.setObjectName(name);
        s.setSyncedAt(syncedAt);
        return s;
    }

    @Test
    @DisplayName("捞回的空表带着观测时刻")
    void 空表带观测时刻() {
        Date at = new Date(1_700_000_000_000L);
        when(schemaMapper.selectList(any())).thenReturn(List.of(row("D1_COMPANYCODE", at)));

        Map<String, Date> empties = service.emptyObjects(1L);

        assertEquals(1, empties.size());
        assertEquals(at, empties.get("D1_COMPANYCODE"));
    }

    /**
     * ★ 投影 + LIKE 下推。{@code detail_json} 是 longtext，一个 200 张表的库整行读一次就是几 MB 进堆，
     * 而 {@code conn_catalog} 是模型每次探索都会调的工具。
     */
    @Test
    @DisplayName("★ 只投影 object_name / synced_at，过滤下推给 MySQL 做")
    @SuppressWarnings("unchecked")
    void 不整行读longtext() {
        when(schemaMapper.selectList(any())).thenReturn(List.of());

        service.emptyObjects(1L);

        ArgumentCaptor<LambdaQueryWrapper<ConnectorSchema>> wrapper = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(schemaMapper).selectList(wrapper.capture());
        String sql = wrapper.getValue().getSqlSelect();
        assertNotNull(sql, "必须有 select 投影，否则每次都会把 detail_json 整列拉回来");
        assertTrue(sql.contains("object_name"), "实际投影: " + sql);
        assertTrue(sql.contains("synced_at"), "实际投影: " + sql);
        assertTrue(!sql.contains("detail_json"), "detail_json 一个字节都不该被读出来。实际投影: " + sql);

        String segment = wrapper.getValue().getSqlSegment();
        assertTrue(segment.toUpperCase(java.util.Locale.ROOT).contains("LIKE"),
                "过滤要下推给 MySQL，别拉回堆里再筛。实际条件: " + segment);
        assertEquals(RowPresence.EMPTY_JSON_MARKER,
                wrapper.getValue().getParamNameValuePairs().values().stream()
                        .map(String::valueOf)
                        .filter(v -> v.contains(RowPresence.KEY))
                        .map(v -> v.replace("%", ""))
                        .findFirst().orElse(null),
                "LIKE 的模式必须来自 RowPresence 那一处契约");
    }

    /** 少一个提醒，不影响 conn_catalog 出结果。让一个叠加增强去否决一次正常的工具调用是不划算的。 */
    @Test
    @DisplayName("读失败 → 空 map，不抛")
    void 读失败不抛() {
        when(schemaMapper.selectList(any())).thenThrow(new IllegalStateException("db down"));

        assertTrue(service.emptyObjects(1L).isEmpty());
        assertNull(service.emptySince(1L, "D1_COMPANYCODE"));
    }

    @Test
    @DisplayName("connectorId 为空 → 空 map")
    void 没有连接id时不查库() {
        assertTrue(service.emptyObjects(null).isEmpty());
        assertNull(service.emptySince(null, "t"));
        org.mockito.Mockito.verify(schemaMapper, org.mockito.Mockito.never()).selectList(any());
    }

    @Test
    @DisplayName("单表查询：命中给时刻，没命中给 null")
    void 单表三态() {
        Date at = new Date(1_700_000_000_000L);
        when(schemaMapper.selectList(any())).thenReturn(List.of(row("D1_COMPANYCODE", at)));

        assertEquals(at, service.emptySince(1L, "D1_COMPANYCODE"));
        assertNull(service.emptySince(1L, "t_ord"), "不在名单里 = 有行或者没探到，两者都不该说话");
        assertNull(service.emptySince(1L, "  "));
    }

    /** 模型传进来的名字常常跟快照差一个壳；折叠只可能让标记多出现，不会让它落到别的表上。 */
    @Test
    @DisplayName("单表查询对大小写宽容")
    void 单表大小写宽容() {
        Date at = new Date(1_700_000_000_000L);
        when(schemaMapper.selectList(any())).thenReturn(List.of(row("D1_COMPANYCODE", at)));

        assertEquals(at, service.emptySince(1L, "d1_companycode"));
    }

    /** 时刻缺失时宁可没有这一栏，也不要写一个编出来的时间。 */
    @Test
    @DisplayName("观测时刻为 null 时不编一个时间出来")
    void 时刻为空时给null() {
        assertNull(ConnectorRowPresenceService.formatObservedAt(null));
        assertNotNull(ConnectorRowPresenceService.formatObservedAt(new Date()));
    }
}
