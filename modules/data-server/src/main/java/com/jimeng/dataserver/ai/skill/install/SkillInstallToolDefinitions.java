package com.jimeng.dataserver.ai.skill.install;

import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SkillInstallToolDefinitions {
    private SkillInstallToolDefinitions() {}

    // 工具名只用 [a-zA-Z0-9_-]：含「.」会被协议适配器规整成「_」下发给模型，
    // 而执行器按原名匹配 → 模型调 skill_search 却 tool_not_supported。故直接用下划线名。
    public static final String SEARCH = "skill_search";
    public static final String INSTALL = "skill_install";
    public static final SkillToolDefinition SEARCH_DEF = buildSearch();
    public static final SkillToolDefinition INSTALL_DEF = buildInstall();

    private static SkillToolDefinition buildSearch() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("keyword", prop("string", "要查找的 skill 关键词，如 pdf / 表格 / 翻译"));
        return new SkillToolDefinition(SEARCH,
                "在 GitHub 上搜索可安装的 Agent Skill（按 SKILL.md）。返回候选列表(owner/repo/path/stars/curated)。"
                        + "搜到后必须把候选列给用户、由用户确认选哪个，再调用 skill_install，不要自行直接安装。",
                objectSchema(props, List.of("keyword")));
    }

    private static SkillToolDefinition buildInstall() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("owner", prop("string", "GitHub owner，取自 skill_search 候选"));
        props.put("repo", prop("string", "GitHub repo，取自候选"));
        props.put("ref", prop("string", "分支/标签，取自候选的 ref，默认 main"));
        props.put("path", prop("string", "skill 子目录，取自候选 path"));
        return new SkillToolDefinition(INSTALL,
                "把用户已确认选定的某个候选 skill 安装进当前租户（私有）。仅在用户明确选定后调用。",
                objectSchema(props, List.of("owner", "repo", "path")));
    }

    private static Map<String, Object> prop(String type, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", desc);
        return m;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> props, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", props);
        s.put("required", required);
        return s;
    }
}
