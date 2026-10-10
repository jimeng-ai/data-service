package com.jimeng.dataserver.ai.skill.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService.ObjectInfo;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.DraftFile;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.DraftView;
import com.jimeng.dataserver.ai.skill.manifest.PreviousKeys;
import com.jimeng.dataserver.ai.skill.manifest.SecretsJsonFixtures;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    // 下面这条的最后一段（预览里 scripts/call.py 仍含原文）是预期，不是漏洞（R21、R22 定案）：
    // 沿用的值只在发布时于内存里解密，模型和工作区都拿不到它——工作区里的 secrets.json 只有名字和发送范围、没有值，所以每轮预览、
    // draft-update、DRAFT 行同步里本来就没有「沿用的值」可抹；它只会出现在属主自己把值打进会话（对话或文件）的时候。
    // 那种情况在发布时由「全部解密 + 抹值 + 残留检查」兜住，不靠每轮预览去解密（每轮解密会让预览依赖密钥配置，也多一个读密钥的出口）。
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
    // ---------------------------------------------------------------- 第 2 轮

    private static final String OLD = "693a91f6-7a2b-4c1d-9e8f-0123456789ab";   // 上一版 WECOM_HOOK 的值

    @Test
    @DisplayName("★ 探针 B / C：发布时（传了上一版）secrets.json 有错，回显的条目和 SKILL.md 的 YAML 报错片段里都没有上一版的值，连前半截都没有")
    void thePublishingDraftScrubsEveryPreviousValueWhateverSecretsJsonSaysAndAlsoDroppedKeys() throws Exception {
        PreviousKeys previous = PreviousKeys.decrypted(Map.of("WECOM_HOOK", OLD));
        Map<String, String> echoed = new LinkedHashMap<>();
        echoed.put("SKILL.md", GOOD_MD);
        echoed.put("secrets.json", "{\"WECOM_HOOK\":{\"hosts\":[\"qyapi.weixin.qq.com\"],\"paths\":[\"/cgi-bin/webhook/send?key=" + OLD + "\"]}}");
        SkillWorkspaceReader r1 = readerOver(echoed);
        DraftView v1 = r1.draft(r1.list("ws/"), null, previous);
        assertThat(String.join("；", v1.getValidationErrors())).contains("{{WECOM_HOOK}}").doesNotContain(OLD).doesNotContain(OLD.substring(0, 12));

        Map<String, String> yaml = new LinkedHashMap<>();
        yaml.put("SKILL.md", "---\nname: refund-flow\ndescription: 通知时用 key: " + OLD + " 发消息\n---\n正文\n");
        yaml.put("secrets.json", "{\"OTHER_KEY\":{\"value\":\"another-new-value-1\",\"hosts\":[\"x.example.com\"],\"methods\":[\"GTE\"]}}");   // WECOM_HOOK 已经删了
        SkillWorkspaceReader r2 = readerOver(yaml);
        DraftView v2 = r2.draft(r2.list("ws/"), null, previous);
        assertThat(v2.getValidationErrors()).anyMatch(e -> e.contains("YAML")).anyMatch(e -> e.contains("GTE"));
        assertThat(String.join("；", v2.getValidationErrors())).doesNotContain(OLD).doesNotContain(OLD.substring(0, 10));
        assertThat(v2.getSkillMd()).doesNotContain(OLD).contains("{{WECOM_HOOK}}");
    }

    @Test
    @DisplayName("发布时没有 secrets.json 也一样：旧值出现在 SKILL.md 的报错和预览文字里，照抹")
    void thePublishingDraftScrubsPreviousValuesWithoutASecretsFile() throws Exception {
        PreviousKeys previous = PreviousKeys.decrypted(Map.of("WECOM_HOOK", OLD));
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: refund-flow\ndescription: a: " + "字".repeat(20) + OLD + " 调接口\n---\n正文\n");
        files.put("scripts/call.py", "TOKEN = '" + OLD + "'\n");
        SkillWorkspaceReader reader = readerOver(files);
        DraftView v = reader.draft(reader.list("ws/"), null, previous);
        assertThat(String.join("；", v.getValidationErrors())).doesNotContain("693a91f6");
        assertThat(file(v, "scripts/call.py").getText()).isEqualTo("TOKEN = '{{WECOM_HOOK}}'\n");
    }

    // ---------------------------------------------------------------- 第 2 轮 C：值集合确定不了 = 读不出来；对外一律不给 skill 文件的原文

    private record Ws(SkillWorkspaceReader reader, RagMinioStorageService storage) {
        DraftView draft() {
            return reader.draft(reader.list("ws/"), null);
        }

        void assertNeverRead(String rel) throws Exception {
            verify(storage, never()).readBytes("ws/skill/refund-flow/" + rel);
        }
    }

    /** declaredSizes：对象存储里「报出来」的大小（可以比内容大得多——不用真的造一个 512 KB 的文件）。 */
    private static Ws wsOver(Map<String, String> files, Map<String, Long> declaredSizes, SkillBuilderProperties props) throws Exception {
        RagMinioStorageService storage = mock(RagMinioStorageService.class);
        List<ObjectInfo> infos = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            String name = "ws/skill/refund-flow/" + e.getKey();
            byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
            infos.add(new ObjectInfo(name, declaredSizes.getOrDefault(e.getKey(), (long) bytes.length), new Date()));
            when(storage.readBytes(name)).thenReturn(bytes);
        }
        when(storage.listObjectInfos("ws/")).thenReturn(infos);
        return new Ws(new SkillWorkspaceReader(storage, props), storage);
    }

    private static final String MARKER = "MARKER-do-not-echo";

    /** 值集合确定不了的几种 secrets.json（每一种里都藏着 MARKER，任何输出里出现它就是回显了条目）。 */
    private static Map<String, String> undeterminableSecretsJson() {
        return SecretsJsonFixtures.undeterminable(TOKEN, MARKER);
    }

    @Test
    @DisplayName("★ R22：secrets.json 读不出来（写到一半 / key 太多 / 候选值太多 / 值太长）：预览、draft-update、校验文本里一律不给 skill 文件的原文——"
            + "SKILL.md、正文、脚本、参考资料都不给，只有 secrets.json 自己的固定文案；对象存储里这些文件连读都不读")
    void anUnreadableSecretsJsonWithholdsEverySkillFileText() throws Exception {
        for (Map.Entry<String, String> broken : undeterminableSecretsJson().entrySet()) {
            Map<String, String> files = new LinkedHashMap<>();
            files.put("SKILL.md", "---\nname: refund-flow\ndescription: 退款 " + TOKEN + "\n---\n调接口时带上 " + TOKEN + "\n");
            files.put("scripts/call.py", "TOKEN = '" + TOKEN + "'\n");
            files.put("references/guide.md", "key 是 " + TOKEN + "\n");
            files.put("secrets.json", broken.getValue());
            Ws ws = wsOver(files, Map.of(), new SkillBuilderProperties());

            DraftView v = ws.draft();

            String why = broken.getKey();
            assertThat(v.isSecretsUnreadable()).as(why).isTrue();
            assertThat(v.getName()).as(why).isNull();
            assertThat(v.getDescription()).as(why).isNull();
            assertThat(v.getSkillMd()).as(why).isEmpty();   // 前端要用 skillMd / body 切 frontmatter：空串，不是 null
            assertThat(v.getBody()).as(why).isEmpty();
            assertThat(v.getFiles()).as(why).hasSize(4).allSatisfy(f -> {
                assertThat(f.getText()).as(why + " " + f.getPath()).isNull();
                assertThat(f.isBinary()).isFalse();
            });
            assertThat(v.getValidationErrors()).as(why).hasSize(2);   // secrets.json 自己的固定文案 + 一句「SKILL.md 等它修好再校验」
            assertThat(v.getValidationErrors().get(0)).as(why).contains("secrets.json");
            assertThat(v.getValidationErrors().get(1)).as(why).contains("等 secrets.json 修好之后再校验");
            String everything = new ObjectMapper().writeValueAsString(v);
            assertThat(everything).as(why).doesNotContain(TOKEN).doesNotContain(MARKER).doesNotContain("sk-live").doesNotContain("调接口时带上");
            for (String rel : List.of("SKILL.md", "scripts/call.py", "references/guide.md")) ws.assertNeverRead(rel);
        }
    }

    @Test
    @DisplayName("★ secrets.json 超过 previewFileMaxBytes：先看对象大小，超了连读都不读（更不解析）；对外是固定文案；刚好等于上限的照读")
    void aSecretsJsonOverThePreviewLimitIsNotEvenRead() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", GOOD_MD);
        files.put("secrets.json", WITH_VALUE);

        long limit = new SkillBuilderProperties().getPreviewFileMaxBytes();
        Ws over = wsOver(files, Map.of("secrets.json", limit + 1), new SkillBuilderProperties());
        DraftView v = over.draft();
        over.assertNeverRead("secrets.json");
        over.assertNeverRead("SKILL.md");
        assertThat(v.isSecretsUnreadable()).isTrue();
        assertThat(v.getValidationErrors()).hasSize(2);
        assertThat(v.getValidationErrors().get(0)).contains("secrets.json 超过 512 KB");
        assertThat(v.getSkillMd()).isEmpty();

        Ws exact = wsOver(files, Map.of("secrets.json", limit), new SkillBuilderProperties());
        DraftView ok = exact.draft();
        assertThat(ok.isSecretsUnreadable()).isFalse();
        assertThat(ok.getValidationErrors()).isEmpty();
        assertThat(file(ok, "secrets.json").getText()).contains("\"******\"");
    }

    @Test
    @DisplayName("★ SKILL.md 超过 previewFileMaxBytes：不读、不解析、不抹值；校验结果是一句固定文案并写明理由（运行时整份 SKILL.md 进模型上下文）；"
            + "name / description 为 null，skillMd / body 为空串；其它文件的预览照给（抹过值）；刚好等于上限的照读")
    void aSkillMdOverThePreviewLimitIsNeitherReadNorParsed() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", GOOD_MD);
        files.put("scripts/call.py", "TOKEN = '" + TOKEN + "'\n");
        files.put("secrets.json", WITH_VALUE);
        long limit = new SkillBuilderProperties().getPreviewFileMaxBytes();

        Ws ws = wsOver(files, Map.of("SKILL.md", limit + 1), new SkillBuilderProperties());
        DraftView v = ws.draft();

        ws.assertNeverRead("SKILL.md");
        assertThat(v.isSkillMdTooLarge()).isTrue();
        assertThat(v.isSecretsUnreadable()).isFalse();
        assertThat(v.getValidationErrors()).singleElement().satisfies(e -> assertThat(e).contains("SKILL.md 超过 512 KB").contains("模型的上下文"));
        assertThat(v.getName()).isNull();
        assertThat(v.getDescription()).isNull();
        assertThat(v.getSkillMd()).isEmpty();
        assertThat(v.getBody()).isEmpty();
        assertThat(file(v, "SKILL.md").getText()).isNull();
        assertThat(file(v, "scripts/call.py").getText()).isEqualTo("TOKEN = '{{CRM_TOKEN}}'\n");
        assertThat(file(v, "secrets.json").getText()).doesNotContain(TOKEN);

        DraftView exact = wsOver(files, Map.of("SKILL.md", limit), new SkillBuilderProperties()).draft();
        assertThat(exact.isSkillMdTooLarge()).isFalse();
        assertThat(exact.getValidationErrors()).isEmpty();
        assertThat(exact.getName()).isEqualTo("refund-flow");
    }

    @Test
    @DisplayName("上限跟着 previewFileMaxBytes 配置走（文案里的数字也是）")
    void theLimitFollowsTheConfiguration() throws Exception {
        SkillBuilderProperties props = new SkillBuilderProperties();
        props.setPreviewFileMaxBytes(2048);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", GOOD_MD);
        files.put("secrets.json", WITH_VALUE);

        DraftView secretsTooLarge = wsOver(files, Map.of("secrets.json", 2049L), props).draft();
        assertThat(secretsTooLarge.getValidationErrors().get(0)).contains("secrets.json 超过 2 KB");
        DraftView skillMdTooLarge = wsOver(files, Map.of("SKILL.md", 2049L), props).draft();
        assertThat(skillMdTooLarge.getValidationErrors()).singleElement().satisfies(e -> assertThat(e).contains("SKILL.md 超过 2 KB"));
    }

    @Test
    @DisplayName("发布时（传了上一版）也一样：读不出来只报固定文案，不往下做逐条校验；上一版的值照样先解密")
    void thePublishingDraftOfAnUnreadableFileReportsOnlyTheFixedSentences() throws Exception {
        AtomicInteger decryptions = new AtomicInteger();
        for (Map.Entry<String, String> broken : undeterminableSecretsJson().entrySet()) {
            Map<String, String> files = new LinkedHashMap<>();
            files.put("SKILL.md", YAML_BROKEN_AROUND_THE_VALUE);
            files.put("secrets.json", broken.getValue());
            Ws ws = wsOver(files, Map.of(), new SkillBuilderProperties());

            DraftView v = ws.reader().draft(ws.reader().list("ws/"), null, stubbed(decryptions));

            assertThat(v.getValidationErrors()).as(broken.getKey()).hasSize(2);
            assertThat(String.join("；", v.getValidationErrors())).as(broken.getKey()).doesNotContain(MARKER).doesNotContain(TOKEN).doesNotContain("YAML");
        }
        assertThat(decryptions.get()).isEqualTo(undeterminableSecretsJson().size());   // 每次发布都先解密（R23），读不出来也一样
    }
}
