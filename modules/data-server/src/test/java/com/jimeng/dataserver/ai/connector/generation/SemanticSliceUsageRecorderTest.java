package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.billing.usage.NormalizedUsage;
import com.jimeng.dataserver.ai.model.ModelRegistry;
import com.jimeng.persistence.entity.AiModel;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticSliceUsageRecorderTest {

    private ConnectorSemanticGenerationMapper generationMapper;
    private AiModelCallRecordService callRecordService;
    private ModelRegistry modelRegistry;
    private SemanticSliceUsageRecorder recorder;
    private ConnectorSemanticGeneration generation;

    @BeforeAll
    static void initLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
                ConnectorSemanticGeneration.class);
    }

    @BeforeEach
    void setUp() {
        generationMapper = mock(ConnectorSemanticGenerationMapper.class);
        callRecordService = mock(AiModelCallRecordService.class);
        modelRegistry = mock(ModelRegistry.class);
        recorder = new SemanticSliceUsageRecorder(generationMapper, callRecordService, modelRegistry);
        generation = generation();
        when(generationMapper.selectById(10L)).thenReturn(generation);
        when(generationMapper.update(any(), any())).thenReturn(1);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("四类 token 累加、实际模型去重并按 ai_model provider 记账")
    @SuppressWarnings("unchecked")
    void usage累加并按实际模型记账() {
        generation.setModelsSeen("older-model");
        AiModel model = pricedModel("deepseek", "deepseek-flash");
        when(modelRegistry.priceOf("deepseek-flash")).thenReturn(model);
        NormalizedUsage usage = usage(11, 7, 3, 2);
        SliceRunResult result = result(usage, Set.of("deepseek-flash"), 200);
        TenantContext.set("caller-tenant");

        assertTrue(recorder.record(generation, 2, "semgen-10-s2-a1",
                "deepseek-flash", result, 1234));

        assertEquals("caller-tenant", TenantContext.get());
        ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemanticGeneration>> update =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(generationMapper).update(eq(null), update.capture());
        String sqlSet = update.getValue().getSqlSet();
        assertTrue(sqlSet.contains("input_tokens"), sqlSet);
        assertTrue(sqlSet.contains("output_tokens"), sqlSet);
        assertTrue(sqlSet.contains("cache_read_tokens"), sqlSet);
        assertTrue(sqlSet.contains("cache_write_tokens"), sqlSet);
        assertTrue(update.getValue().getParamNameValuePairs().containsValue("older-model,deepseek-flash"),
                update.getValue().getParamNameValuePairs().toString());

        ArgumentCaptor<Map<String, Object>> note = ArgumentCaptor.forClass(Map.class);
        verify(callRecordService).recordComputedCall(eq("deepseek"), eq("sandbox:semantic-agent"),
                eq("deepseek-flash"), eq("semantic_agent"), eq(usage), eq(200), eq(1234), note.capture());
        assertEquals("10", note.getValue().get("generation_id"));
        assertEquals(2, note.getValue().get("slice_no"));
        assertEquals("semgen-10-s2-a1", note.getValue().get("run_id"));
        assertEquals(java.util.List.of("deepseek-flash"), note.getValue().get("models_seen"));
        assertFalse(note.getValue().toString().contains("token"), note.getValue().toString());
    }

    @Test
    @DisplayName("没有 summary usage 的已启动片只累加 slices_without_usage")
    void 缺usage累计片数() {
        SliceRunResult result = result(null, Set.of(), null);

        assertTrue(recorder.record(generation, 1, "semgen-10-s1-a1",
                "deepseek-flash", result, 20));

        ArgumentCaptor<LambdaUpdateWrapper<ConnectorSemanticGeneration>> update =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(generationMapper).update(eq(null), update.capture());
        assertTrue(update.getValue().getSqlSet().contains("slices_without_usage"),
                update.getValue().getSqlSet());
        verify(callRecordService, never()).recordComputedCall(any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("owner/status CAS 丢失时不写计费记录")
    void cas丢失不记账() {
        when(generationMapper.update(any(), any())).thenReturn(0);
        NormalizedUsage usage = usage(1, 2, 0, 0);

        assertFalse(recorder.record(generation, 1, "semgen-10-s1-a1",
                "deepseek-flash", result(usage, Set.of(), 200), 5));

        verify(callRecordService, never()).recordComputedCall(any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("实际模型查不到时 provider 为 unknown，零单价可被检测")
    void provider解析与零单价() {
        NormalizedUsage usage = usage(1, 1, 0, 0);
        when(modelRegistry.priceOf("deepseek-flash")).thenReturn(null);

        assertTrue(recorder.record(generation, 1, "semgen-10-s1-a1",
                "deepseek-flash", result(usage, Set.of(), 200), 5));

        verify(callRecordService).recordComputedCall(eq("unknown"), eq("sandbox:semantic-agent"),
                eq("deepseek-flash"), eq("semantic_agent"), eq(usage), eq(200), eq(5), any());
        AiModel zero = pricedModel("deepseek", "deepseek-flash");
        zero.setPriceOutput(BigDecimal.ZERO);
        assertTrue(SemanticSliceUsageRecorder.hasZeroPrice(zero));
        assertTrue(SemanticSliceUsageRecorder.hasZeroPrice(null));
    }

    @Test
    @DisplayName("计费明细写失败不回滚已累计用量，也不要求重派模型")
    void billing失败不重派() {
        NormalizedUsage usage = usage(1, 2, 0, 0);
        when(modelRegistry.priceOf("deepseek-flash"))
                .thenReturn(pricedModel("deepseek", "deepseek-flash"));
        doThrow(new IllegalStateException("db secret"))
                .when(callRecordService).recordComputedCall(any(), any(), any(), any(), any(), any(), any(), any());

        assertTrue(recorder.record(generation, 1, "semgen-10-s1-a1",
                "deepseek-flash", result(usage, Set.of(), 200), 5));

        verify(generationMapper).update(eq(null), any());
    }

    private static ConnectorSemanticGeneration generation() {
        ConnectorSemanticGeneration generation = new ConnectorSemanticGeneration();
        generation.setId(10L);
        generation.setTenantId("tenant-a");
        generation.setOwnerToken("owner-a");
        generation.setStatus("RUNNING");
        return generation;
    }

    private static AiModel pricedModel(String provider, String value) {
        AiModel model = new AiModel();
        model.setProvider(provider);
        model.setValue(value);
        model.setPriceInput(BigDecimal.ONE);
        model.setPriceOutput(BigDecimal.ONE);
        model.setPriceCacheRead(BigDecimal.ONE);
        model.setPriceCacheWrite(BigDecimal.ONE);
        return model;
    }

    private static NormalizedUsage usage(int input, int output, int cacheRead, int cacheWrite) {
        NormalizedUsage usage = new NormalizedUsage();
        usage.setInputTokens(input);
        usage.setOutputTokens(output);
        usage.setTotalTokens(input + output);
        usage.setCacheReadTokens(cacheRead);
        usage.setCacheWriteTokens(cacheWrite);
        return usage;
    }

    private static SliceRunResult result(NormalizedUsage usage, Set<String> models, Integer httpStatus) {
        return new SliceRunResult(SliceRunKind.COMPLETED, true, false, httpStatus, null,
                "success", null, usage, 1, models);
    }
}
