package com.jimeng.dataserver.ai.skill.controller;

import com.jimeng.dataserver.ai.skill.service.SkillPackageLoaderService;
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
        return skillPackageLoaderService.uploadSkillMarkdown(file, overwrite);
    }
}
