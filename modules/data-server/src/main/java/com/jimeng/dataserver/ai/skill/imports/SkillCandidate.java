package com.jimeng.dataserver.ai.skill.imports;

import lombok.Data;

@Data
public class SkillCandidate {
    private String owner;
    private String repo;
    private String ref;
    private String path;
    private String description;
    private Integer stars;
    private boolean curated;
}
