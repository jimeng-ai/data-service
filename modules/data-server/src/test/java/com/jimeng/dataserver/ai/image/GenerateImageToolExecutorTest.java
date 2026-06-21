package com.jimeng.dataserver.ai.image;

import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenerateImageToolExecutorTest {

    private static AgentSandboxProperties props() {
        AgentSandboxProperties p = new AgentSandboxProperties();
        AgentSandboxProperties.ImageGen ig = new AgentSandboxProperties.ImageGen();
        ig.setProvider("seedream");
        ig.setBaseUrl("https://api.302.ai/v1");
        ig.setAuthToken("sk-x");
        ig.setModel("doubao-seedream-5-0");
        p.setImageGen(ig);
        return p;
    }

    @Test
    void supportsOnlyGenerateImage() {
        GenerateImageToolExecutor ex = new GenerateImageToolExecutor(
                mock(ImageGenClient.class), mock(RagMinioStorageService.class),
                mock(AiModelCallRecordService.class), props());
        assertTrue(ex.supports("generate_image"));
        assertFalse(ex.supports("rag_search"));
    }

    @Test
    void executeUploadsAndReturnsUrls() throws Exception {
        ImageGenClient client = mock(ImageGenClient.class);
        when(client.generate(eq("胖橘猫"), anyString(), eq(1)))
                .thenReturn(List.of(new byte[]{(byte) 0x89, 'P', 'N', 'G'}));
        RagMinioStorageService minio = mock(RagMinioStorageService.class);
        when(minio.uploadBytes(any(), anyString(), anyString())).thenReturn("2026/06/21/abc_genimg.png");
        when(minio.presignedUrl(eq("2026/06/21/abc_genimg.png"), anyInt())).thenReturn("http://minio/genimg.png");
        AiModelCallRecordService rec = mock(AiModelCallRecordService.class);

        GenerateImageToolExecutor ex = new GenerateImageToolExecutor(client, minio, rec, props());
        Object out = ex.execute("generate_image", Map.of("prompt", "胖橘猫"));

        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) out;
        assertEquals(List.of("http://minio/genimg.png"), m.get("urls"));
        assertEquals(1, m.get("count"));
        verify(rec).recordComputedCall(any(), eq("image:generate"), any(), eq("image_gen"),
                any(), eq(200), anyInt(), any());
    }

    @Test
    void executeRejectsBlankPrompt() {
        GenerateImageToolExecutor ex = new GenerateImageToolExecutor(
                mock(ImageGenClient.class), mock(RagMinioStorageService.class),
                mock(AiModelCallRecordService.class), props());
        assertThrows(IllegalArgumentException.class,
                () -> ex.execute("generate_image", Map.of("prompt", "")));
    }
}
