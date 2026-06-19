package com.jimeng.dataserver.ai.skill.install;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.ai.skill.imports.GithubSkillSearchService;
import com.jimeng.dataserver.ai.skill.imports.SkillCandidate;
import com.jimeng.dataserver.ai.skill.service.SkillImportService;
import com.jimeng.dataserver.ai.skill.service.SkillToolExecutor;
import com.jimeng.persistence.entity.AiSkill;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SkillInstallToolExecutor implements SkillToolExecutor {

    private final GithubSkillSearchService searchService;
    private final SkillImportService importService;
    private final SkillInstallGuard guard;

    @Override
    public boolean supports(String toolName) {
        return SkillInstallToolDefinitions.SEARCH.equals(toolName)
                || SkillInstallToolDefinitions.INSTALL.equals(toolName);
    }

    @Override
    public Object execute(String toolName, Map<String, Object> input) {
        if (SkillInstallToolDefinitions.SEARCH.equals(toolName)) {
            List<SkillCandidate> candidates = searchService.search(str(input, "keyword"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("candidates", candidates);
            out.put("note", "请把候选列给用户，由用户确认选哪个后再调用 skill.install；不要自行直接安装。");
            return out;
        }
        if (!guard.canInstall()) {
            return Map.of("error", "no_permission", "message", "当前用户无 SKILL 管理权限，无法安装");
        }
        String ref = input.get("ref") == null ? "main" : String.valueOf(input.get("ref"));
        AiSkill s = importService.importFromGithub(
                str(input, "owner"), str(input, "repo"), ref, str(input, "path"),
                TenantContext.get(), AdminRequestContext.findUserIdOrNull());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("installed", Map.of("name", s.getName(), "skill_type", String.valueOf(s.getSkillType())));
        out.put("message", "已安装 skill: " + s.getName() + "，后续对话即可使用。");
        return out;
    }

    private static String str(Map<String, Object> in, String key) {
        Object v = in == null ? null : in.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
