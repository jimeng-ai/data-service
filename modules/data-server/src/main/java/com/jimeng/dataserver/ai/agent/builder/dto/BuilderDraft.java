package com.jimeng.dataserver.ai.agent.builder.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构建器草稿：与 Agent 配置字段一一对应，外加"推荐插件/KB"。
 * 序列化成 JSON 存 chat_conversation.builder_draft；经 SSE draft-update 推给前端预览。
 * 用 @Data class 而非 record（Jackson 非空集合 record 会 500，见 jm-jackson-no-record-dto）。
 */
@Data
public class BuilderDraft {
    private String name;
    private String description;
    /** 头像生成提示（可选，Plan 内仅透传，暂不生成图）。 */
    private String avatarHint;
    private List<String> presetQuestions;
    private String systemPrompt;
    private String model;
    /** {temperature, maxTokens, topP, ...} 驼峰 key（与前端一致）。 */
    private Map<String, Object> modelParams = new LinkedHashMap<>();
    /** 推荐绑定的插件 id（仅推荐，finalize 时由用户确认）。 */
    private List<Long> recommendedPluginIds;
    /** 推荐绑定的知识库 id（仅推荐）。 */
    private List<Long> recommendedKbIds;
}
