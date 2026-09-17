package com.jimeng.dataserver.ai.connector.generation.callback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticAgentCallbackControllerTest {

    @Test
    @DisplayName("四个端点只把过滤器写入的 principal 交给服务，不从请求体或头读取资源 id")
    void controller只使用principal() {
        SemanticAgentCallbackService service = mock(SemanticAgentCallbackService.class);
        SemanticAgentCallbackController controller = new SemanticAgentCallbackController(service);
        SemanticAgentPrincipal principal = new SemanticAgentPrincipal("t", "u", 1, 2, 3, "r", "DIRECT");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SemanticAgentTokens.PRINCIPAL_ATTRIBUTE, principal);
        TableListRequest listRequest = new TableListRequest();
        TableMetadataRequest metadataRequest = new TableMetadataRequest();
        SubmitRequest submitRequest = new SubmitRequest();
        RunScopeView runScope = new RunScopeView();
        TableListView tables = new TableListView();
        TableMetadataView metadata = new TableMetadataView();
        SubmitResultView submit = new SubmitResultView();
        when(service.runScope(principal)).thenReturn(runScope);
        when(service.listTables(principal, listRequest)).thenReturn(tables);
        when(service.tableMetadata(principal, metadataRequest)).thenReturn(metadata);
        when(service.submit(principal, submitRequest)).thenReturn(submit);

        assertSame(runScope, controller.runScope(request, Map.of(
                "generationId", "forged", "connectorId", "forged", "tenantId", "forged", "runId", "forged")));
        assertSame(tables, controller.tables(request, listRequest));
        assertSame(metadata, controller.tableMetadata(request, metadataRequest));
        assertSame(submit, controller.submit(request, submitRequest));
        verify(service).runScope(principal);
        verify(service).listTables(principal, listRequest);
        verify(service).tableMetadata(principal, metadataRequest);
        verify(service).submit(principal, submitRequest);
    }

    @Test
    @DisplayName("绕过过滤器直接调用控制器时失败关闭")
    void 缺principal失败() {
        SemanticAgentCallbackController controller = new SemanticAgentCallbackController(mock(SemanticAgentCallbackService.class));
        assertThrows(IllegalStateException.class, () -> controller.runScope(new MockHttpServletRequest(), Map.of()));
    }
}
