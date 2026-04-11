package com.jimeng.dataserver.ai.skill.controller;

import com.jimeng.dataserver.ai.skill.service.SkillPackageLoaderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
