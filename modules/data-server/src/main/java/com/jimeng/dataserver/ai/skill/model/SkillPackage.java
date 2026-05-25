package com.jimeng.dataserver.ai.skill.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public class SkillPackage implements ToolPackage {

    private final String name;
    private final String description;
    private final String body;
    private final Path rootPath;
    private final List<SkillToolDefinition> tools;

    public SkillPackage(String name, String description, String body, Path rootPath, List<SkillToolDefinition> tools) {
        this.name = name;
        this.description = description;
        this.body = body;
        this.rootPath = rootPath;
        this.tools = tools == null ? Collections.emptyList() : List.copyOf(tools);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getBody() {
        return body;
    }

    public Path getRootPath() {
        return rootPath;
    }

    public List<SkillToolDefinition> getTools() {
        return tools;
    }
}
