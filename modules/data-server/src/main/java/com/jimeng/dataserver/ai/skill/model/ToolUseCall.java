package com.jimeng.dataserver.ai.skill.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ToolUseCall {

    private final String toolUseId;
    private final String toolName;
    private final Map<String, Object> input;

    public ToolUseCall(String toolUseId, String toolName, Map<String, Object> input) {
        this.toolUseId = toolUseId;
        this.toolName = toolName;
        this.input = input == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    public String getToolUseId() {
        return toolUseId;
    }

    public String getToolName() {
        return toolName;
    }

    public Map<String, Object> getInput() {
        return input;
    }
}
