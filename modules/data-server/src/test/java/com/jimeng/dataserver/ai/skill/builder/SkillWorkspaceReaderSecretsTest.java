package com.jimeng.dataserver.ai.skill.builder;

import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService.ObjectInfo;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.DraftFile;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.DraftView;
import com.jimeng.dataserver.ai.skill.manifest.PreviousKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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

    // ---------------------------------------------------------------- 第 1 轮修复

    private static final String TOKEN = "sk-live-CRM-0123456789";
    private static final String GOOD_MD = "---\nname: refund-flow\ndescription: 退款\n---\n正文\n";
    private static final String WITH_VALUE = "{\"CRM_TOKEN\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"api.customer.com\"]}}";
    /** description 里的第二个冒号让 YAML 报错；报错文案会被截成 160 个字，值正好被切在中间。 */
    private static final String YAML_BROKEN_AROUND_THE_VALUE =
            "---\nname: refund-flow\ndescription: a: " + "字".repeat(20) + TOKEN + " 调接口\n---\n正文\n";

    private static SkillWorkspaceReader readerOver(Map<String, String> files) throws Exception {
        RagMinioStorageService storage = mock(RagMinioStorageService.class);
        List<ObjectInfo> infos = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            String name = "ws/skill/refund-flow/" + e.getKey();
            byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
            infos.add(new ObjectInfo(name, bytes.length, new Date()));
            when(storage.readBytes(name)).thenReturn(bytes);
        }
        when(storage.listObjectInfos("ws/")).thenReturn(infos);
        return new SkillWorkspaceReader(storage, new SkillBuilderProperties());
    }

    private static DraftFile file(DraftView v, String path) {
        return v.getFiles().stream().filter(f -> f.getPath().equals(path)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("★ 预览里 secrets.json 的值一律打码（草稿预览、draft-update 事件、发布后旧会话的读接口，用的都是这一份）；hosts 原样；它不是运行时文件")
    void thePreviewOfSecretsJsonIsMasked() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", GOOD_MD);
        files.put("secrets.json", WITH_VALUE);
        DraftView v = readerOver(files).draft(readerOver(files).list("ws/"), null);

        DraftFile f = file(v, "secrets.json");
        assertThat(f.getText()).contains("\"******\"").contains("api.customer.com").contains("CRM_TOKEN").doesNotContain(TOKEN);
        assertThat(f.isRuntime()).isFalse();
        assertThat(v.isSecretsUnreadable()).isFalse();
        assertThat(v.getValidationErrors()).isEmpty();
    }

    @Test
    @DisplayName("★ secrets.json 存在却读不出来（不知道哪些是值）：它的预览一个字都不给；对外只报它自己的错误，不给 SKILL.md 解析错误的原文；标出 secretsUnreadable")
    void anUnreadableSecretsJsonShowsNothingAndHidesTheSkillMdErrorText() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", YAML_BROKEN_AROUND_THE_VALUE);
        files.put("secrets.json", "{\"CRM_TOKEN\":{\"value\":\"" + TOKEN + "\",\"hosts\":[");   // 写到一半
        SkillWorkspaceReader reader = readerOver(files);
        DraftView v = reader.draft(reader.list("ws/"), null);

        assertThat(v.isSecretsUnreadable()).isTrue();
        assertThat(file(v, "secrets.json").getText()).isNull();
        // 只有 secrets.json 自己的错误（和一句说明 SKILL.md 晚点再校验），没有 SKILL.md 解析错误的原文
        assertThat(v.getValidationErrors()).hasSize(2);
        assertThat(v.getValidationErrors().get(0)).contains("secrets.json");
        assertThat(v.getValidationErrors().get(1)).contains("等 secrets.json 修好之后再校验");
        assertThat(String.join("", v.getValidationErrors())).doesNotContain("YAML").doesNotContain("sk-live").doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("secrets.json 读不出来时，目录名 / name 不一致、多余的 SKILL.md 这类会回显文件名和 name 的校验也先不报")
    void anUnreadableSecretsJsonAlsoSilencesTheChecksThatEchoNames() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: some-other-name\ndescription: 退款\n---\n正文\n");   // 与目录名 refund-flow 不一致
        files.put("references/SKILL.md", "多余的");
        files.put("secrets.json", "{ 写到一半");
        SkillWorkspaceReader reader = readerOver(files);
        DraftView v = reader.draft(reader.list("ws/"), null);

        assertThat(v.getValidationErrors()).hasSize(2);
        assertThat(String.join("", v.getValidationErrors())).doesNotContain("不一致").doesNotContain("只能有一个 SKILL.md");
        // secrets.json 修好以后，这些检查照常报
        files.put("secrets.json", WITH_VALUE);
        DraftView fixed = readerOver(files).draft(readerOver(files).list("ws/"), null);
        assertThat(fixed.getValidationErrors()).anyMatch(e -> e.contains("不一致")).anyMatch(e -> e.contains("只能有一个 SKILL.md"));
    }

    @Test
    @DisplayName("★ 先抹值、再解析：校验的文本 = 发布的文本。YAML 报错片段里只有 {{名字}}，预览里的 SKILL.md / 正文 / 其它文件也一样")
    void theSkillMdIsScrubbedBeforeItIsParsed() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", YAML_BROKEN_AROUND_THE_VALUE);
        files.put("scripts/call.py", "TOKEN = '" + TOKEN + "'\n");
        files.put("secrets.json", WITH_VALUE);
        SkillWorkspaceReader reader = readerOver(files);
        DraftView v = reader.draft(reader.list("ws/"), null);

        assertThat(v.getValidationErrors()).anyMatch(e -> e.contains("YAML"));
        assertThat(String.join("", v.getValidationErrors())).doesNotContain("sk-live").doesNotContain(TOKEN);
        assertThat(v.getSkillMd()).contains("{{CRM_TOKEN}}").doesNotContain(TOKEN);
        assertThat(file(v, "scripts/call.py").getText()).isEqualTo("TOKEN = '{{CRM_TOKEN}}'\n");
        assertThat(v.isSecretsUnreadable()).isFalse();
    }

    @Test
    @DisplayName("草稿的 name / description / body 取自替换之后的 SKILL.md（DRAFT 行同步的就是它们）")
    void nameDescriptionAndBodyComeFromTheScrubbedText() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: refund-flow\ndescription: \"退款 " + TOKEN + "\"\n---\n调接口时带上 " + TOKEN + "\n");
        files.put("secrets.json", WITH_VALUE);
        SkillWorkspaceReader reader = readerOver(files);
        DraftView v = reader.draft(reader.list("ws/"), null);

        assertThat(v.getDescription()).isEqualTo("退款 {{CRM_TOKEN}}");
        assertThat(v.getBody()).isEqualTo("调接口时带上 {{CRM_TOKEN}}\n");
        assertThat(v.getSkillMd()).endsWith(v.getBody());   // 前端靠它把 frontmatter 切出来
    }

    @Test
    @DisplayName("值写进了 paths：校验错误点名 key 和 paths，不带值")
    void aValueInPathsIsReportedWithoutTheValue() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", GOOD_MD);
        files.put("secrets.json", "{\"HOOK_KEY\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"hook.example.com\"],\"paths\":[\"/hook/" + TOKEN + "\"]}}");
        SkillWorkspaceReader reader = readerOver(files);
        DraftView v = reader.draft(reader.list("ws/"), null);

        assertThat(v.getValidationErrors()).anyMatch(e -> e.contains("HOOK_KEY") && e.contains("paths"));
        assertThat(String.join("", v.getValidationErrors())).doesNotContain(TOKEN);
        assertThat(file(v, "secrets.json").getText()).doesNotContain(TOKEN);
    }

    private static PreviousKeys stubbed(AtomicInteger decryptions) {
        return new PreviousKeys() {
            @Override
            public Set<String> names() {
                return Set.of("CRM_TOKEN");
            }

            @Override
            public Map<String, String> values(Collection<String> names) {
                decryptions.incrementAndGet();
                return Map.of("CRM_TOKEN", TOKEN);
            }
        };
    }

    @Test
    @DisplayName("沿用上一版的值（secrets.json 里不带 value）：发布时传入 PreviousKeys 才能抹掉脚本和报错里的原文；预览和同步不传，也就不解密")
    void reusedValuesAreScrubbedOnlyWhenTheCallerSuppliesThem() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", YAML_BROKEN_AROUND_THE_VALUE);
        files.put("scripts/call.py", "TOKEN = '" + TOKEN + "'\n");
        files.put("secrets.json", "{\"CRM_TOKEN\":{\"hosts\":[\"api.customer.com\"]}}");
        SkillWorkspaceReader reader = readerOver(files);

        AtomicInteger decryptions = new AtomicInteger();
        DraftView publishing = reader.draft(reader.list("ws/"), null, stubbed(decryptions));
        assertThat(decryptions.get()).isEqualTo(1);
        assertThat(String.join("", publishing.getValidationErrors())).doesNotContain("sk-live");
        assertThat(file(publishing, "scripts/call.py").getText()).isEqualTo("TOKEN = '{{CRM_TOKEN}}'\n");

        DraftView preview = reader.draft(reader.list("ws/"), null);   // 预览 / 同步：不解密
        assertThat(file(preview, "scripts/call.py").getText()).contains(TOKEN);
        assertThat(decryptions.get()).isEqualTo(1);
    }
}
