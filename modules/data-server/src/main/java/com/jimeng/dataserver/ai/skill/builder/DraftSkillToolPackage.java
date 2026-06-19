package com.jimeng.dataserver.ai.skill.builder;

import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 构建器专用工具 draft_skill：据对话增量写"正在设计的 skill"草稿。仅 __skill_builder_mode__ 注入。 */
@Component
public class DraftSkillToolPackage implements ToolPackage {
    public static final String TOOL_NAME = "draft_skill";
    @Override public String getName() { return TOOL_NAME; }
    @Override public String getDescription() {
        return "增量更新正在设计的 skill 草稿。每当从用户处获得新信息（用途、触发场景、步骤、是否需要脚本等），"
             + "就调用本工具把对应字段写入草稿；只传本轮有变化的字段。";
    }
    @Override public String getBody() {
        return """
            把对话中获得的信息写进 draft_skill；各字段要求：
            - name：kebab-case、字母开头、≤64；体现用途（如 csv-to-xlsx）。
            - description：一句话说明用途 + 触发场景（写好触发词，利于被发现）。
            - body：SKILL.md 正文，写成可直接用的操作指引（步骤/输入输出/约束）。
            - skillType：纯指引→PROMPT；需要跑脚本处理文件→DOER。
            - files：仅 DOER 时给出，path→content（如 scripts/run.py 的完整可运行代码）。
            生成 DOER 时脚本要自包含、可在沙箱用常见依赖运行；建议先让用户给样例输入以便试跑。
            """;
    }
    @Override public List<SkillToolDefinition> getTools() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", str("skill 名 kebab-case"));
        props.put("description", str("一句话用途 + 触发场景"));
        props.put("body", str("SKILL.md 正文"));
        props.put("skillType", str("PROMPT 或 DOER"));
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("type", "object");
        files.put("description", "DOER 文件树：相对路径 -> 文件内容");
        files.put("additionalProperties", Map.of("type", "string"));
        props.put("files", files);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        return List.of(new SkillToolDefinition(TOOL_NAME, "更新 skill 草稿", schema));
    }
    private static Map<String, Object> str(String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string"); m.put("description", desc);
        return m;
    }
}
