package com.jimeng.dataserver.ai.skill.model;

public class ToolExecutionResult {

    /**
     * 工具结果 payload（Map）里的「引用来源」旁路 key 约定。
     *
     * <p>工具若在返回的 payload Map 中放入此 key（值为可序列化的引用列表），
     * {@code AiConversationLoop} 会在回传给模型前把它抽出来、单独发一个 {@code citations} SSE 事件，
     * 并从 payload 中剥离——使富化引用只用于前端「参考来源」展示，不进模型上下文、不计入 token。
     * 目前由 {@code RagSkillToolExecutor}（rag.search）使用。
     */
    public static final String CITATIONS_SIDECAR_KEY = "__citations__";

    private final String toolUseId;
    private final String toolName;
    private final boolean success;
    private final Object payload;

    public ToolExecutionResult(String toolUseId, String toolName, boolean success, Object payload) {
        this.toolUseId = toolUseId;
        this.toolName = toolName;
        this.success = success;
        this.payload = payload;
    }

    public String getToolUseId() {
        return toolUseId;
    }

    public String getToolName() {
        return toolName;
    }

    public boolean isSuccess() {
        return success;
    }

    public Object getPayload() {
        return payload;
    }
}
