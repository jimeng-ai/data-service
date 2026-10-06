package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.connection.CredentialCipher;
import com.jimeng.dataserver.ai.connector.pool.CustomerDataSourceManager;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorInstanceLoader;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProbeService;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.mapper.AgentConnectionMapper;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 授权前的校验：连接要在当前租户里存在，而且类型平台还支持（2026-10 下线了 HTTP 类）。 */
class ConnectorServiceGrantableTest {

    private ConnectionMapper connectionMapper;
    private ConnectorService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        connectionMapper = mock(ConnectionMapper.class);
        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        when(registry.supports("MYSQL")).thenReturn(true);
        service = new ConnectorService(connectionMapper, mock(AgentConnectionMapper.class),
                mock(ConnectorSchemaMapper.class), registry, mock(ConnectorInstanceLoader.class),
                mock(ConnectorProbeService.class), mock(CustomerDataSourceManager.class), mock(CredentialCipher.class),
                mock(ConnectorSemanticService.class), mock(PlatformTransactionManager.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ConnectorAuditService.class));
    }

    private void row(long id, String kind) {
        Connection c = new Connection();
        c.setId(id);
        c.setKind(kind);
        when(connectionMapper.selectById(id)).thenReturn(c);
    }

    @Test
    @DisplayName("数据库类连接可以授权")
    void mysqlIsGrantable() {
        row(1L, "MYSQL");
        assertDoesNotThrow(() -> service.requireGrantable(1L));
    }

    @Test
    @DisplayName("★ 已下线类型（kind=HTTP）的遗留行不能再授权")
    void legacyHttpRowIsRejected() {
        row(9L, "HTTP");
        ServiceException e = assertThrows(ServiceException.class, () -> service.requireGrantable(9L));
        assertTrue(e.getMessage().contains("已下线"), e.getMessage());
    }

    @Test
    @DisplayName("不存在（或不属于本租户，租户过滤由拦截器做）的连接：NOT_FOUND")
    void missingRowIsNotFound() {
        ServiceException e = assertThrows(ServiceException.class, () -> service.requireGrantable(404L));
        assertTrue(e.getMessage().contains("连接不存在"), e.getMessage());
    }
}
