package com.jimeng.dataserver.ai.rag.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.rag.model.IngestionMessage;
import com.jimeng.dataserver.ai.rag.model.IngestionStatus;
import com.jimeng.dataserver.ai.rag.service.es.ChunkIndexService;
import com.jimeng.dataserver.ai.rag.service.ingest.IngestionQueueProducer;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.persistence.entity.KbChunk;
import com.jimeng.persistence.entity.KbDocument;
import com.jimeng.persistence.mapper.KbChunkMapper;
import com.jimeng.persistence.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final KbDocumentMapper kbDocumentMapper;
    private final KbChunkMapper kbChunkMapper;
    private final RagMinioStorageService minioStorage;
    private final IngestionQueueProducer ingestionQueueProducer;
    private final ChunkIndexService chunkIndexService;
    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 上传文件到知识库，仅存储（MinIO + kb_document），状态置 STAGED（待确认），
     * 不发 RabbitMQ 消息、不触发入库流水线。需用户随后调 {@link #confirm} 才进入处理。
     * 逐行切片（rowPerChunk）在确认时统一决定，故上传不接收该参数。
     */
    public KbDocument upload(Long kbId, MultipartFile file) throws Exception {
        knowledgeBaseService.get(kbId);
        if (file == null || file.isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "上传文件不能为空");
        }
        byte[] bytes = file.getBytes();
        long fileSize = file.getSize();
        // 直接对原始字节算 sha256（不要先 new String(bytes)：二进制文件经默认字符集解码会把非法字节塌缩成 U+FFFD，
        // 既增加碰撞风险又依赖 JVM 默认编码、跨环境不稳）。
        String hash = DigestUtil.sha256Hex(bytes);

        // 幂等：相同 kb 内的同 hash 文件
        //  - 已完成入库（DONE）：直接返回旧记录，跳过整条流水线
        //  - 其它状态（STAGED / PARSING / ... / FAILED）：复用旧记录，重置为 STAGED 等待用户再次确认（不入队）
        LambdaQueryWrapper<KbDocument> w = new LambdaQueryWrapper<>();
        w.eq(KbDocument::getKbId, kbId).eq(KbDocument::getFileHash, hash);
        KbDocument existed = kbDocumentMapper.selectOne(w);
        if (existed != null) {
            if (IngestionStatus.DONE.code().equals(existed.getStatus())) {
                log.info("文档幂等命中（已完成）kbId={} hash={} docId={}", kbId, hash, existed.getId());
                return existed;
            }
            log.info("文档存在但状态 {} 未完成，重置为待确认 kbId={} docId={}",
                    existed.getStatus(), kbId, existed.getId());
            existed.setStatus(IngestionStatus.STAGED.code());
            existed.setFailureReason(null);
            if (existed.getFileSize() == null) {
                existed.setFileSize(fileSize); // 旧记录缺大小则补上
            }
            kbDocumentMapper.updateById(existed);
            return existed;
        }

        String objectName = minioStorage.upload(file);

        KbDocument doc = new KbDocument();
        doc.setKbId(kbId);
        doc.setTitle(file.getOriginalFilename());
        doc.setSourceType(detectSourceType(file.getOriginalFilename()));
        doc.setMinioBucket(minioStorage.getBucket());
        doc.setMinioObject(objectName);
        doc.setFileHash(hash);
        doc.setFileSize(fileSize);
        doc.setRowPerChunk(false); // 默认值；真正生效的逐行切片在 confirm 时按本批勾选统一写入
        doc.setStatus(IngestionStatus.STAGED.code());
        kbDocumentMapper.insert(doc);
        return doc;
    }

    /**
     * 确认入库：把本知识库下所有「待确认（STAGED）」文档推进到入库流水线。
     * 给每篇写入本次勾选的 rowPerChunk（仅 xlsx/csv 实际生效）、状态置 UPLOADED、各发一条 RabbitMQ 消息。
     * @return 被确认入队的文档列表（无待确认文件时为空列表）
     */
    public List<KbDocument> confirm(Long kbId, boolean rowPerChunk) {
        knowledgeBaseService.get(kbId);
        LambdaQueryWrapper<KbDocument> w = new LambdaQueryWrapper<>();
        w.eq(KbDocument::getKbId, kbId).eq(KbDocument::getStatus, IngestionStatus.STAGED.code());
        List<KbDocument> staged = kbDocumentMapper.selectList(w);
        for (KbDocument doc : staged) {
            doc.setStatus(IngestionStatus.UPLOADED.code());
            doc.setFailureReason(null);
            doc.setRowPerChunk(rowPerChunk);
            kbDocumentMapper.updateById(doc);
            ingestionQueueProducer.publish(new IngestionMessage(doc.getId(), kbId, null, TenantContext.get()));
        }
        log.info("确认入库 kbId={} rowPerChunk={} 入队 {} 篇", kbId, rowPerChunk, staged.size());
        return staged;
    }

    public KbDocument get(Long docId) {
        KbDocument doc = kbDocumentMapper.selectById(docId);
        if (doc == null) throw new ServiceException(ExceptionCode.INVALID_REQUEST, "kb_document 不存在: " + docId);
        return doc;
    }

    public List<KbDocument> listByKb(Long kbId) {
        LambdaQueryWrapper<KbDocument> w = new LambdaQueryWrapper<>();
        w.eq(KbDocument::getKbId, kbId).orderByDesc(KbDocument::getCreateTime);
        return kbDocumentMapper.selectList(w);
    }

    /** 列出某文档切片（按切片序号升序），供前端「查看切片」。 */
    public List<KbChunk> listChunks(Long docId) {
        LambdaQueryWrapper<KbChunk> w = new LambdaQueryWrapper<>();
        w.eq(KbChunk::getDocId, docId).orderByAsc(KbChunk::getChunkIndex);
        return kbChunkMapper.selectList(w);
    }

    /** 打开原始文件流用于预览/下载；调用方负责关闭返回的输入流。 */
    public PreviewStream openPreview(KbDocument doc) throws Exception {
        if (doc.getMinioObject() == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "文档无原始文件，无法预览");
        }
        InputStream stream = minioStorage.download(doc.getMinioObject());
        return new PreviewStream(contentType(doc.getTitle()), stream);
    }

    /** 预览文件流 + 其 MIME 类型。 */
    public record PreviewStream(String contentType, InputStream stream) {}

    /** 按文件名后缀推断浏览器内联预览所需的 MIME 类型；未知则二进制流（触发下载）。 */
    static String contentType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown; charset=utf-8";
        if (lower.endsWith(".csv")) return "text/csv; charset=utf-8";
        if (lower.endsWith(".txt") || lower.endsWith(".tsv")) return "text/plain; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsm")) return "application/vnd.ms-excel";
        return "application/octet-stream";
    }

    public void delete(Long docId) throws Exception {
        KbDocument doc = get(docId);
        chunkIndexService.deleteByDoc(docId);
        if (doc.getMinioObject() != null) {
            try {
                minioStorage.delete(doc.getMinioObject());
            } catch (Exception e) {
                log.warn("MinIO 删除失败 obj={}: {}", doc.getMinioObject(), e.getMessage());
            }
        }
        // 物理删（非逻辑删）：否则软删行仍占 uk_kb_hash 唯一键，重传同文件会撞 Duplicate entry。
        kbDocumentMapper.physicalDeleteById(docId);
    }

    public KbDocument retry(Long docId) {
        KbDocument doc = get(docId);
        // 黑名单：只有已完成的文档不允许 retry；其他状态（FAILED、卡死的中间状态）都允许重新入库
        if (IngestionStatus.DONE.code().equals(doc.getStatus())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "文档已完成入库，无需重试");
        }
        doc.setStatus(IngestionStatus.UPLOADED.code());
        doc.setFailureReason(null);
        kbDocumentMapper.updateById(doc);
        ingestionQueueProducer.publish(new IngestionMessage(doc.getId(), doc.getKbId(), null, TenantContext.get()));
        return doc;
    }

    private String detectSourceType(String filename) {
        if (filename == null) return "unknown";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) return "docx";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "md";
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".xlsm")) return "xlsx";
        if (lower.endsWith(".csv") || lower.endsWith(".tsv")) return "csv";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "html";
        if (lower.endsWith(".txt")) return "txt";
        return "unknown";
    }
}
