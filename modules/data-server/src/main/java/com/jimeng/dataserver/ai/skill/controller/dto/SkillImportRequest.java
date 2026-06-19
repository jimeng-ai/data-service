package com.jimeng.dataserver.ai.skill.controller.dto;

import lombok.Data;

@Data
public class SkillImportRequest {
    private String owner;
    private String repo;
    private String ref = "main";
    private String path;
}
