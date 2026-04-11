package com.jimeng.dataserver.ai.skill.model;

public class ToolExecutionResult {

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
