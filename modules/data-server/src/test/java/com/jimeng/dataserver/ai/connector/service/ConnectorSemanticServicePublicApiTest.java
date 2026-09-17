package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 语义层 agent 写入器需要的窄公共 API（设计 7.11、8.5）。 */
class ConnectorSemanticServicePublicApiTest {

    private static final Long CONNECTOR_ID = 41L;
    private ConnectorSemanticMapper semanticMapper;
    private ConnectionMapper connectionMapper;
    private ConnectorSemanticService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ConnectorSemantic.class);
        semanticMapper = mock(ConnectorSemanticMapper.class);
        connectionMapper = mock(ConnectionMapper.class);
        service = new ConnectorSemanticService(semanticMapper, connectionMapper);
        Connection connection = new Connection();
        connection.setId(CONNECTOR_ID);
        connection.setTenantId("tenant-a");
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(connection);
    }

    @Test
    @DisplayName("requireOwned 与 uniqueKey 是跨包写入器可调用的 public API")
    void 窄公共API可见() throws Exception {
        assertTrue(Modifier.isPublic(ConnectorSemanticService.class
                .getMethod("requireOwned", Long.class).getModifiers()));
        assertTrue(Modifier.isPublic(ConnectorSemanticService.class
                .getMethod("uniqueKey", ConnectorSemantic.class).getModifiers()));
        assertEquals("field\u0001t_ord\u0001amt\u0001",
                ConnectorSemanticService.uniqueKey(row("FIELD", "T_ORD", "Amt", "", null)));
        assertEquals("tenant-a", service.requireOwned(CONNECTOR_ID).getTenantId());
    }

    @Test
    @DisplayName("不存在同词 CAVEAT 时写入 INFERRED 行，并在撞唯一键时只返回 CONFLICT")
    void caveat插入与冲突() {
        ConnectorSemantic fresh = row("CAVEAT", "", "", "销售额", "{\"applies_to\":[\"t_ord\"]}");

        assertEquals(ConnectorSemanticService.CaveatMergeResult.INSERTED,
                service.mergeInferredCaveat(CONNECTOR_ID, fresh));
        assertEquals("tenant-a", fresh.getTenantId());
        assertEquals(CONNECTOR_ID, fresh.getConnectorId());
        assertEquals(ConnectorSemanticService.SOURCE_INFERRED, fresh.getSource());
        assertEquals(ConnectorSemanticService.ST_DRAFT, fresh.getStatus());
        assertEquals(ConnectorSemanticService.V_NONE, fresh.getVerified());

        org.mockito.Mockito.reset(semanticMapper);
        when(semanticMapper.selectOne(any())).thenReturn(null);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("race")).when(semanticMapper).insert(any());
        assertEquals(ConnectorSemanticService.CaveatMergeResult.CONFLICT,
                service.mergeInferredCaveat(CONNECTOR_ID,
                        row("CAVEAT", "", "", "退款", "{\"applies_to\":[\"t_refund\"]}")));
    }

    @Test
    @DisplayName("已有人工行时不覆盖；已有 INFERRED 时只合并 applies_to、保留先到 gloss 并限制十张表")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void caveat人工冲突与合并() throws Exception {
        ConnectorSemantic human = row("CAVEAT", "", "", "销售额", "{\"applies_to\":[\"t_old\"]}");
        human.setId(1L);
        human.setSource(ConnectorSemanticService.SOURCE_HUMAN);
        when(semanticMapper.selectOne(any())).thenReturn(human);
        assertEquals(ConnectorSemanticService.CaveatMergeResult.SKIPPED_NOT_INFERRED,
                service.mergeInferredCaveat(CONNECTOR_ID,
                        row("CAVEAT", "", "", "销售额", "{\"applies_to\":[\"t_new\"]}")));
        verify(semanticMapper, never()).update(any(), any());

        org.mockito.Mockito.reset(semanticMapper);
        ConnectorSemantic old = row("CAVEAT", "", "", "销售额",
                "{\"applies_to\":[\"t0\",\"T1\",\"t2\",\"t3\",\"t4\",\"t5\",\"t6\",\"t7\",\"t8\"]}");
        old.setId(2L);
        old.setSource(ConnectorSemanticService.SOURCE_INFERRED);
        old.setGloss("先到的问题");
        when(semanticMapper.selectOne(any())).thenReturn(old);
        when(semanticMapper.update(isNull(), any())).thenReturn(1);

        ConnectorSemantic fresh = row("CAVEAT", "", "", "销售额",
                "{\"applies_to\":[\"t1\",\"t9\",\"t10\"]}");
        fresh.setGloss("后来问题不能覆盖");
        assertEquals(ConnectorSemanticService.CaveatMergeResult.MERGED,
                service.mergeInferredCaveat(CONNECTOR_ID, fresh));

        ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemantic>> captor = ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(semanticMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<ConnectorSemantic> wrapper = captor.getValue();
        assertFalse(wrapper.getSqlSet().contains("gloss"), "先到 gloss 不应被覆盖");
        String json = wrapper.getParamNameValuePairs().values().stream()
                .filter(String.class::isInstance).map(String.class::cast)
                .filter(v -> v.contains("applies_to")).findFirst().orElseThrow();
        Map<String, Object> detail = CommonUtil.getObjectMapper().readValue(json, Map.class);
        assertEquals(List.of("t0", "T1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9"),
                detail.get("applies_to"));
    }

    @Test
    @DisplayName("INFERRED 合并的乐观更新影响 0 行返回 CONFLICT")
    void caveat并发冲突() {
        ConnectorSemantic old = row("CAVEAT", "", "", "销售额", "{\"applies_to\":[\"t_old\"]}");
        old.setId(2L);
        old.setSource(ConnectorSemanticService.SOURCE_INFERRED);
        when(semanticMapper.selectOne(any())).thenReturn(old);
        when(semanticMapper.update(isNull(), any())).thenReturn(0);

        assertEquals(ConnectorSemanticService.CaveatMergeResult.CONFLICT,
                service.mergeInferredCaveat(CONNECTOR_ID,
                        row("CAVEAT", "", "", "销售额", "{\"applies_to\":[\"t_new\"]}")));
    }

    private static ConnectorSemantic row(String scope, String object, String field, String term, String detail) {
        ConnectorSemantic row = new ConnectorSemantic();
        row.setScope(scope);
        row.setObjectName(object);
        row.setFieldName(field);
        row.setTerm(term);
        row.setGloss("问题");
        row.setDetailJson(detail);
        row.setEvidence(ConnectorSemanticService.EV_NAME);
        row.setAnchorKind(ConnectorSemanticService.ANCHOR_NONE);
        return row;
    }
}
