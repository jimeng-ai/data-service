package com.jimeng.dataserver.ai.skill.imports;

import com.jimeng.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GithubTarballFetcherTest {
    @Test void buildsCodeloadUrl() {
        assertEquals("https://codeload.github.com/anthropics/skills/tar.gz/main",
                GithubTarballFetcher.buildUrl("anthropics", "skills", "main"));
    }
    @Test void rejectsIllegalCoordinate() {
        assertThrows(ServiceException.class, () -> GithubTarballFetcher.buildUrl("a/../b", "skills", "main"));
    }
}
