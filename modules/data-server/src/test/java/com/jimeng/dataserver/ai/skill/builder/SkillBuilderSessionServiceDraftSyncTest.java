package com.jimeng.dataserver.ai.skill.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService.ObjectInfo;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.DraftUpdateEvent;
import com.jimeng.dataserver.ai.skill.secret.SkillSecretService;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.entity.SkillBuilderSession;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.AiSkillMapper;
import com.jimeng.persistence.mapper.SkillBuilderSessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每轮结束同步 DRAFT 行（{@code syncAfterRun}）：DRAFT 行在列表里就是一张卡片，还能被设成全团队可见，
 * 库里不能有 key 原文。真的草稿读取（SkillWorkspaceReader）加真的 SkillBuilderSessionService，存储和 mapper 是桩。
 */
class SkillBuilderSessionServiceDraftSyncTest {

    private static final String TOKEN = "sk-live-CRM-0123456789";
    private static final String WITH_VALUE = "{\"CRM_TOKEN\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"api.customer.com\"]}}";

    /** 插入 / 更新 DRAFT 行那一刻行上的内容（ArgumentCaptor 留的是引用，分不出是写之前还是之后设的）。 */
    private record Row(String name, String description, String body) {
        static Row of(AiSkill s) {
            return new Row(s.getName(), s.getDescription(), s.getBody());
        }
    }

    private final RagMinioStorageService storage = mock(RagMinioStorageService.class);
    private final AiSkillMapper aiSkillMapper = mock(AiSkillMapper.class);
    private final SkillBuilderSessionMapper sessionMapper = mock(SkillBuilderSessionMapper.class);
    private final SkillBuilderSessionService svc = new SkillBuilderSessionService(sessionMapper, aiSkillMapper,
            mock(AgentMapper.class), mock(ChatConversationService.class), storage,
            new SkillWorkspaceReader(storage, new SkillBuilderProperties()), new SkillBuilderProperties(), new ObjectMapper(),
            mock(SkillSecretService.class));
    private final List<Row> inserted = new ArrayList<>();
    private final List<Row> updated = new ArrayList<>();

    private void workspace(Map<String, String> files, AiSkill existingDraft) throws Exception {
        workspace(files, Map.of(), existingDraft);
    }

    /** declaredSizes：对象存储里「报出来」的大小（可以比内容大得多——不用真的造一个 512 KB 的文件）。 */
    private void workspace(Map<String, String> files, Map<String, Long> declaredSizes, AiSkill existingDraft) throws Exception {
        SkillBuilderSession session = new SkillBuilderSession();
        session.setId(1L);
        session.setTenantId("t1");
        session.setOwnerUserId(7L);
        session.setWorkspacePrefix("ws/1/");
        session.setDraftSkillId(existingDraft == null ? null : existingDraft.getId());
        when(sessionMapper.selectById(1L)).thenReturn(session);
        List<ObjectInfo> infos = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            String name = "ws/1/skill/refund-flow/" + e.getKey();
            byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
            infos.add(new ObjectInfo(name, declaredSizes.getOrDefault(e.getKey(), (long) bytes.length), new Date()));
            StorageStubs.object(storage, name, bytes);
        }
        when(storage.listObjectInfos("ws/1/")).thenReturn(infos);
        if (existingDraft != null) when(aiSkillMapper.selectById(existingDraft.getId())).thenReturn(existingDraft);
        doAnswer(inv -> { inserted.add(Row.of(inv.getArgument(0))); return 1; }).when(aiSkillMapper).insert(any(AiSkill.class));
        doAnswer(inv -> { updated.add(Row.of(inv.getArgument(0))); return 1; }).when(aiSkillMapper).updateById(any(AiSkill.class));
    }

    private static AiSkill draftRow() {
        AiSkill row = new AiSkill();
        row.setId(77L);
        row.setStatus(SkillConst.STATUS_DRAFT);
        row.setName("上一次同步的名字");
        row.setBody("上一次同步的正文");
        return row;
    }

    @Test
    @DisplayName("★ 模型把 key 原文写进了 SKILL.md（name 以外的 description 和正文）：DRAFT 行里是 {{名字}}，一个字原文都没有")
    void theDraftRowNeverReceivesTheValue() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: refund-flow\ndescription: \"退款，key 是 " + TOKEN + "\"\n---\n调接口时带上 " + TOKEN + "\n");
        files.put("secrets.json", WITH_VALUE);
        workspace(files, null);

        svc.syncAfterRun(1L, true);

        assertThat(inserted).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("refund-flow");
            assertThat(row.description()).isEqualTo("退款，key 是 {{CRM_TOKEN}}");
            assertThat(row.body()).isEqualTo("调接口时带上 {{CRM_TOKEN}}\n");
        });
        assertThat(inserted.toString()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("★ 已有 DRAFT 行时同样：更新进去的 name / description / body 都是抹过值的")
    void anExistingDraftRowIsUpdatedWithScrubbedText() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: refund-flow\ndescription: 退款\n---\n带上 " + TOKEN + "\n");
        files.put("secrets.json", WITH_VALUE);
        workspace(files, draftRow());

        svc.syncAfterRun(1L, true);

        assertThat(updated).singleElement().satisfies(row -> assertThat(row.body()).isEqualTo("带上 {{CRM_TOKEN}}\n"));
        assertThat(updated.toString()).doesNotContain(TOKEN);
        verify(aiSkillMapper, never()).insert(any(AiSkill.class));
    }

    @Test
    @DisplayName("★ secrets.json 存在却读不出来（写到一半）：不知道哪些是值，第一次同步就建一行，名字和正文都是说明，不写草稿里的任何文字")
    void anUnreadableSecretsFileMeansNothingIsWrittenOnTheFirstSync() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: refund-flow\ndescription: 退款 " + TOKEN + "\n---\n带上 " + TOKEN + "\n");
        files.put("secrets.json", "{\"CRM_TOKEN\":{\"value\":\"" + TOKEN + "\",\"hosts\":[");
        workspace(files, null);

        svc.syncAfterRun(1L, true);

        assertThat(inserted).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo(SkillBuilderSessionService.UNREADABLE_DRAFT_NAME);
            assertThat(row.description()).isNull();
            assertThat(row.body()).isEqualTo(SkillBuilderSessionService.UNREADABLE_DRAFT_BODY);
        });
        assertThat(inserted.toString()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("★ secrets.json 读不出来、DRAFT 行已经有了：保留上一次同步的内容，不更新")
    void anUnreadableSecretsFileLeavesTheExistingDraftRowAlone() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: refund-flow\ndescription: 退款\n---\n带上 " + TOKEN + "\n");
        files.put("secrets.json", "{\"CRM_TOKEN\":{\"value\":\"" + TOKEN + "\",\"hosts\":[");
        AiSkill existing = draftRow();
        workspace(files, existing);

        svc.syncAfterRun(1L, true);

        verify(aiSkillMapper, never()).updateById(any(AiSkill.class));
        verify(aiSkillMapper, never()).insert(any(AiSkill.class));
        assertThat(existing.getBody()).isEqualTo("上一次同步的正文");
    }

    @Test
    @DisplayName("★ 推给前端的 draft-update 事件（序列化之后的 JSON）里没有值：不论 SKILL.md、脚本、secrets.json 的预览、校验错误")
    void theDraftUpdateEventCarriesNoValue() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: refund-flow\ndescription: a: " + "字".repeat(20) + TOKEN + " 调接口\n---\n带上 " + TOKEN + "\n");
        files.put("scripts/call.py", "TOKEN = '" + TOKEN + "'\n");
        files.put("secrets.json", WITH_VALUE);
        workspace(files, null);

        DraftUpdateEvent event = svc.syncAfterRun(1L, true);

        String payload = new ObjectMapper().writeValueAsString(event);
        assertThat(payload).doesNotContain(TOKEN).doesNotContain("sk-live");
        assertThat(payload).contains("{{CRM_TOKEN}}").contains("******");
    }

    // ---------------------------------------------------------------- 第 2 轮 C：确定不了值集合、SKILL.md 太大——不同步

    private static final long LIMIT = new SkillBuilderProperties().getPreviewFileMaxBytes();

    private static String sixtyFiveCandidates() {
        StringBuilder b = new StringBuilder("{\"CRM_TOKEN\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"api.customer.com\"]");
        for (int i = 0; i < 65; i++) b.append(",\"note").append(i).append("\":\"candidate-value-").append(i).append("\"");
        return b.append("}}").toString();
    }

    @Test
    @DisplayName("★ R22：值集合确定不了（候选值超过 64 个）= 读不出来：DRAFT 行已经有了就保留上一次，不更新、不新建")
    void anUndeterminableValueSetLeavesTheExistingDraftRowAlone() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: refund-flow\ndescription: 退款\n---\n带上 " + TOKEN + "\n");
        files.put("secrets.json", sixtyFiveCandidates());
        AiSkill existing = draftRow();
        workspace(files, existing);

        svc.syncAfterRun(1L, true);

        verify(aiSkillMapper, never()).updateById(any(AiSkill.class));
        verify(aiSkillMapper, never()).insert(any(AiSkill.class));
        assertThat(existing.getBody()).isEqualTo("上一次同步的正文");
    }

    @Test
    @DisplayName("★ R22：secrets.json 超过大小上限（不读）：第一次同步就建一行占位，不写草稿里的任何文字")
    void aSecretsJsonOverTheLimitMeansAPlaceholderOnTheFirstSync() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: refund-flow\ndescription: 退款 " + TOKEN + "\n---\n带上 " + TOKEN + "\n");
        files.put("secrets.json", WITH_VALUE);
        workspace(files, Map.of("secrets.json", LIMIT + 1), null);

        svc.syncAfterRun(1L, true);

        assertThat(inserted).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo(SkillBuilderSessionService.UNREADABLE_DRAFT_NAME);
            assertThat(row.description()).isNull();
            assertThat(row.body()).isEqualTo(SkillBuilderSessionService.UNREADABLE_DRAFT_BODY);
        });
        assertThat(inserted.toString()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("★ R22：SKILL.md 超过大小上限（不读、不解析、不抹值）：第一次同步建一行占位，正文是单独的说明")
    void aSkillMdOverTheLimitMeansAPlaceholderOnTheFirstSync() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: refund-flow\ndescription: 退款 " + TOKEN + "\n---\n带上 " + TOKEN + "\n");
        files.put("secrets.json", WITH_VALUE);
        workspace(files, Map.of("SKILL.md", LIMIT + 1), null);

        svc.syncAfterRun(1L, true);

        assertThat(inserted).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo(SkillBuilderSessionService.UNREADABLE_DRAFT_NAME);
            assertThat(row.description()).isNull();
            assertThat(row.body()).isEqualTo(SkillBuilderSessionService.TOO_LARGE_DRAFT_BODY);
        });
        assertThat(inserted.toString()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("★ R22：SKILL.md 太大、DRAFT 行已经有了：保留上一次同步的内容，不更新")
    void aSkillMdOverTheLimitLeavesTheExistingDraftRowAlone() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: refund-flow\ndescription: 退款\n---\n带上 " + TOKEN + "\n");
        files.put("secrets.json", WITH_VALUE);
        AiSkill existing = draftRow();
        workspace(files, Map.of("SKILL.md", LIMIT + 1), existing);

        svc.syncAfterRun(1L, true);

        verify(aiSkillMapper, never()).updateById(any(AiSkill.class));
        verify(aiSkillMapper, never()).insert(any(AiSkill.class));
        assertThat(existing.getBody()).isEqualTo("上一次同步的正文");
        assertThat(existing.getName()).isEqualTo("上一次同步的名字");
    }

    @Test
    @DisplayName("★ R22：读不出来时推给前端的 draft-update 事件（序列化之后的 JSON）里没有 skill 文件的任何原文：SKILL.md、脚本、正文都是空的")
    void theDraftUpdateEventOfAnUnreadableSecretsFileCarriesNoSkillFileText() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: refund-flow\ndescription: 退款 " + TOKEN + "\n---\n调接口时带上 " + TOKEN + "\n");
        files.put("scripts/call.py", "TOKEN = '" + TOKEN + "'\n");
        files.put("secrets.json", sixtyFiveCandidates());
        workspace(files, null);

        DraftUpdateEvent event = svc.syncAfterRun(1L, true);

        String payload = new ObjectMapper().writeValueAsString(event);
        assertThat(payload).doesNotContain(TOKEN).doesNotContain("sk-live").doesNotContain("调接口时带上").doesNotContain("candidate-value-");
        assertThat(payload).contains("secrets.json");   // 固定文案还在
    }

    // ---------------------------------------------------------------- 第 4 轮 4：DRAFT 行同步前，做发布时同一道残留核对

    /** 两个当前值首尾相接地重叠：xxxx-AAAA-1111 的尾巴就是 AAAA-1111-yyyy 的头。 */
    private static final String OVERLAPPING = "{\"KEY_A\":{\"value\":\"xxxx-AAAA-1111\",\"hosts\":[\"a.example.com\"]},"
            + "\"KEY_B\":{\"value\":\"AAAA-1111-yyyy\",\"hosts\":[\"b.example.com\"]}}";
    private static final String SKILL_MD_WITH_BOTH_OVERLAPPING = "---\nname: refund-flow\ndescription: \"签名 xxxx-AAAA-1111-yyyy\"\n---\n"
            + "调接口时带上 xxxx-AAAA-1111-yyyy\n";

    @Test
    @DisplayName("★ 第 4 轮 4：两个当前值在 SKILL.md 里首尾相接地重叠——不带核对的替换只换掉先出现的那个，留下后一个值的尾巴（{{KEY_A}}-yyyy）。"
            + "DRAFT 行同步前做发布时同一道核对：有值没被整段盖住就不同步，第一次同步建占位的一行，库里收不到尾巴")
    void overlappingValuesNeverLeaveATailInTheDraftRow() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", SKILL_MD_WITH_BOTH_OVERLAPPING);
        files.put("secrets.json", OVERLAPPING);
        workspace(files, null);

        DraftUpdateEvent event = svc.syncAfterRun(1L, true);

        assertThat(inserted).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo(SkillBuilderSessionService.UNREADABLE_DRAFT_NAME);
            assertThat(row.description()).isNull();
            assertThat(row.body()).isEqualTo(SkillBuilderSessionService.OVERLAPPING_VALUES_DRAFT_BODY);
        });
        assertThat(inserted.toString()).doesNotContain("yyyy").doesNotContain("xxxx").doesNotContain("AAAA");
        // 预览和 draft-update 只给会话属主看：按 R21 / R22 保持现状，不加这道核对（文字照给、校验结果照旧，发布时处理器同一道核对会拒绝）
        assertThat(event.getDraft().getBody()).isEqualTo("调接口时带上 {{KEY_A}}-yyyy\n");
        assertThat(event.getDraft().getValidationErrors()).isEmpty();
    }

    @Test
    @DisplayName("★ 第 4 轮 4：同上，DRAFT 行已经有了——保留上一次同步的内容，不更新；值分开写了之后照常同步")
    void overlappingValuesLeaveTheExistingDraftRowAlone() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", SKILL_MD_WITH_BOTH_OVERLAPPING);
        files.put("secrets.json", OVERLAPPING);
        AiSkill existing = draftRow();
        workspace(files, existing);

        svc.syncAfterRun(1L, true);

        verify(aiSkillMapper, never()).updateById(any(AiSkill.class));
        verify(aiSkillMapper, never()).insert(any(AiSkill.class));
        assertThat(existing.getBody()).isEqualTo("上一次同步的正文");

        files.put("SKILL.md", "---\nname: refund-flow\ndescription: 签名\n---\n先带 xxxx-AAAA-1111，再带 AAAA-1111-yyyy\n");   // 分开写了
        workspace(files, existing);
        svc.syncAfterRun(1L, true);
        assertThat(updated).singleElement().satisfies(row -> assertThat(row.body()).isEqualTo("先带 {{KEY_A}}，再带 {{KEY_B}}\n"));
    }

    // ---------------------------------------------------------------- 第 4 轮（续）A（R33）：DRAFT 行同步前，解析之后再核对一遍

    @Test
    @DisplayName("★ 第 4 轮（续）R33：description 用 YAML 的 \\x43 拼出了 key 的值（原文里没有，替换不掉，解码之后就是它）——DRAFT 行同步前在解析之后再核对一遍："
            + "不同步，第一次同步建占位的一行，库里收不到这个值；已有的行保留上一次")
    void aValueRebuiltByDecodingNeverReachesTheDraftRow() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: refund-flow\ndescription: \"key sk-live-\\x43RM-0123456789\"\n---\n正文\n");
        files.put("secrets.json", WITH_VALUE);
        workspace(files, null);

        svc.syncAfterRun(1L, true);

        assertThat(inserted).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo(SkillBuilderSessionService.UNREADABLE_DRAFT_NAME);
            assertThat(row.description()).isNull();
            assertThat(row.body()).isEqualTo(SkillBuilderSessionService.DECODED_VALUE_DRAFT_BODY);
        });
        assertThat(inserted.toString()).doesNotContain(TOKEN).doesNotContain("sk-live");

        AiSkill existing = draftRow();
        workspace(files, existing);
        svc.syncAfterRun(1L, true);
        verify(aiSkillMapper, never()).updateById(any(AiSkill.class));
        assertThat(existing.getDescription()).isNull();
        assertThat(existing.getBody()).isEqualTo("上一次同步的正文");
    }

    // ---------------------------------------------------------------- 第 4 轮（再续）R34

    @Test
    @DisplayName("★ 第 4 轮（再续）R34：预览和 draft-update 里由解析得到的 name、description 也用这一轮确定的值集合抹过——description 用 \\x43 "
            + "解码出来的值不进事件（事件会写进 Redis Stream）；SKILL.md 的原文照旧给（里面是转义，不是值）")
    void theDraftUpdateEventCarriesNoDecodedValue() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: refund-flow\ndescription: \"key sk-live-\\x43RM-0123456789\"\n---\n正文\n");
        files.put("secrets.json", WITH_VALUE);
        workspace(files, null);

        DraftUpdateEvent event = svc.syncAfterRun(1L, true);

        assertThat(event.getDraft().getDescription()).isEqualTo("key {{CRM_TOKEN}}");
        assertThat(new ObjectMapper().writeValueAsString(event)).doesNotContain(TOKEN).doesNotContain("sk-live-CRM");
        assertThat(cn.hutool.json.JSONUtil.toJsonStr(event)).doesNotContain(TOKEN).doesNotContain("sk-live-CRM");   // RunEventTee 用 hutool 序列化
    }

    @Test
    @DisplayName("★ 第 4 轮（再续）R34：license（不入库的字段）用 \\x43 拼出 key 的值——DRAFT 行同步和发布同一个口径：不同步，第一次同步建占位的一行")
    void aValueRebuiltInAnyFrontmatterFieldKeepsTheDraftRowFromSyncing() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: refund-flow\ndescription: 退款\nlicense: \"MIT sk-live-\\x43RM-0123456789\"\n---\n正文\n");
        files.put("secrets.json", WITH_VALUE);
        workspace(files, null);

        svc.syncAfterRun(1L, true);

        assertThat(inserted).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo(SkillBuilderSessionService.UNREADABLE_DRAFT_NAME);
            assertThat(row.body()).isEqualTo(SkillBuilderSessionService.DECODED_VALUE_DRAFT_BODY);
        });
    }

    @Test
    @DisplayName("★ 第 3 轮：key 的名字就是它自己的值——占位符 {{名字}} 会把值打出来，所以同样按读不出来处理：第一次同步建占位的一行，推给前端的事件里没有值、没有 skill 文件的原文")
    void aKeyNameThatIsItsOwnValueIsTreatedLikeAnUnreadableFile() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", "---\nname: refund-flow\ndescription: 退款 " + TOKEN + "\n---\n带上 " + TOKEN + "\n");
        files.put("secrets.json", "{\"" + TOKEN + "\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"api.customer.com\"]}}");
        workspace(files, null);

        DraftUpdateEvent event = svc.syncAfterRun(1L, true);

        assertThat(inserted).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo(SkillBuilderSessionService.UNREADABLE_DRAFT_NAME);
            assertThat(row.body()).isEqualTo(SkillBuilderSessionService.UNREADABLE_DRAFT_BODY);
        });
        String payload = new ObjectMapper().writeValueAsString(event);
        assertThat(payload).doesNotContain(TOKEN).doesNotContain("sk-live").doesNotContain("带上");
        assertThat(inserted.toString()).doesNotContain(TOKEN);
    }
}
