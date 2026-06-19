package com.jimeng.dataserver.ai.skill.imports;

import com.jimeng.common.core.exception.ServiceException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class SkillBundleExtractorTest {
    private byte[] tarGz(String... pathThenContent) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(bos))) {
            for (int i = 0; i < pathThenContent.length; i += 2) {
                byte[] data = pathThenContent[i + 1].getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry e = new TarArchiveEntry(pathThenContent[i]);
                e.setSize(data.length);
                tar.putArchiveEntry(e); tar.write(data); tar.closeArchiveEntry();
            }
        }
        return bos.toByteArray();
    }
    private SkillBundleExtractor extractor() { return new SkillBundleExtractor(new SkillImportProperties()); }

    @Test void extractsSkillMarkdownAndStripsTopLevelDir() throws Exception {
        byte[] gz = tarGz(
                "repo-main/skills/pdf/SKILL.md", "---\nname: pdf\ndescription: d\n---\n正文",
                "repo-main/skills/pdf/scripts/run.py", "print(1)");
        SkillBundle b = extractor().extract(gz, "skills/pdf");
        assertTrue(b.skillMarkdown().contains("name: pdf"));
        assertTrue(b.files().containsKey("scripts/run.py"));
        assertFalse(b.files().containsKey("SKILL.md"));
    }
    @Test void rejectsPathTraversal() throws Exception {
        byte[] gz = tarGz("repo-main/skills/x/../../etc/passwd", "x");
        assertThrows(ServiceException.class, () -> extractor().extract(gz, "skills/x"));
    }
    @Test void rejectsTooManyFiles() throws Exception {
        SkillImportProperties p = new SkillImportProperties(); p.setMaxFiles(1);
        SkillBundleExtractor ex = new SkillBundleExtractor(p);
        byte[] gz = tarGz(
                "repo-main/skills/x/SKILL.md", "---\nname: x\ndescription: d\n---\nb",
                "repo-main/skills/x/a.txt", "a",
                "repo-main/skills/x/b.txt", "b");
        assertThrows(ServiceException.class, () -> ex.extract(gz, "skills/x"));
    }
    @Test void failsWhenNoSkillMd() throws Exception {
        byte[] gz = tarGz("repo-main/skills/x/readme.md", "no skill");
        assertThrows(ServiceException.class, () -> extractor().extract(gz, "skills/x"));
    }
}
