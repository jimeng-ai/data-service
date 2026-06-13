package com.jimeng.dataserver.ai.rag.controller;

import com.jimeng.dataserver.ai.rag.service.DocumentService;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.persistence.entity.KbChunk;
import com.jimeng.persistence.entity.KbDocument;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Tag(name = "RAG-文档管理", description = "知识库内文档的上传、查询、删除、重试入库")
@RestController
@RequestMapping("/data/rag")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final PermissionResolver permissionResolver;

    @Operation(summary = "上传文档到知识库", description = "将文件上传到指定知识库并触发异步入库流程（解析 → 切片 → 上下文化 → 向量化 → 入 ES）")
    @PostMapping(value = "/kb/{kbId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KbDocument upload(@Parameter(description = "知识库 ID") @PathVariable Long kbId,
                             @Parameter(description = "上传的文档文件，支持 pdf/docx/xlsx/md 等") @RequestParam("file") MultipartFile file,
                             @Parameter(description = "表格逐行切片：true=Excel/CSV 每数据行独立成 chunk（FAQ 表用），仅对 xlsx/csv 生效")
                             @RequestParam(value = "rowPerChunk", defaultValue = "false") boolean rowPerChunk) throws Exception {
        permissionResolver.assertCurrentAccess(ResourceType.KNOWLEDGE_BASE, kbId);
        return documentService.upload(kbId, file, rowPerChunk);
    }

    @Operation(summary = "查询知识库内文档列表", description = "返回指定知识库下所有文档及各自的入库状态")
    @GetMapping("/kb/{kbId}/documents")
    public List<KbDocument> listByKb(@Parameter(description = "知识库 ID") @PathVariable Long kbId) {
        permissionResolver.assertCurrentAccess(ResourceType.KNOWLEDGE_BASE, kbId);
        return documentService.listByKb(kbId);
    }

    @Operation(summary = "获取文档详情", description = "按文档 ID 查询单个文档的元信息与入库状态")
    @GetMapping("/documents/{docId}")
    public KbDocument get(@Parameter(description = "文档 ID") @PathVariable Long docId) {
        // 文档以 docId 直达，须反查其所属知识库再做实例级鉴权（防止凭 docId 越权读他人知识库文档）。
        KbDocument doc = documentService.get(docId);
        permissionResolver.assertCurrentAccess(ResourceType.KNOWLEDGE_BASE, doc.getKbId());
        return doc;
    }

    @Operation(summary = "查看文档切片", description = "返回指定文档入库后产生的全部切片（按切片序号升序），含切片文本、上下文化文本、标题路径、页码、token 数")
    @GetMapping("/documents/{docId}/chunks")
    public List<KbChunk> chunks(@Parameter(description = "文档 ID") @PathVariable Long docId) {
        permissionResolver.assertCurrentAccess(ResourceType.KNOWLEDGE_BASE, documentService.get(docId).getKbId());
        return documentService.listChunks(docId);
    }

    @Operation(summary = "预览/下载原始文件", description = "以内联方式返回文档的原始上传文件流，供前端预览（PDF/图片/文本等）或下载")
    @GetMapping("/documents/{docId}/preview")
    public void preview(@Parameter(description = "文档 ID") @PathVariable Long docId,
                        HttpServletResponse response) throws Exception {
        KbDocument doc = documentService.get(docId);
        permissionResolver.assertCurrentAccess(ResourceType.KNOWLEDGE_BASE, doc.getKbId());
        DocumentService.PreviewStream preview = documentService.openPreview(doc);
        response.setContentType(preview.contentType());
        String filename = doc.getTitle() == null ? "file" : doc.getTitle();
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        // inline：可预览类型浏览器内联渲染，不可预览类型由前端自行触发下载
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded);
        // 关键：直接写出字节流并返回 void。带 HttpServletResponse 参数 + void 返回，Spring 视请求已处理，
        // 不再走 GlobalResponseHandler(ResponseBodyAdvice)，避免二进制被包装成 JSON（否则 Content-Type
        // 会变成 application/json、前端拿到的 blob 即损坏 → PDF/Excel 乱码）。
        try (InputStream in = preview.stream(); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
            out.flush();
        }
    }

    @Operation(summary = "删除文档", description = "删除文档记录，同步清理 MinIO 文件与 ES 中的 chunk 索引")
    @DeleteMapping("/documents/{docId}")
    public void delete(@Parameter(description = "文档 ID") @PathVariable Long docId) throws Exception {
        permissionResolver.assertCurrentAccess(ResourceType.KNOWLEDGE_BASE, documentService.get(docId).getKbId());
        documentService.delete(docId);
    }

    @Operation(summary = "重新触发入库", description = "对入库失败（FAILED）的文档重新投递到 RabbitMQ 队列，再走一次完整入库流程")
    @PostMapping("/documents/{docId}/retry")
    public KbDocument retry(@Parameter(description = "文档 ID") @PathVariable Long docId) {
        permissionResolver.assertCurrentAccess(ResourceType.KNOWLEDGE_BASE, documentService.get(docId).getKbId());
        return documentService.retry(docId);
    }
}
