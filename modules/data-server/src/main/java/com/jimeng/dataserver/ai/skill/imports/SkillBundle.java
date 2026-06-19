package com.jimeng.dataserver.ai.skill.imports;

import java.util.Map;

public record SkillBundle(String skillMarkdown, Map<String, byte[]> files) {}
