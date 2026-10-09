package com.jimeng.dataserver.ai.skill.model;

import com.jimeng.dataserver.ai.skill.manifest.JmMetadata;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public class SkillPackage implements ToolPackage {

    private final String name;
    private final String description;
    private final String body;
    private final Path rootPath;
    private final List<SkillToolDefinition> tools;
    private final JmMetadata jm;

    public SkillPackage(String name, String description, String body, Path rootPath,
                        List<SkillToolDefinition> tools, JmMetadata jm) {
        this.name = name;
        this.description = description;
        this.body = body;
        this.rootPath = rootPath;
        this.tools = tools == null ? Collections.emptyList() : List.copyOf(tools);
        this.jm = jm == null ? JmMetadata.EMPTY : jm;
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

    /** SKILL.md 的 metadata.jm。 */
    public JmMetadata getJm() {
        return jm;
    }

    @Override
    public SkillRequirement getRequires() {
        return jm.requires();
    }
}
