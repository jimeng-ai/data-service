package com.jimeng.dataserver.ai.agent.builder;

import cn.hutool.core.util.StrUtil;
import com.jimeng.dataserver.ai.rag.model.DocumentBlock;
import com.jimeng.dataserver.ai.rag.model.ParsedDocument;
import com.jimeng.dataserver.ai.rag.service.parse.DocumentParser;
import com.jimeng.dataserver.ai.rag.service.parse.DocumentParserRegistry;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.persistence.entity.AgentInputFile;
import com.jimeng.persistence.mapper.AgentInputFileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把构建器向导上传的输入文件转成 LLM content 块：图片→多模态 image 块；文档→解析文本块。
 * 单文件失败只跳过不整轮失败（对齐 RAG 解析兜底）。文档文本按上限截断，避免撑爆上下文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuilderAttachmentService {

    /** 单个文档抽取文本上限（字符）。 */
    private static final int MAX_DOC_CHARS = 20_000;

    private final RagMinioStorageService storage;
    private final AgentInputFileMapper inputFileMapper;
    private final DocumentParserRegistry parserRegistry;

    public List<Map<String, Object>> toContentBlocks(List<Long> fileIds) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (fileIds == null || fileIds.isEmpty()) return blocks;
        for (Long fileId : fileIds) {
            try {
                AgentInputFile f = inputFileMapper.selectById(fileId);
                if (f == null) continue;
                byte[] bytes = readAll(f.getObjectName());
                String ct = f.getContentType();
                if (ct != null && ct.startsWith("image/")) {
                    blocks.add(imageBlock(bytes, ct));
                } else {
                    String text = extractText(bytes, ct, f.getFilename());
                    if (StrUtil.isNotBlank(text)) {
                        blocks.add(textBlock("【参考资料：" + f.getFilename() + "】\n" + text));
                    }
                }
            } catch (Exception e) {
                log.warn("构建器附件处理失败，跳过 fileId={}: {}", fileId, e.getMessage());
            }
        }
        return blocks;
    }

    private byte[] readAll(String objectName) throws Exception {
        try (InputStream is = storage.download(objectName)) {
            return is.readAllBytes();
        }
    }

    private String extractText(byte[] bytes, String contentType, String filename) throws Exception {
        DocumentParser parser = parserRegistry.resolve(contentType, filename);
        ParsedDocument parsed = parser.parse(new java.io.ByteArrayInputStream(bytes), filename);
        StringBuilder sb = new StringBuilder();
        if (parsed.getBlocks() != null) {
            for (DocumentBlock b : parsed.getBlocks()) {
                if (b.getText() != null && !b.getText().isBlank()) {
                    sb.append(b.getText()).append("\n");
                    if (sb.length() >= MAX_DOC_CHARS) {
                        sb.append("\n（内容过长，已截断）");
                        break;
                    }
                }
            }
        }
        return sb.toString();
    }

    private Map<String, Object> imageBlock(byte[] bytes, String mediaType) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "base64");
        source.put("media_type", StrUtil.isBlank(mediaType) ? "image/png" : mediaType);
        source.put("data", Base64.getEncoder().encodeToString(bytes));
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image");
        block.put("source", source);
        return block;
    }

    private Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }
}
