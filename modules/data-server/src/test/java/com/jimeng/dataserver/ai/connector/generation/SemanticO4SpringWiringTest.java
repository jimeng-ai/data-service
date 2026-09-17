package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.dataserver.admin.auth.service.AdminAuthService;
import com.jimeng.dataserver.ai.agent.exec.service.SidecarClient;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.connector.generation.outcome.SliceOutcomeHandlerRegistry;
import com.jimeng.dataserver.ai.model.ModelRegistry;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationTableMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class SemanticO4SpringWiringTest {

    @Test
    @DisplayName("O4 组件可由真实 Spring 构造且 outcome 注册表完整初始化")
    void spring构造真实组件() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ConnectorSemanticGenerationTableMapper.class,
                    () -> mock(ConnectorSemanticGenerationTableMapper.class));
            context.registerBean(ConnectorSchemaMapper.class, () -> mock(ConnectorSchemaMapper.class));
            context.registerBean(ConnectorSemanticGenerationMapper.class,
                    () -> mock(ConnectorSemanticGenerationMapper.class));
            context.registerBean(AdminAuthService.class, () -> mock(AdminAuthService.class));
            context.registerBean(SidecarClient.class, () -> mock(SidecarClient.class));
            context.registerBean(SemanticGenerationHeartbeat.class,
                    () -> mock(SemanticGenerationHeartbeat.class));
            context.registerBean(AiModelCallRecordService.class,
                    () -> mock(AiModelCallRecordService.class));
            context.registerBean(ModelRegistry.class, () -> mock(ModelRegistry.class));
            context.register(SemanticTableRenderer.class, SemanticSlicePlanner.class,
                    SemanticSliceUsageRecorder.class, SemanticSliceDispatcher.class,
                    SliceOutcomeHandlerRegistry.class);
            context.refresh();

            assertNotNull(context.getBean(SemanticSlicePlanner.class));
            assertNotNull(context.getBean(SemanticSliceDispatcher.class));
            assertNotNull(context.getBean(SemanticSliceUsageRecorder.class));
            assertNotNull(context.getBean(SliceOutcomeHandlerRegistry.class));
        }
    }
}
