package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SkillBundleResolverTest {
    @Test void mapsObjectsToRelPaths() {
        SidecarRunPayload.SkillRef ref = SkillBundleResolver.toSkillRef(
                "pdf", "skills/5/1/", "bkt",
                List.of("skills/5/1/SKILL.md", "skills/5/1/scripts/run.py"));
        assertEquals("pdf", ref.getName());
        assertEquals(2, ref.getFiles().size());
        assertEquals("SKILL.md", ref.getFiles().get(0).getRelPath());
        assertEquals("scripts/run.py", ref.getFiles().get(1).getRelPath());
        assertEquals("bkt", ref.getFiles().get(0).getBucket());
    }
}
