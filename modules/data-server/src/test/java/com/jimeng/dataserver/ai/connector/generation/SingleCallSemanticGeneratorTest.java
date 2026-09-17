package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticDeriveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("单次语义推导生成器")
class SingleCallSemanticGeneratorTest {

    private ConnectorSemanticDeriveService deriveService;
    private SingleCallSemanticGenerator generator;

    @BeforeEach
    void setUp() {
        deriveService = mock(ConnectorSemanticDeriveService.class);
        generator = new SingleCallSemanticGenerator(deriveService);
    }

    @Test
    void 非空降级原因被稳定格式化为全角分号结尾的说明前缀() {
        GenerationRequest request = new GenerationRequest(
                7L, "tenant-a", 9L, GenerationTrigger.MANUAL_REGENERATE, "sandbox 未配置");

        GenerationAck ack = generator.submit(request);

        ArgumentCaptor<String> prefix = ArgumentCaptor.forClass(String.class);
        verify(deriveService).deriveAsync(eq(7L), prefix.capture());
        assertEquals("降级原因：sandbox 未配置；", prefix.getValue());
        assertEquals(GeneratorKind.SINGLE_CALL, ack.kind());
        assertTrue(ack.accepted());
        assertNull(ack.generationId());
    }

    @Test
    void 空降级原因不伪造前缀() {
        GenerationRequest request = new GenerationRequest(
                7L, "tenant-a", 9L, GenerationTrigger.CONNECTOR_CREATED, "  ");

        generator.submit(request);

        verify(deriveService).deriveAsync(7L, null);
    }

    @Test
    void 后台提交异常被收敛为拒绝ack而不外抛() {
        doThrow(new IllegalStateException("线程池已关闭"))
                .when(deriveService).deriveAsync(any(), any());
        GenerationRequest request = new GenerationRequest(
                7L, "tenant-a", 9L, GenerationTrigger.CONNECTOR_CREATED, null);

        GenerationAck ack = generator.submit(request);

        assertEquals(GeneratorKind.SINGLE_CALL, ack.kind());
        assertFalse(ack.accepted());
        assertNull(ack.generationId());
        assertTrue(ack.note().contains("线程池已关闭"), ack.note());
    }
}
