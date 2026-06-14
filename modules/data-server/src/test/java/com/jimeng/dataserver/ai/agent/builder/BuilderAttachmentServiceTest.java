package com.jimeng.dataserver.ai.agent.builder;

import com.jimeng.dataserver.ai.rag.model.DocumentBlock;
import com.jimeng.dataserver.ai.rag.model.ParsedDocument;
import com.jimeng.dataserver.ai.rag.service.parse.DocumentParser;
import com.jimeng.dataserver.ai.rag.service.parse.DocumentParserRegistry;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.persistence.entity.AgentInputFile;
import com.jimeng.persistence.mapper.AgentInputFileMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BuilderAttachmentServiceTest {

    private final RagMinioStorageService storage = mock(RagMinioStorageService.class);
    private final AgentInputFileMapper fileMapper = mock(AgentInputFileMapper.class);
    private final DocumentParserRegistry parserRegistry = mock(DocumentParserRegistry.class);
    private final BuilderAttachmentService svc =
            new BuilderAttachmentService(storage, fileMapper, parserRegistry);

    private AgentInputFile file(Long id, String name, String ct) {
        AgentInputFile f = new AgentInputFile();
        f.setId(id);
        f.setObjectName("obj-" + id);
        f.setFilename(name);
        f.setContentType(ct);
        return f;
    }

    @Test
    void imageFile_becomesBase64ImageBlock() throws Exception {
        when(fileMapper.selectById(1L)).thenReturn(file(1L, "shot.png", "image/png"));
        when(storage.download("obj-1")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        List<Map<String, Object>> blocks = svc.toContentBlocks(List.of(1L));

        assertEquals(1, blocks.size());
        assertEquals("image", blocks.get(0).get("type"));
        Map<?, ?> source = (Map<?, ?>) blocks.get(0).get("source");
        assertEquals("base64", source.get("type"));
        assertEquals("image/png", source.get("media_type"));
        assertNotNull(source.get("data"));
    }

    @Test
    void docFile_becomesTextBlock() throws Exception {
        when(fileMapper.selectById(2L)).thenReturn(file(2L, "spec.pdf", "application/pdf"));
        when(storage.download("obj-2")).thenReturn(new ByteArrayInputStream(new byte[]{9}));
        DocumentParser parser = mock(DocumentParser.class);
        ParsedDocument parsed = new ParsedDocument();
        parsed.setBlocks(List.of(
                DocumentBlock.text(null, 1, "退货政策：7 天无理由"),
                DocumentBlock.text(null, 1, "运费由买家承担")));
        when(parserRegistry.resolve("application/pdf", "spec.pdf")).thenReturn(parser);
        when(parser.parse(any(), eq("spec.pdf"))).thenReturn(parsed);

        List<Map<String, Object>> blocks = svc.toContentBlocks(List.of(2L));

        assertEquals(1, blocks.size());
        assertEquals("text", blocks.get(0).get("type"));
        String text = String.valueOf(blocks.get(0).get("text"));
        assertTrue(text.contains("spec.pdf"));
        assertTrue(text.contains("退货政策"));
        assertTrue(text.contains("运费由买家承担"));
    }

    @Test
    void brokenFile_isSkipped_notThrow() throws Exception {
        when(fileMapper.selectById(3L)).thenReturn(file(3L, "bad.pdf", "application/pdf"));
        when(storage.download("obj-3")).thenThrow(new RuntimeException("minio down"));

        List<Map<String, Object>> blocks = svc.toContentBlocks(List.of(3L));
        assertTrue(blocks.isEmpty());   // 单个文件失败不抛、跳过
    }
}
