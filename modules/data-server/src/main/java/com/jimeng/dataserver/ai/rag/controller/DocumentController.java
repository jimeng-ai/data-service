package com.jimeng.dataserver.ai.rag.controller;

import com.jimeng.dataserver.ai.rag.service.DocumentService;
import com.jimeng.persistence.entity.KbDocument;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "RAG-文档管理", description = "知识库内文档的上传、查询、删除、重试入库")
@RestController
@RequestMapping("/data/rag")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @Operation(summary = "上传文档到知识库", description = "将文件上传到指定知识库并触发异步入库流程（解析 → 切片 → 上下文化 → 向量化 → 入 ES）")
    @PostMapping("/kb/{kbId}/documents")
    public KbDocument upload(@Parameter(description = "知识库 ID") @PathVariable Long kbId,
                             @Parameter(description = "上传的文档文件，支持 pdf/docx/xlsx/md 等") @RequestParam("file") MultipartFile file) throws Exception {
        return documentService.upload(kbId, file);
    }

    @Operation(summary = "查询知识库内文档列表", description = "返回指定知识库下所有文档及各自的入库状态")
    @GetMapping("/kb/{kbId}/documents")
    public List<KbDocument> listByKb(@Parameter(description = "知识库 ID") @PathVariable Long kbId) {
        return documentService.listByKb(kbId);
    }

    @Operation(summary = "获取文档详情", description = "按文档 ID 查询单个文档的元信息与入库状态")
    @GetMapping("/documents/{docId}")
    public KbDocument get(@Parameter(description = "文档 ID") @PathVariable Long docId) {
        return documentService.get(docId);
    }

    @Operation(summary = "删除文档", description = "删除文档记录，同步清理 MinIO 文件与 ES 中的 chunk 索引")
    @DeleteMapping("/documents/{docId}")
    public void delete(@Parameter(description = "文档 ID") @PathVariable Long docId) throws Exception {
        documentService.delete(docId);
    }

    @Operation(summary = "重新触发入库", description = "对入库失败（FAILED）的文档重新投递到 RabbitMQ 队列，再走一次完整入库流程")
    @PostMapping("/documents/{docId}/retry")
    public KbDocument retry(@Parameter(description = "文档 ID") @PathVariable Long docId) {
        return documentService.retry(docId);
    }
}
