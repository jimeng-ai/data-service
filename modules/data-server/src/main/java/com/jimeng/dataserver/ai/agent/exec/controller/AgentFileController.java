package com.jimeng.dataserver.ai.agent.exec.controller;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.agent.exec.dto.AgentFileView;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.persistence.entity.AgentArtifact;
import com.jimeng.persistence.entity.AgentInputFile;
import com.jimeng.persistence.mapper.AgentArtifactMapper;
import com.jimeng.persistence.mapper.AgentInputFileMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
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

    @Operation(summary = "下载产物文件", description = "按 artifactId（租户隔离）从 MinIO 流式回传产物。")
    @GetMapping("/artifacts/{artifactId}/download")
    public Resource download(@PathVariable Long artifactId, HttpServletResponse response) throws Exception {
        AgentArtifact a = artifactMapper.selectById(artifactId);
        if (a == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "产物不存在");
        }
        response.setContentType(a.getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : a.getContentType());
        String fn = URLEncoder.encode(a.getFilename() == null ? "download" : a.getFilename(),
                StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + fn);
        // 产物与输入文件统一落在 RagMinioStorageService 的 bucket（data-service 通过 payload
        // 告诉边车用同一个 bucket），故这里直接用它下载。
        InputStream is = storage.download(a.getObjectName());
        return new InputStreamResource(is);
    }
}
