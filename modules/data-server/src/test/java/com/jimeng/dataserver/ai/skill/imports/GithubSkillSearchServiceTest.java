package com.jimeng.dataserver.ai.skill.imports;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GithubSkillSearchServiceTest {
    @Test void rankCuratedFirst() {
        SkillCandidate a = new SkillCandidate(); a.setOwner("randomuser"); a.setStars(100);
        SkillCandidate b = new SkillCandidate(); b.setOwner("anthropics"); b.setStars(1);
        List<SkillCandidate> ranked = GithubSkillSearchService.rank(List.of(a, b), List.of("anthropics"));
        assertEquals("anthropics", ranked.get(0).getOwner());
        assertTrue(ranked.get(0).isCurated());
    }
    @Test void parseDirFromSkillMdPath() {
        assertEquals("skills/pdf", GithubSkillSearchService.dirOf("skills/pdf/SKILL.md"));
        assertEquals("", GithubSkillSearchService.dirOf("SKILL.md"));
    }
}
