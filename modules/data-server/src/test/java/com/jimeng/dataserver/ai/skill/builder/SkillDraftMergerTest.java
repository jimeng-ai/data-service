package com.jimeng.dataserver.ai.skill.builder;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SkillDraftMergerTest {
    @Test void mergeOnlyChangedFields() {
        SkillDraft d = new SkillDraft();
        d.setName("old"); d.setBody("b1");
        SkillDraftMerger.merge(d, Map.of("name", "new"));
        assertEquals("new", d.getName());
        assertEquals("b1", d.getBody());
    }
    @Test void mergeFilesAccumulate() {
        SkillDraft d = new SkillDraft();
        SkillDraftMerger.merge(d, Map.of("files", Map.of("scripts/a.py", "print(1)")));
        SkillDraftMerger.merge(d, Map.of("files", Map.of("scripts/b.py", "print(2)")));
        assertEquals(2, d.getFiles().size());
        assertEquals("print(1)", d.getFiles().get("scripts/a.py"));
    }
}
