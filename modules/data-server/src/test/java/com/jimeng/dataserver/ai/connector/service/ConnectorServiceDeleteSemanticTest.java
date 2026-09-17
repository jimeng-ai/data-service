package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.dataserver.ai.connection.CredentialCipher;
import com.jimeng.dataserver.ai.connector.generation.GenerationReasonCode;
import com.jimeng.dataserver.ai.connector.generation.SemanticConnectionClaim;
import com.jimeng.dataserver.ai.connector.generation.SemanticGenerationCleanup;
import com.jimeng.dataserver.ai.connector.pool.CustomerDataSourceManager;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorInstanceLoader;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProbeService;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.mapper.AgentConnectionMapper;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticStagedMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("删除连接时清理语义层生成")
class ConnectorServiceDeleteSemanticTest {

    private static final String TENANT_ID = "tenant-a";
    private static final Long CONNECTOR_ID = 7L;

    private SemanticConnectionClaim claim;
    private ConnectorSemanticStagedMapper stagedMapper;
    private ConnectorSemanticGenerationMapper generationMapper;
    private SemanticGenerationCleanup cleanup;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
                ConnectorSemanticGeneration.class);
        claim = mock(SemanticConnectionClaim.class);
        stagedMapper = mock(ConnectorSemanticStagedMapper.class);
        generationMapper = mock(ConnectorSemanticGenerationMapper.class);
        cleanup = new SemanticGenerationCleanup(claim, stagedMapper, generationMapper);
    }

    @Test
    @DisplayName("删除时未结束批次置CANCELLED")
    void 删除时未结束批次置CANCELLED() {
        when(generationMapper.update(any(), any())).thenReturn(2);

        cleanup.onConnectorDeleted(TENANT_ID, CONNECTOR_ID);

        ArgumentCaptor<ConnectorSemanticGeneration> update =
                ArgumentCaptor.forClass(ConnectorSemanticGeneration.class);
        verify(generationMapper).update(update.capture(), any());
        assertEquals("CANCELLED", update.getValue().getStatus());
        assertEquals(GenerationReasonCode.CONNECTION_DELETED.name(), update.getValue().getReasonCode());
        assertNotNull(update.getValue().getFinishedAt());
    }

    @Test
    @DisplayName("暂存行被物理删除")
    void 暂存行被物理删除() {
        cleanup.onConnectorDeleted(TENANT_ID, CONNECTOR_ID);

        verify(stagedMapper).physicalDeleteByConnector(TENANT_ID, CONNECTOR_ID);
    }

    @Test
    @DisplayName("无批次时不报错")
    void 无批次时不报错() {
        when(generationMapper.update(any(), any())).thenReturn(0);

        assertDoesNotThrow(() -> cleanup.onConnectorDeleted(TENANT_ID, CONNECTOR_ID));
    }

    @Test
    @DisplayName("先锁连接行再改批次")
    void 先锁连接行再改批次() {
        cleanup.onConnectorDeleted(TENANT_ID, CONNECTOR_ID);

        var order = inOrder(claim, stagedMapper, generationMapper);
        order.verify(claim).lockRow(CONNECTOR_ID);
        order.verify(stagedMapper).physicalDeleteByConnector(TENANT_ID, CONNECTOR_ID);
        order.verify(generationMapper).update(any(), any());
    }

    @Test
    @DisplayName("ObjectProvider为空时跳过")
    void ObjectProvider为空时跳过() {
        ConnectionMapper connectionMapper = mock(ConnectionMapper.class);
        Connection row = new Connection();
        row.setId(CONNECTOR_ID);
        row.setTenantId(TENANT_ID);
        row.setName("shop");
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(row);
        ObjectProvider<SemanticGenerationCleanup> cleanupProvider = mock(ObjectProvider.class);
        ConnectorService service = connectorService(connectionMapper, cleanupProvider);

        service.delete(CONNECTOR_ID);

        verify(cleanupProvider).getIfAvailable();
        verify(connectionMapper).deleteById(CONNECTOR_ID);
    }

    @Test
    @DisplayName("删除连接行前调用语义清理")
    void 删除连接行前调用语义清理() {
        ConnectionMapper connectionMapper = mock(ConnectionMapper.class);
        Connection row = new Connection();
        row.setId(CONNECTOR_ID);
        row.setTenantId(TENANT_ID);
        row.setName("shop");
        when(connectionMapper.selectById(CONNECTOR_ID)).thenReturn(row);
        ObjectProvider<SemanticGenerationCleanup> cleanupProvider = mock(ObjectProvider.class);
        SemanticGenerationCleanup cleanupMock = mock(SemanticGenerationCleanup.class);
        when(cleanupProvider.getIfAvailable()).thenReturn(cleanupMock);
        ConnectorService service = connectorService(connectionMapper, cleanupProvider);

        service.delete(CONNECTOR_ID);

        var order = inOrder(cleanupMock, connectionMapper);
        order.verify(cleanupMock).onConnectorDeleted(TENANT_ID, CONNECTOR_ID);
        order.verify(connectionMapper).deleteById(CONNECTOR_ID);
    }

    private static ConnectorService connectorService(
            ConnectionMapper connectionMapper,
            ObjectProvider<SemanticGenerationCleanup> cleanupProvider) {
        return new ConnectorService(connectionMapper, mock(AgentConnectionMapper.class),
                mock(ConnectorSchemaMapper.class), mock(ConnectorRegistry.class),
                mock(ConnectorInstanceLoader.class), mock(ConnectorProbeService.class),
                mock(CustomerDataSourceManager.class), mock(CredentialCipher.class),
                mock(ConnectorSemanticService.class), mock(PlatformTransactionManager.class),
                mock(ObjectProvider.class), cleanupProvider);
    }
}
