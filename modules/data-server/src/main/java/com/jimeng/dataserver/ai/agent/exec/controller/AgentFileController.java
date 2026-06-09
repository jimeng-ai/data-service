package com.jimeng.dataserver.ai.agent.exec.controller;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.dataserver.ai.agent.exec.dto.AgentFileView;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.persistence.entity.AgentArtifact;
import com.jimeng.persistence.entity.AgentExecRun;
import com.jimeng.persistence.entity.AgentInputFile;
import com.jimeng.persistence.mapper.AgentArtifactMapper;
import com.jimeng.persistence.mapper.AgentExecRunMapper;
import com.jimeng.persistence.mapper.AgentInputFileMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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

/**
 * 代码执行 Agent 的输入文件上传 + 产物下载。复用 RagMinioStorageService 存储；产物下载按
 * artifactId 做租户隔离查询后流式回传（不暴露裸 MinIO / presigned URL）。
 */
@Tag(name = "Agent-文件", description = "代码执行 Agent 的输入文件上传 + 产物下载")
@RestController
@RequestMapping("/data/agent")
@RequiredArgsConstructor
public class AgentFileController {

    private final RagMinioStorageService storage;
    private final AgentInputFileMapper inputFileMapper;
    private final AgentArtifactMapper artifactMapper;
    private final AgentExecRunMapper runMapper;
    private final PermissionResolver permissionResolver;

    @Operation(summary = "上传输入文件", description = "上传文件到 MinIO（不入知识库），返回 fileId 供 /data/agent/exec 引用。")
    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AgentFileView upload(@RequestParam("file") MultipartFile file) throws Exception {
        String objectName = storage.upload(file);
        AgentInputFile f = new AgentInputFile();
        f.setTenantId(TenantContext.get());
        f.setBucket(storage.getBucket());
        f.setObjectName(objectName);
        f.setFilename(file.getOriginalFilename());
        f.setContentType(file.getContentType());
        f.setSizeBytes(file.getSize());
        inputFileMapper.insert(f);
        return new AgentFileView(f.getId(), objectName, storage.getBucket(),
                f.getFilename(), file.getContentType(), file.getSize());
    }

    @Operation(summary = "预览输入文件", description = "按 fileId（租户隔离）从 MinIO 流式回传输入文件，Content-Disposition: inline 供前端缩略图/预览。")
    @GetMapping("/files/{fileId}")
    public void previewInputFile(@PathVariable Long fileId, HttpServletResponse response) throws Exception {
        AgentInputFile f = inputFileMapper.selectById(fileId);
        if (f == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "文件不存在");
        }
        // 输入文件「按人私有」：成员只能预览自己上传的（超管放行）。否则同租户可凭 id 越权下载他人文件（IDOR）。
        permissionResolver.assertOwnerOrSuperAdmin(f.getCreateUser());
        response.setContentType(f.getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : f.getContentType());
        String fn = URLEncoder.encode(f.getFilename() == null ? "file" : f.getFilename(),
                StandardCharsets.UTF_8).replace("+", "%20");
        // inline：图片/PDF/文本浏览器可内联预览；前端也可 fetch 取 blob 自行渲染（xlsx/docx）。
        response.setHeader("Content-Disposition", "inline; filename*=UTF-8''" + fn);
        // 必须 void+OutputStream 写出：非 void 返回会被 GlobalResponseHandler 包成 JSON、Content-Type
        // 变 application/json，前端 blob 损坏（图片靠 <img> 嗅探侥幸可用，PDF/Excel 直接乱码）。
        // 详见 jm-binary-download-response-wrapping 记忆。
        writeStream(storage.download(f.getObjectName()), response);
    }

    @Operation(summary = "下载产物文件", description = "按 artifactId（租户隔离）从 MinIO 流式回传产物。")
    @GetMapping("/artifacts/{artifactId}/download")
    public void download(@PathVariable Long artifactId, HttpServletResponse response) throws Exception {
        AgentArtifact a = artifactMapper.selectById(artifactId);
        if (a == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "产物不存在");
        }
        // 产物「按人私有」：artifactId 会随 SSE downloadUrl 暴露，必须挡住同租户凭 id 越权下载他人产物（IDOR）。
        // 产物 create_user 在 OkHttp 回调线程落库、可能为空/失真，故按父运行记录的 user_id 判属主（超管放行）。
        if (!permissionResolver.isSuperAdmin()) {
            AgentExecRun run = a.getRunId() == null ? null : runMapper.selectById(a.getRunId());
            if (run == null || !permissionResolver.currentOwnerId().equals(run.getUserId())) {
                throw new ServiceException(ExceptionCode.NOT_FOUND, "产物不存在");
            }
        }
        response.setContentType(a.getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : a.getContentType());
        String fn = URLEncoder.encode(a.getFilename() == null ? "download" : a.getFilename(),
                StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + fn);
        // 产物与输入文件统一落在 RagMinioStorageService 的 bucket（data-service 通过 payload
        // 告诉边车用同一个 bucket），故这里直接用它下载。void+OutputStream 同上避免 JSON 包装。
        writeStream(storage.download(a.getObjectName()), response);
    }

    /** 把 MinIO 输入流原样写到响应输出流（自动关闭两端）。 */
    private static void writeStream(InputStream in, HttpServletResponse response) throws Exception {
        try (InputStream is = in; OutputStream out = response.getOutputStream()) {
            is.transferTo(out);
            out.flush();
        }
    }
}
