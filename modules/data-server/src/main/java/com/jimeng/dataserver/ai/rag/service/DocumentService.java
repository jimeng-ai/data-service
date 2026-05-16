package com.jimeng.dataserver.ai.rag.service;

import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.rag.model.IngestionMessage;
import com.jimeng.dataserver.ai.rag.model.IngestionStatus;
import com.jimeng.dataserver.ai.rag.service.es.ChunkIndexService;
import com.jimeng.dataserver.ai.rag.service.ingest.IngestionQueueProducer;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.persistence.entity.KbDocument;
import com.jimeng.persistence.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final KbDocumentMapper kbDocumentMapper;
    private final RagMinioStorageService minioStorage;
    private final IngestionQueueProducer ingestionQueueProducer;
    private final ChunkIndexService chunkIndexService;
    private final KnowledgeBaseService knowledgeBaseService;

    public KbDocument upload(Long kbId, MultipartFile file) throws Exception {
        knowledgeBaseService.get(kbId);
        if (file == null || file.isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "上传文件不能为空");
        }
        byte[] bytes = file.getBytes();
        String hash = SecureUtil.sha256(new String(bytes));

        // 幂等：相同 kb 内的同 hash 文件
        //  - 已完成入库（DONE）：直接返回旧记录，跳过整条流水线
        //  - 中间状态（UPLOADED / PARSING / CHUNKING / ... / FAILED）：复用旧记录，但重新入队触发入库
        LambdaQueryWrapper<KbDocument> w = new LambdaQueryWrapper<>();
        w.eq(KbDocument::getKbId, kbId).eq(KbDocument::getFileHash, hash);
        KbDocument existed = kbDocumentMapper.selectOne(w);
        if (existed != null) {
            if (IngestionStatus.DONE.code().equals(existed.getStatus())) {
                log.info("文档幂等命中（已完成）kbId={} hash={} docId={}", kbId, hash, existed.getId());
                return existed;
            }
            log.info("文档存在但状态 {} 未完成，重新入队 kbId={} docId={}",
                    existed.getStatus(), kbId, existed.getId());
            existed.setStatus(IngestionStatus.UPLOADED.code());
            existed.setFailureReason(null);
            kbDocumentMapper.updateById(existed);
            ingestionQueueProducer.publish(new IngestionMessage(existed.getId(), kbId, null));
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
        doc.setStatus(IngestionStatus.UPLOADED.code());
        kbDocumentMapper.insert(doc);

        ingestionQueueProducer.publish(new IngestionMessage(doc.getId(), kbId, null));
        return doc;
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
        kbDocumentMapper.deleteById(docId);
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
        ingestionQueueProducer.publish(new IngestionMessage(doc.getId(), doc.getKbId(), null));
        return doc;
    }

    private String detectSourceType(String filename) {
        if (filename == null) return "unknown";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) return "docx";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "md";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "html";
        if (lower.endsWith(".txt")) return "txt";
        return "unknown";
    }
}
