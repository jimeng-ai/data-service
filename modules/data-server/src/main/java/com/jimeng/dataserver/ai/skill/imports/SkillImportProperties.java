package com.jimeng.dataserver.ai.skill.imports;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "skill.import")
public class SkillImportProperties {
    private long maxTotalBytes = 5L * 1024 * 1024;
    private int maxFiles = 200;
    private int fetchTimeoutSec = 30;
    private String githubToken;
}
