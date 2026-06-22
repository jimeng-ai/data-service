package com.jimeng.dataserver.ai.image;

import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    void executeUploadsAndReturnsBackendUrls() throws Exception {
        ImageGenClient client = mock(ImageGenClient.class);
        when(client.generate(eq("胖橘猫"), anyString(), eq(1)))
                .thenReturn(List.of(new byte[]{(byte) 0x89, 'P', 'N', 'G'}));
        RagMinioStorageService minio = mock(RagMinioStorageService.class);
        AiModelCallRecordService rec = mock(AiModelCallRecordService.class);

        GenerateImageToolExecutor ex = new GenerateImageToolExecutor(client, minio, rec, props());
        Object out = ex.execute("generate_image", Map.of("prompt", "胖橘猫"));

        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) out;
        @SuppressWarnings("unchecked")
        List<String> urls = (List<String>) m.get("urls");
        assertEquals(1, urls.size());
        // 返回的是后端鉴权回传路径(非裸 presigned)；扁平 genimg- key、png 扩展名。
        assertTrue(urls.get(0).matches("^/data/ai/image/genimg-[0-9a-f]+\\.png$"),
                "unexpected url: " + urls.get(0));
        assertEquals(1, m.get("count"));

        // 落库走 putObject(扁平 key、不改写)；不再调用会加日期斜杠前缀的 uploadBytes，也不再 presign。
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(minio).putObject(keyCap.capture(), any(), eq("image/png"));
        assertTrue(keyCap.getValue().matches("^genimg-[0-9a-f]+\\.png$"),
                "unexpected key: " + keyCap.getValue());
        verify(minio, never()).uploadBytes(any(), anyString(), anyString());
        verify(minio, never()).presignedUrl(anyString(), anyInt());

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
