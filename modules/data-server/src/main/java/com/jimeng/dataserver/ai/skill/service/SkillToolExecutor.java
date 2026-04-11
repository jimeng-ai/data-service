package com.jimeng.dataserver.ai.skill.service;

import java.util.Map;

public interface SkillToolExecutor {

    boolean supports(String toolName);

    Object execute(String toolName, Map<String, Object> input);
}
