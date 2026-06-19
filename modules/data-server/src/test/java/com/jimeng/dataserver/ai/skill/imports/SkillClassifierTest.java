package com.jimeng.dataserver.ai.skill.imports;

import com.jimeng.dataserver.ai.skill.SkillConst;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillClassifierTest {
    @Test void doerWhenScriptsPresent() {
        assertEquals(SkillConst.TYPE_DOER, SkillClassifier.classify(new SkillBundle("md", Map.of("scripts/run.py", new byte[]{1}))));
    }
    @Test void doerWhenExecutableExtension() {
        assertEquals(SkillConst.TYPE_DOER, SkillClassifier.classify(new SkillBundle("md", Map.of("tools/convert.sh", new byte[]{1}))));
    }
    @Test void promptWhenOnlyReferences() {
        assertEquals(SkillConst.TYPE_PROMPT, SkillClassifier.classify(new SkillBundle("md", Map.of("references/guide.md", new byte[]{1}))));
    }
    @Test void promptWhenNoAuxFiles() {
        assertEquals(SkillConst.TYPE_PROMPT, SkillClassifier.classify(new SkillBundle("md", Map.of())));
    }
}
