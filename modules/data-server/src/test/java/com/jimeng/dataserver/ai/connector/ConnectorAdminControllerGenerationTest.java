package com.jimeng.dataserver.ai.connector;

import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
import com.jimeng.dataserver.ai.connector.generation.ConnectorSemanticGenerationService;
import com.jimeng.dataserver.ai.connector.generation.GenerationAck;
import com.jimeng.dataserver.ai.connector.generation.GenerationTrigger;
import com.jimeng.dataserver.ai.connector.generation.GeneratorKind;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.dataserver.ai.connector.service.ConnectorSchemaService;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticDeriveService;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.ConnectorService;
import com.jimeng.dataserver.ai.connector.service.ConnectorUpsert;
import com.jimeng.dataserver.ai.connector.service.ConnectorView;
import com.jimeng.dataserver.ai.connector.service.GrantScriptService;
import com.jimeng.dataserver.ai.connector.service.PendingWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("连接器控制器语义生成入口")
class ConnectorAdminControllerGenerationTest {

    private ConnectorService connectorService;
    private ConnectorSemanticGenerationService generationService;
    private ConnectorAdminController controller;

    @BeforeEach
    void setUp() {
        connectorService = mock(ConnectorService.class);
        generationService = mock(ConnectorSemanticGenerationService.class);
        controller = new ConnectorAdminController(connectorService, mock(ConnectorAuditService.class),
                mock(ConnectorSchemaService.class), mock(ConnectorSemanticService.class),
                mock(ConnectorSemanticDeriveService.class), generationService,
                mock(PendingWriteService.class), mock(GrantScriptService.class), mock(SuperAdminGuard.class));
    }

    @Test
    @DisplayName("新建成功后改道生成门面")
    void 新建成功后改道生成门面() {
        ConnectorUpsert request = new ConnectorUpsert();
        ConnectorView created = ConnectorView.builder().id("7").build();
        when(connectorService.create(request)).thenReturn(created);

        assertEquals(created, controller.create(request));

        verify(generationService).trigger(7L, GenerationTrigger.CONNECTOR_CREATED);
    }

    @Test
    @DisplayName("新建返回非法id时保持跳过")
    void 新建返回非法id时保持跳过() {
        when(connectorService.create(any())).thenReturn(ConnectorView.builder().id("probe").build());

        controller.create(new ConnectorUpsert());

        verify(generationService, never()).trigger(any(), any());
    }

    @Test
    @DisplayName("新建生成派发失败不推翻连接创建")
    void 新建生成派发失败不推翻连接创建() {
        ConnectorView created = ConnectorView.builder().id("7").build();
        when(connectorService.create(any())).thenReturn(created);
        when(generationService.trigger(7L, GenerationTrigger.CONNECTOR_CREATED))
                .thenThrow(new IllegalStateException("closing"));

        assertEquals(created, controller.create(new ConnectorUpsert()));
    }

    @Test
    @DisplayName("手工重跑先校验归属再改道并返回生成器")
    void 手工重跑先校验归属再改道并返回生成器() {
        GenerationAck ack = new GenerationAck(GeneratorKind.AGENT, false, 101L, "保留");
        when(generationService.trigger(7L, GenerationTrigger.MANUAL_REGENERATE)).thenReturn(ack);

        Map<String, Object> response = controller.deriveSemantic(7L);

        assertEquals(true, response.get("started"));
        assertEquals("AGENT", response.get("generator"));
        var order = inOrder(connectorService, generationService);
        order.verify(connectorService).get(7L);
        order.verify(generationService).trigger(7L, GenerationTrigger.MANUAL_REGENERATE);
        assertTrue(response.size() == 2);
    }
}
