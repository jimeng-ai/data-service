package com.jimeng.dataserver.ai.rag.controller;

import com.jimeng.dataserver.ai.rag.service.KnowledgeBaseService;
import com.jimeng.dataserver.admin.common.UserNameResolver;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.persistence.entity.KnowledgeBase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "RAG-知识库管理", description = "知识库的创建、查询、删除")
@RestController
@RequestMapping("/data/rag/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;
    private final PermissionResolver permissionResolver;
    private final UserNameResolver userNameResolver;

    @Operation(summary = "创建知识库", description = "新建一个知识库；后续可向该知识库下上传文档")
    @PostMapping
    public KnowledgeBase create(@RequestBody CreateKbRequest req) {
        return kbService.create(req.getName(), req.getDescription());
    }

    @Operation(summary = "查询知识库列表", description = "返回当前租户的知识库（成员仅见被授权的）")
    @GetMapping
    public List<KnowledgeBase> list() {
        List<KnowledgeBase> kbs = permissionResolver.filterCurrent(
                kbService.list(), ResourceType.KNOWLEDGE_BASE, KnowledgeBase::getId, KnowledgeBase::getCreateUser);
        userNameResolver.fillCreatorNames(kbs); // 回填创建人显示名
        return kbs;
    }

    @Operation(summary = "获取知识库详情", description = "按 ID 查询单个知识库的元信息")
    @GetMapping("/{id}")
    public KnowledgeBase get(@Parameter(description = "知识库 ID") @PathVariable Long id) {
        permissionResolver.assertCurrentAccess(ResourceType.KNOWLEDGE_BASE, id);
        return kbService.get(id);
    }

    @Operation(summary = "删除知识库", description = "删除知识库及其下所有文档（同步清理 MinIO 文件与 ES 索引）")
    @DeleteMapping("/{id}")
    public void delete(@Parameter(description = "知识库 ID") @PathVariable Long id) throws Exception {
        permissionResolver.assertCurrentAccess(ResourceType.KNOWLEDGE_BASE, id);
        kbService.delete(id);
    }

    @Schema(description = "创建知识库请求体")
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CreateKbRequest {
        @Schema(description = "知识库名称", example = "高德 POI 文档库")
        private String name;
        @Schema(description = "知识库描述（可选）", example = "存放高德 POI 分类与编码相关文档")
        private String description;
    }
}
