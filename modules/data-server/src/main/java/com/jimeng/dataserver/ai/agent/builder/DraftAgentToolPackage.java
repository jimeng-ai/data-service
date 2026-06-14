package com.jimeng.dataserver.ai.agent.builder;

import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构建器专用工具 draft_agent：模型据对话进展增量更新"正在设计的 Agent 配置"。
 * 仅在构建器会话注入（见 SkillRuntimeService 的 __agent_builder_mode__ 短路），不对普通对话可见。
 */
@Component
public class DraftAgentToolPackage implements ToolPackage {

    public static final String TOOL_NAME = "draft_agent";

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "增量更新正在设计的 Agent 配置草稿。每当从用户处获得新信息（用途、语气、模型偏好、想用的插件/知识库等），"
                + "就调用本工具把对应字段写入草稿。只传本次有变化的字段，无需重复未变字段。";
    }

    @Override
    public String getBody() {
        return """
                每当从对话中获得可写入配置的信息，就调用 draft_agent 把它写进草稿；只传本轮有变化的字段，不要重复未变字段。各字段的质量要求：

                - name：简短、能体现用途（如「售后退货客服」）。
                - description：一句话说明这个 Agent 帮谁做什么。
                - systemPrompt：给「正在被设计的那个 Agent」用的完整人设提示词，用第二人称「你是…」书写，建议涵盖：角色定位、能力范围、语气风格、关键约束/拒答规则、输出格式要求。要写成可直接上线的成品，而不是泛泛而谈。
                - model：必须取自下方「可选模型目录」的 value；按任务选型——复杂推理/长文档选 Opus，日常均衡选 Sonnet，高频简单选 Haiku。
                - presetQuestions：3~4 个贴合该 Agent 用途、站在终端用户视角提出的引导问句。
                - modelParams：仅在用户有明确偏好时设置（如要更稳定就调低 temperature），否则留空用默认。
                - recommendedPluginIds / recommendedKbIds：仅当下方目录里有契合用途的项时，按其 id 推荐；这只是建议，最终由用户在右侧勾选确认，别假定一定会绑定。

                不要推荐目录里不存在的模型、插件或知识库。""";
    }

    @Override
    public List<SkillToolDefinition> getTools() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", strProp("Agent 展示名"));
        props.put("description", strProp("一句话描述这个 Agent 的用途"));
        props.put("systemPrompt", strProp("完整的人设/系统提示词"));
        props.put("model", strProp("模型 id，必须取自可选模型目录的 value"));
        props.put("presetQuestions", arrProp("对话空状态的引导问题", "string"));
        props.put("modelParams", objProp("模型参数 {temperature, maxTokens, topP}"));
        props.put("recommendedPluginIds", arrProp("推荐绑定的插件 id（取自可用插件目录）", "integer"));
        props.put("recommendedKbIds", arrProp("推荐绑定的知识库 id（取自知识库目录）", "integer"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        // 全可选：增量 patch。
        return List.of(new SkillToolDefinition(TOOL_NAME, getDescription(), schema));
    }

    private Map<String, Object> strProp(String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string");
        m.put("description", desc);
        return m;
    }

    private Map<String, Object> objProp(String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("description", desc);
        return m;
    }

    private Map<String, Object> arrProp(String desc, String itemType) {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", itemType);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "array");
        m.put("description", desc);
        m.put("items", items);
        return m;
    }
}
