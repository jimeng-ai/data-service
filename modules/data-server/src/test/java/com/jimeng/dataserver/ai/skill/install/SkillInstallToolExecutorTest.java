package com.jimeng.dataserver.ai.skill.install;

import com.jimeng.dataserver.ai.skill.imports.GithubSkillSearchService;
import com.jimeng.dataserver.ai.skill.imports.SkillCandidate;
import com.jimeng.dataserver.ai.skill.service.SkillImportService;
import com.jimeng.persistence.entity.AiSkill;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SkillInstallToolExecutorTest {

    private SkillInstallGuard guardAllow() {
        SkillInstallGuard g = mock(SkillInstallGuard.class);
        when(g.canInstall()).thenReturn(true);
        return g;
    }

    @Test
    void supportsBothTools() {
        SkillInstallToolExecutor ex = new SkillInstallToolExecutor(
                mock(GithubSkillSearchService.class), mock(SkillImportService.class), mock(SkillInstallGuard.class));
        assertTrue(ex.supports("skill.search"));
        assertTrue(ex.supports("skill.install"));
        assertFalse(ex.supports("gaode.poi.cluster"));
    }

    @Test
    void searchReturnsCandidates() {
        GithubSkillSearchService search = mock(GithubSkillSearchService.class);
        SkillCandidate c = new SkillCandidate();
        c.setOwner("anthropics");
        c.setRepo("skills");
        c.setPath("pdf");
        when(search.search("pdf")).thenReturn(List.of(c));
        SkillInstallToolExecutor ex = new SkillInstallToolExecutor(search, mock(SkillImportService.class), guardAllow());
        Object payload = ex.execute("skill.search", Map.of("keyword", "pdf"));
        assertTrue(payload.toString().contains("anthropics"));
    }

    @Test
    void installDeniedWithoutPermission() {
        SkillInstallGuard guard = mock(SkillInstallGuard.class);
        when(guard.canInstall()).thenReturn(false);
        SkillImportService importSvc = mock(SkillImportService.class);
        SkillInstallToolExecutor ex = new SkillInstallToolExecutor(
                mock(GithubSkillSearchService.class), importSvc, guard);
        Object payload = ex.execute("skill.install",
                Map.of("owner", "anthropics", "repo", "skills", "path", "pdf"));
        assertTrue(payload.toString().contains("no_permission"));
        verify(importSvc, never()).importFromGithub(any(), any(), any(), any(), any(), any());
    }

    @Test
    void installCallsImportWhenAllowed() {
        SkillImportService importSvc = mock(SkillImportService.class);
        AiSkill s = new AiSkill();
        s.setName("pdf");
        when(importSvc.importFromGithub(eq("anthropics"), eq("skills"), anyString(), eq("pdf"), any(), any()))
                .thenReturn(s);
        SkillInstallToolExecutor ex = new SkillInstallToolExecutor(
                mock(GithubSkillSearchService.class), importSvc, guardAllow());
        Object payload = ex.execute("skill.install",
                Map.of("owner", "anthropics", "repo", "skills", "ref", "main", "path", "pdf"));
        assertTrue(payload.toString().contains("pdf"));
        verify(importSvc).importFromGithub(
                eq("anthropics"), eq("skills"), eq("main"), eq("pdf"), any(), any());
    }
}
