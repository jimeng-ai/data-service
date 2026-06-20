package com.jimeng.dataserver.ai.skill.controller;

import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.controller.dto.SkillDetailView;
import com.jimeng.dataserver.ai.skill.controller.dto.SkillImportRequest;
import com.jimeng.dataserver.ai.skill.controller.dto.SkillView;
import com.jimeng.dataserver.ai.skill.service.SkillBundleReadService;
import com.jimeng.dataserver.ai.skill.service.SkillImportService;
import com.jimeng.dataserver.ai.skill.service.SkillTenantService;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.entity.SysUser;
import com.jimeng.persistence.mapper.SysUserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Tag(name = "租户Skill", description = "租户私有 skill 管理")
@RestController
@RequestMapping("/data/tenant/skills")
@RequiredArgsConstructor
public class SkillTenantController {

    private final SkillTenantService skillTenantService;
    private final SkillImportService skillImportService;
    private final SkillBundleReadService skillBundleReadService;
    private final SysUserMapper sysUserMapper;

    @Operation(summary = "从 GitHub 导入 skill")
    @PostMapping("/import")
    public SkillView importFromGithub(@RequestBody SkillImportRequest req) {
        AiSkill s = skillImportService.importFromGithub(
                req.getOwner(), req.getRepo(), req.getRef(), req.getPath(),
                AdminRequestContext.requireTenantId(), AdminRequestContext.requireUserId());
        return SkillView.of(s);
    }

    @Operation(summary = "上传 SKILL.md 创建私有 skill")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SkillView upload(@RequestParam("file") MultipartFile file) throws IOException {
        String raw = new String(file.getBytes(), StandardCharsets.UTF_8);
        AiSkill s = skillTenantService.createFromMarkdown(
                raw, AdminRequestContext.requireTenantId(), AdminRequestContext.requireUserId());
        return SkillView.of(s);
    }

    @Operation(summary = "skill 列表")
    @GetMapping
    public List<SkillView> list(@RequestParam(defaultValue = "false") boolean mine) {
        List<AiSkill> rows = skillTenantService.list(
                AdminRequestContext.requireTenantId(), AdminRequestContext.requireUserId(), mine);
        return rows.stream().map(SkillView::of).toList();
    }

    @Operation(summary = "skill 详情（含 SKILL.md 正文 + DOER 脚本文件）")
    @GetMapping("/{id}")
    public SkillDetailView get(@PathVariable Long id) {
        AiSkill s = skillTenantService.get(
                id, AdminRequestContext.requireTenantId(), AdminRequestContext.requireUserId());
        return SkillDetailView.of(s, skillBundleReadService.read(s.getBundleKey()), resolveOwnerName(s.getOwnerUserId()));
    }

    /** 把创建者 userId 解析成显示名（displayName 优先，回退 username）；查不到返回 null。
     *  sys_user 非租户隔离表，按主键直查即可；调用方已能看到该 skill，不存在越权暴露。 */
    private String resolveOwnerName(Long ownerUserId) {
        if (ownerUserId == null) {
            return null;
        }
        SysUser owner = sysUserMapper.selectById(ownerUserId);
        if (owner == null) {
            return null;
        }
        String display = owner.getDisplayName();
        return (display != null && !display.isBlank()) ? display : owner.getUsername();
    }

    @Operation(summary = "共享给团队")
    @PostMapping("/{id}/share")
    public void share(@PathVariable Long id) {
        skillTenantService.setScope(id, SkillConst.SCOPE_TENANT, AdminRequestContext.requireUserId());
    }

    @Operation(summary = "取消共享")
    @PostMapping("/{id}/unshare")
    public void unshare(@PathVariable Long id) {
        skillTenantService.setScope(id, SkillConst.SCOPE_PRIVATE, AdminRequestContext.requireUserId());
    }

    @Operation(summary = "启用")
    @PostMapping("/{id}/enable")
    public void enable(@PathVariable Long id) {
        skillTenantService.setStatus(id, SkillConst.STATUS_ACTIVE, AdminRequestContext.requireUserId());
    }

    @Operation(summary = "停用")
    @PostMapping("/{id}/disable")
    public void disable(@PathVariable Long id) {
        skillTenantService.setStatus(id, SkillConst.STATUS_DISABLED, AdminRequestContext.requireUserId());
    }

    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        skillTenantService.delete(id, AdminRequestContext.requireUserId());
    }
}
