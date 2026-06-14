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
        return "你是「Agent 构建器」。通过多轮对话帮助用户设计一个 Agent，并用 draft_agent 工具逐步把配置写入草稿：\n"
                + "- 主动追问关键信息：这个 Agent 面向谁、要完成什么任务、语气风格、是否需要联网/插件、是否需要企业知识库。\n"
                + "- 每获得一项信息就调用 draft_agent 更新对应字段（name/description/systemPrompt/model/modelParams/presetQuestions）。\n"
                + "- systemPrompt 要写成完整、可直接使用的人设提示词（角色、能力边界、语气、输出要求）。\n"
                + "- model 只能从下方「可选模型目录」里挑；按用途选型（复杂推理选 Opus、日常均衡选 Sonnet、高频低成本选 Haiku/mini）。\n"
                + "- 若下方「可用插件/知识库目录」里有契合用途的项，用 recommendedPluginIds/recommendedKbIds 推荐其 id（仅推荐，用户最终确认）。\n"
                + "- 信息足够时，用自然语言向用户小结当前草稿，并提示可以点「创建 Agent」。";
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
