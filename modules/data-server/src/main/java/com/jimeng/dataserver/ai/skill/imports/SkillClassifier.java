package com.jimeng.dataserver.ai.skill.imports;

import com.jimeng.dataserver.ai.skill.SkillConst;
import java.util.Set;

public final class SkillClassifier {
    private SkillClassifier() {}
    private static final Set<String> SCRIPT_EXT = Set.of(".py", ".sh", ".js", ".ts", ".rb", ".pl", ".bash");

    public static String classify(SkillBundle bundle) {
        for (String path : bundle.files().keySet()) {
            String lower = path.toLowerCase();
            if (lower.startsWith("scripts/")) return SkillConst.TYPE_DOER;
            for (String ext : SCRIPT_EXT) if (lower.endsWith(ext)) return SkillConst.TYPE_DOER;
        }
        return SkillConst.TYPE_PROMPT;
    }
}
