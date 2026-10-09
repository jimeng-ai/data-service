package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.controller.dto.SkillFileView;
import com.jimeng.dataserver.ai.skill.eval.SkillMaterializer;
import com.jimeng.dataserver.ai.skill.util.SkillBundleRules;
import com.jimeng.dataserver.ai.skill.SkillConst;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** S1 之前发布的老 bundle 里可能留着 secrets.json：它不能被下发进沙箱，也不能在详情页、评测里被读到。 */
class SkillBundleSecretsTest {

    private static final String PREFIX = "skills/7/1/";

    @Test
    @DisplayName("老bundle里的secrets不下发")
    void 老bundle里的secrets不下发() {
        SidecarRunPayload.SkillRef ref = SkillBundleResolver.toSkillRef("crm", PREFIX, "b",
                List.of(PREFIX + "SKILL.md", PREFIX + "secrets.json", PREFIX + "scripts/run.py"));
        assertThat(ref.getFiles()).extracting(SidecarRunPayload.SkillFile::getRelPath).containsExactly("SKILL.md", "scripts/run.py");
    }

    @Test
    @DisplayName("老bundle里的secrets详情页读不到")
    void 老bundle里的secrets详情页读不到() throws Exception {
        RagMinioStorageService minio = mock(RagMinioStorageService.class);
        when(minio.listObjects(PREFIX)).thenReturn(List.of(PREFIX + "secrets.json", PREFIX + "scripts/run.py"));
        when(minio.download(anyString())).thenAnswer(inv -> new ByteArrayInputStream("x".getBytes()));
        List<SkillFileView> files = new SkillBundleReadService(minio).read(PREFIX);
        assertThat(files).extracting(SkillFileView::getPath).containsExactly("scripts/run.py");
    }

    @Test
    @DisplayName("老bundle里的secrets评测读不到")
    void 老bundle里的secrets评测读不到() throws Exception {
        RagMinioStorageService minio = mock(RagMinioStorageService.class);
        when(minio.listObjects(PREFIX)).thenReturn(List.of(PREFIX + "secrets.json", PREFIX + "evals/evals.json"));
        when(minio.download(anyString())).thenAnswer(inv -> new ByteArrayInputStream("{}".getBytes()));
        SkillMaterializer materializer = new SkillMaterializer(minio);
        assertThat(materializer.readBundleFiles(PREFIX)).containsOnlyKeys("evals/evals.json");
    }

    @Test
    @DisplayName("secrets.json 不是附带文件：不让 skill 变成 DOER，也不算运行时文件；只认根目录那一份")
    void rules() {
        assertThat(SkillBundleRules.inferSkillType(List.of("SKILL.md", "secrets.json"))).isEqualTo(SkillConst.TYPE_PROMPT);
        assertThat(SkillBundleRules.isRuntimeFile("secrets.json")).isFalse();
        assertThat(SkillBundleRules.isRuntimeFile("references/secrets.json")).isTrue();
    }
}
