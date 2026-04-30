package com.jimeng.dataserver.ai.skill.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class SkillToolDefinition {

    private final String name;
    private final String modelName;
    private final String description;
    private final Map<String, Object> inputSchema;

    public SkillToolDefinition(String name, String description, Map<String, Object> inputSchema) {
        this.name = name;
        this.modelName = normalizeModelName(name);
        this.description = description;
        this.inputSchema = inputSchema == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getModelName() {
        return modelName;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    public Map<String, Object> toClaudeTool() {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", modelName);
        tool.put("description", description);
        tool.put("input_schema", inputSchema);
        return tool;
    }

    public Map<String, Object> toOpenAiTool() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", modelName);
        function.put("description", description);
        function.put("parameters", inputSchema);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    private String normalizeModelName(String source) {
        if (source == null) {
            return "skill_tool";
        }
        StringBuilder sb = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || c == '-';
            sb.append(valid ? c : '_');
        }
        String normalized = sb.toString();
        if (normalized.isBlank()) {
            normalized = "skill_tool";
        }
        if (normalized.length() > 128) {
            normalized = normalized.substring(0, 128);
        }
        return normalized;
    }
}
