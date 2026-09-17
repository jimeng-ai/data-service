package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.ai.connector.service.ConnectorService;
import com.jimeng.dataserver.ai.connector.service.ConnectorView;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticStagedMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("语义层生成触发门面")
class ConnectorSemanticGenerationServiceTest {

    private static final Long CONNECTOR_ID = 7L;
    private static final Long GENERATION_ID = 101L;

    private ConnectorService connectorService;
    private SemanticGeneratorSelector selector;
    private AgentSemanticGenerator agentGenerator;
    private SingleCallSemanticGenerator singleGenerator;
    private ConnectorSemanticGenerationMapper generationMapper;
    private ConnectorSemanticStagedMapper stagedMapper;
    private ConnectionMapper connectionMapper;
    private ConnectorSemanticGenerationService service;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, ConnectorSemanticGeneration.class);
        TableInfoHelper.initTableInfo(assistant, Connection.class);

        connectorService = mock(ConnectorService.class);
        selector = mock(SemanticGeneratorSelector.class);
        agentGenerator = mock(AgentSemanticGenerator.class);
        singleGenerator = mock(SingleCallSemanticGenerator.class);
        generationMapper = mock(ConnectorSemanticGenerationMapper.class);
        stagedMapper = mock(ConnectorSemanticStagedMapper.class);
        connectionMapper = mock(ConnectionMapper.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new ConnectorSemanticGenerationService(connectorService, selector, agentGenerator,
                singleGenerator, generationMapper, stagedMapper, connectionMapper, transactionManager);
        TenantContext.set("tenant-a");
        when(connectorService.get(CONNECTOR_ID)).thenReturn(connector());
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        TenantContext.clear();
    }

    @Test
    @DisplayName("INTERRUPTED批次在配置性不通过时SUPERSEDED")
    void INTERRUPTED批次在配置性不通过时SUPERSEDED() {
        selectSingle(InterruptedBatchPolicy.SUPERSEDE, "agent 生成未开启，走单次推导", false);
        ConnectorSemanticGeneration interrupted = interrupted("STAGED");
        when(generationMapper.selectOne(any())).thenReturn(interrupted);
        when(generationMapper.update(any(), any())).thenReturn(1);
        GenerationAck expected = new GenerationAck(GeneratorKind.SINGLE_CALL, true, null, "已提交单次推导");
        when(singleGenerator.submit(any())).thenReturn(expected);

        GenerationAck actual = service.trigger(CONNECTOR_ID, GenerationTrigger.MANUAL_REGENERATE);

        assertEquals(expected, actual);
        var order = inOrder(generationMapper, stagedMapper, singleGenerator);
        order.verify(generationMapper).update(any(), any());
        order.verify(stagedMapper).physicalDeleteByGeneration("tenant-a", GENERATION_ID);
        order.verify(singleGenerator).submit(any());
        ArgumentCaptor<ConnectorSemanticGeneration> update =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        verify(generationMapper).update(update.capture(), any());
        assertEquals("CANCELLED", update.getValue().getStatus());
        assertEquals(GenerationReasonCode.SUPERSEDED.name(), update.getValue().getReasonCode());
    }

    @Test
    @DisplayName("探测瞬时失败不作废批次也不回落")
    void 探测瞬时失败不作废批次也不回落() {
        selectSingle(InterruptedBatchPolicy.KEEP, "sandbox 健康检查失败：timeout", false);
        when(generationMapper.selectOne(any())).thenReturn(interrupted("STAGED"));
        when(connectionMapper.update(any(), any())).thenReturn(1);

        GenerationAck ack = service.trigger(CONNECTOR_ID, GenerationTrigger.MANUAL_REGENERATE);

        assertFalse(ack.accepted());
        assertEquals(GeneratorKind.AGENT, ack.kind());
        assertEquals(GENERATION_ID, ack.generationId());
        assertEquals("sandbox 暂不可用，保留上次未完成的生成，稍后再点重新生成继续", ack.note());
        verify(generationMapper, never()).update(any(), any());
        verify(stagedMapper, never()).physicalDeleteByGeneration(any(), any());
        verify(singleGenerator, never()).submit(any());
        ArgumentCaptor<Connection> note = ArgumentCaptor.forClass(Connection.class);
        verify(connectionMapper).update(note.capture(), any());
        assertEquals(ack.note(), note.getValue().getSemanticNote());
    }

    @Test
    @DisplayName("总开关关闭不动INTERRUPTED批次")
    void 总开关关闭不动INTERRUPTED批次() {
        selectSingle(InterruptedBatchPolicy.LEAVE, null, true);
        when(generationMapper.selectOne(any())).thenReturn(interrupted("DIRECT"));
        GenerationAck expected = new GenerationAck(GeneratorKind.SINGLE_CALL, true, null, "已提交单次推导");
        when(singleGenerator.submit(any())).thenReturn(expected);

        assertEquals(expected, service.trigger(CONNECTOR_ID, GenerationTrigger.MANUAL_REGENERATE));

        verify(generationMapper, never()).update(any(), any());
        verify(stagedMapper, never()).physicalDeleteByGeneration(any(), any());
        ArgumentCaptor<GenerationRequest> request = ArgumentCaptor.forClass(GenerationRequest.class);
        verify(singleGenerator).submit(request.capture());
        assertEquals(null, request.getValue().degradeReason());
    }

    @Test
    @DisplayName("userId在请求线程取得")
    void userId在请求线程取得() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AdminRequestContext.HEADER_USER_ID, "9988");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(selector.select(any())).thenReturn(new SemanticGeneratorSelector.Selection(
                GeneratorKind.AGENT, PreconditionVerdict.allowed(), null));
        GenerationAck expected = new GenerationAck(GeneratorKind.AGENT, true, GENERATION_ID, "queued");
        when(agentGenerator.submit(any())).thenReturn(expected);

        assertEquals(expected, service.trigger(CONNECTOR_ID, GenerationTrigger.CONNECTOR_CREATED));

        ArgumentCaptor<GenerationRequest> submitted = ArgumentCaptor.forClass(GenerationRequest.class);
        verify(agentGenerator).submit(submitted.capture());
        assertEquals(9988L, submitted.getValue().triggeredBy());
        assertEquals("tenant-a", submitted.getValue().tenantId());
        assertEquals(GenerationTrigger.CONNECTOR_CREATED, submitted.getValue().trigger());
    }

    @Test
    @DisplayName("跨租户id被get挡住")
    void 跨租户id被get挡住() {
        ServiceException denied = new ServiceException(ExceptionCode.NOT_FOUND, "连接不存在");
        when(connectorService.get(CONNECTOR_ID)).thenThrow(denied);

        ServiceException thrown = assertThrows(ServiceException.class,
                () -> service.trigger(CONNECTOR_ID, GenerationTrigger.MANUAL_REGENERATE));

        assertEquals(denied, thrown);
        verify(selector, never()).select(any());
        verify(agentGenerator, never()).submit(any());
        verify(singleGenerator, never()).submit(any());
    }

    private void selectSingle(InterruptedBatchPolicy policy, String reason, boolean silent) {
        when(selector.select(any())).thenReturn(new SemanticGeneratorSelector.Selection(
                GeneratorKind.SINGLE_CALL,
                new PreconditionVerdict(false, silent, reason),
                policy));
    }

    private static ConnectorView connector() {
        return ConnectorView.builder().id(String.valueOf(CONNECTOR_ID)).capabilities(List.of("DESCRIBE")).build();
    }

    private static ConnectorSemanticGeneration interrupted(String mode) {
        ConnectorSemanticGeneration row = new ConnectorSemanticGeneration();
        row.setId(GENERATION_ID);
        row.setTenantId("tenant-a");
        row.setConnectorId(CONNECTOR_ID);
        row.setMode(mode);
        row.setStatus("INTERRUPTED");
        return row;
    }
}
