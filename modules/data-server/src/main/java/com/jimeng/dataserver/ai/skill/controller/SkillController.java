package com.jimeng.dataserver.ai.skill.controller;

import com.jimeng.dataserver.ai.skill.service.SkillPackageLoaderService;
import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Tag(name = "Skill管理", description = "Skill包管理接口")
@RestController
@RequestMapping("/data/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillPackageLoaderService skillPackageLoaderService;
    private final SuperAdminGuard superAdminGuard;

    @Operation(summary = "获取skill列表", description = "读取本地skills目录，返回skill元信息")
    @GetMapping
    public List<Map<String, Object>> listSkills() {
        return skillPackageLoaderService.listSkillSummaries();
    }

    @Operation(summary = "上传SKILL.md创建skill", description = "上传本地SKILL.md文件并写入skills目录，使其加入skill体系")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadSkill(
            @Parameter(description = "SKILL.md文件") @RequestParam("file") MultipartFile file,
            @Parameter(description = "同名skill存在时是否覆盖") @RequestParam(defaultValue = "false") boolean overwrite) {
        // Skill 落地到服务器 skills 目录并对全租户生效，属平台级写操作，仅限企业超管。
        superAdminGuard.requireSuperAdmin();
        return skillPackageLoaderService.uploadSkillMarkdown(file, overwrite);
    }
}
