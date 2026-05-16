package com.jimeng.dataserver.ai.rag.service.ingest;

import cn.hutool.json.JSONUtil;
import com.jimeng.dataserver.ai.rag.model.BlockType;
import com.jimeng.dataserver.ai.rag.model.Chunk;
import com.jimeng.dataserver.ai.rag.model.DocumentBlock;
import com.jimeng.dataserver.ai.rag.model.IngestionStatus;
import com.jimeng.dataserver.ai.rag.model.ParsedDocument;
import com.jimeng.dataserver.ai.rag.service.chunk.HierarchicalChunker;
import com.jimeng.dataserver.ai.rag.service.contextualize.ContextualizationService;
import com.jimeng.dataserver.ai.rag.service.contextualize.ImageDescriptionService;
import com.jimeng.dataserver.ai.rag.service.embed.EmbeddingService;
import com.jimeng.dataserver.ai.rag.service.es.ChunkIndexService;
import com.jimeng.dataserver.ai.rag.service.parse.DocumentParser;
import com.jimeng.dataserver.ai.rag.service.parse.DocumentParserRegistry;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.persistence.entity.KbDocument;
import com.jimeng.persistence.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档入库流程编排：UPLOADED → PARSING → CHUNKING → CONTEXTUALIZING → EMBEDDING → DONE / FAILED
 *
 * <p>由 RabbitMQ Consumer 异步调用。整篇文档串行执行以让 Anthropic prompt cache 命中（5min TTL）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final KbDocumentMapper kbDocumentMapper;
    private final RagMinioStorageService minioStorage;
    private final DocumentParserRegistry parserRegistry;
    private final HierarchicalChunker chunker;
    private final ContextualizationService contextualizationService;
    private final ImageDescriptionService imageDescriptionService;
    private final EmbeddingService embeddingService;
    private final ChunkIndexService chunkIndexService;

    public void ingest(Long docId) {
        KbDocument doc = kbDocumentMapper.selectById(docId);
        if (doc == null) {
            log.warn("ingest: kb_document[{}] 不存在", docId);
            return;
        }
        long t0 = System.currentTimeMillis();
        Map<String, Object> metrics = new HashMap<>();

        try {
            // 1) PARSING
            updateStatus(doc, IngestionStatus.PARSING);
            long t1 = System.currentTimeMillis();
            ParsedDocument parsed = parse(doc);
            metrics.put("parse_ms", System.currentTimeMillis() - t1);
            metrics.put("blocks", parsed.getBlocks() == null ? 0 : parsed.getBlocks().size());

            // 2) CHUNKING
            updateStatus(doc, IngestionStatus.CHUNKING);
            long t2 = System.currentTimeMillis();
            List<Chunk> chunks = chunker.chunk(parsed);
            metrics.put("chunk_ms", System.currentTimeMillis() - t2);
            metrics.put("chunks", chunks.size());
            if (chunks.isEmpty()) {
                throw new IllegalStateException("解析后无任何 chunk");
            }

            // 3) IMAGE description (把 IMAGE chunks 的图片转描述)
            long t3 = System.currentTimeMillis();
            describeImages(parsed, chunks);
            metrics.put("image_ms", System.currentTimeMillis() - t3);

            // 4) CONTEXTUALIZING
            updateStatus(doc, IngestionStatus.CONTEXTUALIZING);
            long t4 = System.currentTimeMillis();
            String fullDocText = renderFullDocText(parsed);
            List<String> contexts = contextualizationService.generateContexts(
                    fullDocText,
                    chunks.stream().map(Chunk::getContent).toList());
            for (int i = 0; i < chunks.size(); i++) {
                String ctx = contexts.get(i);
                Chunk c = chunks.get(i);
                if (ctx == null || ctx.isBlank()) {
                    c.setContextualizedContent(c.getContent());
                } else {
                    c.setContextualizedContent(ctx + "\n\n" + c.getContent());
                }
            }
            metrics.put("contextualize_ms", System.currentTimeMillis() - t4);

            // 5) EMBEDDING
            updateStatus(doc, IngestionStatus.EMBEDDING);
            long t5 = System.currentTimeMillis();
            List<String> texts = chunks.stream().map(Chunk::getContextualizedContent).toList();
            List<float[]> vectors = embeddingService.embedAll(texts);
            for (int i = 0; i < chunks.size(); i++) {
                chunks.get(i).setEmbedding(vectors.get(i));
            }
            metrics.put("embed_ms", System.currentTimeMillis() - t5);

            // 6) INDEX
            long t6 = System.currentTimeMillis();
            chunkIndexService.indexChunks(doc.getKbId(), doc.getId(), chunks);
            metrics.put("index_ms", System.currentTimeMillis() - t6);

            // 7) DONE
            doc.setTotalChunks(chunks.size());
            doc.setTotalTokens(chunks.stream().mapToInt(Chunk::getTokenCount).sum());
            metrics.put("total_ms", System.currentTimeMillis() - t0);
            doc.setIngestionMetadata(JSONUtil.toJsonStr(metrics));
            doc.setStatus(IngestionStatus.DONE.code());
            doc.setFailureReason(null);    // 成功完成时清掉历史失败记录
            doc.setUpdateTime(new Date());
            kbDocumentMapper.updateById(doc);
            log.info("ingest DONE docId={} chunks={} total={}ms",
                    docId, chunks.size(), metrics.get("total_ms"));
        } catch (Exception e) {
            log.error("ingest FAILED docId=" + docId, e);
            doc.setStatus(IngestionStatus.FAILED.code());
            doc.setFailureReason(appendFailureReason(doc.getFailureReason(), e.getMessage()));
            doc.setUpdateTime(new Date());
            kbDocumentMapper.updateById(doc);
            throw new RuntimeException(e);
        }
    }

    private ParsedDocument parse(KbDocument doc) throws Exception {
        try (InputStream is = minioStorage.download(doc.getMinioObject())) {
            DocumentParser parser = parserRegistry.resolve(null, doc.getTitle());
            return parser.parse(is, doc.getTitle());
        }
    }

    private void describeImages(ParsedDocument parsed, List<Chunk> chunks) {
        // 当前 parser 阶段还未生成 IMAGE block（PDF/DOCX 抽图后续优化点），这里预留挂钩
        // 若 chunks 有 IMAGE 类型且 content 为空，可从 parsed.blocks 中取对应 imageBytes 调 vision
        Map<Integer, DocumentBlock> imageBlockByIndex = new HashMap<>();
        if (parsed.getBlocks() != null) {
            for (int i = 0; i < parsed.getBlocks().size(); i++) {
                DocumentBlock b = parsed.getBlocks().get(i);
                if (b.getType() == BlockType.IMAGE) imageBlockByIndex.put(i, b);
            }
        }
        if (imageBlockByIndex.isEmpty()) return;

        for (Chunk c : chunks) {
            if (c.getType() != BlockType.IMAGE) continue;
            if (c.getContent() != null && !c.getContent().isBlank()) continue;
            // 简化策略：按 chunk_index 在 image block 序列里取第 N 个
            DocumentBlock anyImage = imageBlockByIndex.values().iterator().next();
            String desc = imageDescriptionService.describe(anyImage.getImageBytes(), anyImage.getImageMediaType());
            c.setContent(desc);
        }
    }

    private String renderFullDocText(ParsedDocument parsed) {
        StringBuilder sb = new StringBuilder();
        if (parsed.getTitle() != null) sb.append("# ").append(parsed.getTitle()).append("\n\n");
        if (parsed.getBlocks() == null) return sb.toString();
        List<String> lastPath = new ArrayList<>();
        for (DocumentBlock b : parsed.getBlocks()) {
            if (b.getHeadingPath() != null && !b.getHeadingPath().equals(lastPath)) {
                for (int i = 0; i < b.getHeadingPath().size(); i++) {
                    sb.append("#".repeat(i + 2)).append(' ').append(b.getHeadingPath().get(i)).append('\n');
                }
                lastPath = b.getHeadingPath();
                sb.append('\n');
            }
            if (b.getText() != null) sb.append(b.getText()).append("\n\n");
        }
        return sb.toString();
    }

    private void updateStatus(KbDocument doc, IngestionStatus status) {
        doc.setStatus(status.code());
        doc.setUpdateTime(new Date());
        kbDocumentMapper.updateById(doc);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static final DateTimeFormatter FAILURE_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int FAILURE_REASON_MAX_LEN = 1000;

    /**
     * 把本次失败追加到 failure_reason 顶部，保留历史失败记录但截断总长度。格式：
     * <pre>
     * [2026-05-16 21:58:30] Contextualization 连续 3 次返回 402, ...
     * [2026-05-16 21:58:25] OpenRouter embeddings 调用失败: status=502, ...
     * </pre>
     * 最新条目在顶部，截断时丢尾部（最老的条目），所以读日志的人最先看到最近一次的原因。
     */
    private String appendFailureReason(String existing, String newError) {
        String line = "[" + LocalDateTime.now().format(FAILURE_TS_FMT) + "] " +
                (newError == null ? "(no message)" : newError.replaceAll("\\s+", " ").trim());
        String combined = existing == null || existing.isBlank() ? line : line + "\n" + existing;
        return truncate(combined, FAILURE_REASON_MAX_LEN);
    }
}
