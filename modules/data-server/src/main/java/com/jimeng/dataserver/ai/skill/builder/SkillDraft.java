package com.jimeng.dataserver.ai.skill.builder;

import lombok.Data;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class SkillDraft {
    private String name;
    private String description;
    private String body;
    private String skillType;
    private Map<String, String> files = new LinkedHashMap<>();
}
