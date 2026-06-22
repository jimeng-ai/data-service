package com.jimeng.dataserver.ai.image;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeneratedImageControllerTest {

    @Test
    void streamsPngRawBytesInline() throws Exception {
        RagMinioStorageService storage = mock(RagMinioStorageService.class);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        when(storage.download("genimg-abc123def.png")).thenReturn(new ByteArrayInputStream(png));

        MockHttpServletResponse resp = new MockHttpServletResponse();
        new GeneratedImageController(storage).get("genimg-abc123def.png", resp);

        assertEquals("image/png", resp.getContentType());
        assertTrue(resp.getHeader("Content-Disposition").startsWith("inline"));
        // 原始字节直写 OutputStream，未被 GlobalResponseHandler 包成 JSON。
        assertArrayEquals(png, resp.getContentAsByteArray());
    }

    @Test
    void jpgGetsJpegContentType() throws Exception {
        RagMinioStorageService storage = mock(RagMinioStorageService.class);
        when(storage.download("genimg-deadbeef.jpg")).thenReturn(new ByteArrayInputStream(new byte[]{1}));

        MockHttpServletResponse resp = new MockHttpServletResponse();
        new GeneratedImageController(storage).get("genimg-deadbeef.jpg", resp);

        assertEquals("image/jpeg", resp.getContentType());
    }

    @Test
    void rejectsNonGenimgNamesWithoutTouchingStorage() {
        RagMinioStorageService storage = mock(RagMinioStorageService.class);
        GeneratedImageController c = new GeneratedImageController(storage);
        // 越权读 RAG 其它对象 / 路径穿越 / 非法扩展名 / 空 UUID 一律 404，且根本不访问存储。
        String[] bad = {
                "secret-doc.pdf",          // 非 genimg-，挡住读取知识库对象
                "../genimg-abc.png",       // 路径穿越
                "genimg-abc.gif",          // 扩展名不允许
                "genimg-zz.png",           // 非十六进制
                "genimg-.png",             // 空 UUID
        };
        for (String name : bad) {
            assertThrows(ServiceException.class,
                    () -> c.get(name, new MockHttpServletResponse()), name);
        }
        verifyNoInteractions(storage);
    }
}
