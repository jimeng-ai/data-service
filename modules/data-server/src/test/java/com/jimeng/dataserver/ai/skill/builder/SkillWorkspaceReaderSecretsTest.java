package com.jimeng.dataserver.ai.skill.builder;

import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService.ObjectInfo;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.DraftView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillWorkspaceReaderSecretsTest {

    @Test
    @DisplayName("草稿预览：secrets.json 写错就出现在发布前的校验错误里；它不让 skill 变成 DOER")
    void secretsErrorsShowUpAndDoNotMakeDoer() throws Exception {
        RagMinioStorageService storage = mock(RagMinioStorageService.class);
        String skillMd = "ws/skill/refund-flow/SKILL.md";
        String secrets = "ws/skill/refund-flow/secrets.json";
        when(storage.listObjectInfos("ws/")).thenReturn(List.of(
                new ObjectInfo(skillMd, 10, new Date()), new ObjectInfo(secrets, 10, new Date())));
        when(storage.readBytes(skillMd)).thenReturn("---\nname: refund-flow\ndescription: 退款\n---\n正文\n".getBytes(StandardCharsets.UTF_8));
        when(storage.readBytes(secrets)).thenReturn("{\"crm\":{\"value\":\"abcdefgh\",\"hosts\":[\"a.example.com\"]}}"
                .getBytes(StandardCharsets.UTF_8));
        SkillWorkspaceReader reader = new SkillWorkspaceReader(storage, new SkillBuilderProperties());

        DraftView v = reader.draft(reader.list("ws/"), null);
        assertThat(v.getValidationErrors()).anyMatch(e -> e.contains("crm"));
        assertThat(v.getInferredType()).isEqualTo(SkillConst.TYPE_PROMPT);
    }
}
