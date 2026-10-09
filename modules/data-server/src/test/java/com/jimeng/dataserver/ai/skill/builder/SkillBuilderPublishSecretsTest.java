package com.jimeng.dataserver.ai.skill.builder;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.DraftView;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.manifest.SkillSecretsFile;
import com.jimeng.dataserver.ai.skill.secret.SkillSecretService;
import com.jimeng.dataserver.ai.skill.service.AiSkillRegistryService;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.entity.AiSkillVersion;
import com.jimeng.persistence.entity.SkillBuilderSession;
import com.jimeng.persistence.entity.SkillSecret;
import com.jimeng.persistence.mapper.AiSkillMapper;
import com.jimeng.persistence.mapper.AiSkillVersionMapper;
import com.jimeng.persistence.mapper.SkillBuilderSessionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验收 V1 第 ③ 条的单元版：发布后的 skill 包里没有 secrets.json，脚本和文档里没有 token 原文。
 *
 * <p>对 mapper 的断言都在 mapper 被调用的那一刻记快照，不用 ArgumentCaptor 事后看：captor 留的是同一个可变实体的引用，
 * 把 setManifest / 正文替换挪到 updateById 之后，事后看照样是对的，可库里写进去的就不对了（同 SkillTenantServiceManifestTest）。
 */
class SkillBuilderPublishSecretsTest {

    private static final String TOKEN = "sk-live-CRM-0123456789";

    /**
     * 新建 skill 的发布路径里有 {@code LambdaUpdateWrapper.set(AiSkill::getOriginRef, null)}，它创建时就要解析列名，
     * 要用 MP 的实体缓存；纯单测里没有 Spring，得自己灌一遍（同 RoleResourceServiceTest）。
     */
    @BeforeAll
    static void initEntityCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiSkill.class);
    }

    /** updateById 被调用那一刻 ai_skill 行上的内容。 */
    private record SkillAtWrite(String body, String manifest, String bundleKey, Integer version) {}

    /** versionMapper.insert 被调用那一刻版本记录上的内容。 */
    private record VersionAtWrite(String manifest, String bundleKey, Integer version) {}

    private final SkillBuilderSessionService sessions = mock(SkillBuilderSessionService.class);
    private final SkillWorkspaceReader reader = mock(SkillWorkspaceReader.class);
    private final RagMinioStorageService storage = mock(RagMinioStorageService.class);
    private final AiSkillMapper aiSkillMapper = mock(AiSkillMapper.class);
    private final AiSkillVersionMapper versionMapper = mock(AiSkillVersionMapper.class);
    private final SkillSecretService secretService = mock(SkillSecretService.class);
    private final SkillBuilderPublishService svc = new SkillBuilderPublishService(sessions, mock(SkillBuilderSessionMapper.class),
            reader, storage, aiSkillMapper, versionMapper, mock(AiSkillRegistryService.class), mock(ChatConversationService.class),
            secretService);

    private final List<SkillAtWrite> skillWrites = new ArrayList<>();
    private final List<VersionAtWrite> versionWrites = new ArrayList<>();

    private SkillBuilderSession session() {
        SkillBuilderSession session = new SkillBuilderSession();
        session.setId(1L);
        session.setTenantId("t1");
        session.setOwnerUserId(7L);
        session.setConversationId(9L);
        session.setWorkspacePrefix("ws/1/");
        when(sessions.requireOwned(1L)).thenReturn(session);
        when(sessions.requireActive(session)).thenReturn(session);
        return session;
    }

    /** 工作区里 skill/refund-flow/ 下放这些文件；返回的草稿预览通过校验。 */
    private void workspaceWith(Map<String, byte[]> files) throws Exception {
        SkillWorkspaceReader.Listing listing = new SkillWorkspaceReader.Listing("ws/1/", Map.of());
        when(reader.list("ws/1/")).thenReturn(listing);
        DraftView draft = new DraftView();
        draft.setDirName("refund-flow");
        draft.setName("refund-flow");
        draft.setDescription("退款");
        draft.setEffectiveType("PROMPT");
        draft.setValidationErrors(List.of());
        draft.setIterations(1);
        when(reader.draft(listing, null)).thenReturn(draft);
        TreeMap<String, RagMinioStorageService.ObjectInfo> objects = new TreeMap<>();
        files.keySet().forEach(k -> objects.put(k, null));
        when(reader.skillFiles(listing, "refund-flow")).thenReturn(objects);
        when(reader.readBytes(eq(listing), anyString())).thenAnswer(inv -> {
            String rel = inv.getArgument(1);
            return files.entrySet().stream().filter(e -> rel.endsWith("/" + e.getKey())).findFirst().orElseThrow().getValue();
        });
    }

    private void recordMapperWrites() {
        doAnswer(inv -> {
            AiSkill r = inv.getArgument(0);
            skillWrites.add(new SkillAtWrite(r.getBody(), r.getManifest(), r.getBundleKey(), r.getVersion()));
            return 1;
        }).when(aiSkillMapper).updateById(any(AiSkill.class));
        doAnswer(inv -> {
            AiSkillVersion v = inv.getArgument(0);
            versionWrites.add(new VersionAtWrite(v.getManifest(), v.getBundleKey(), v.getVersion()));
            return 1;
        }).when(versionMapper).insert(any(AiSkillVersion.class));
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("发布：bundle 里没有 secrets.json，文本文件里的原文都成了占位符；正文、manifest、key 行都对")
    void extractsSecretsOnPublish() throws Exception {
        session();
        Map<String, byte[]> files = new TreeMap<>();
        files.put("SKILL.md", b("---\nname: refund-flow\ndescription: 退款\nmetadata:\n  jm:\n    hosts: [cdn.example.com]\n---\n"
                + "用 " + TOKEN + " 调接口\n"));
        files.put("references/api.md", b("Authorization: Bearer " + TOKEN));
        files.put("secrets.json", b("{\"CRM_TOKEN\":{\"value\":\"" + TOKEN + "\",\"hosts\":[\"api.customer.com\"],"
                + "\"methods\":[\"GET\"]}}"));
        workspaceWith(files);
        doAnswer(inv -> { ((AiSkill) inv.getArgument(0)).setId(42L); return 1; }).when(aiSkillMapper).insert(any(AiSkill.class));
        recordMapperWrites();
        when(secretService.prepare(any(), any(), any(), any())).thenReturn(List.of(new SkillSecret()));

        svc.publish(1L);

        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> bodies = ArgumentCaptor.forClass(byte[].class);
        verify(storage, times(2)).putObject(names.capture(), bodies.capture(), anyString());
        verify(storage, never()).copyObject(anyString(), anyString());
        assertThat(names.getAllValues()).containsExactlyInAnyOrder("skills/42/1/SKILL.md", "skills/42/1/references/api.md");
        for (byte[] body : bodies.getAllValues()) {
            assertThat(new String(body, StandardCharsets.UTF_8)).doesNotContain(TOKEN).contains("{{CRM_TOKEN}}");
        }

        // 交给 updateById 的那一刻：正文已是占位符，manifest 已带上 key 的声明（不含值）
        assertThat(skillWrites).hasSize(1);
        SkillAtWrite row = skillWrites.get(0);
        assertThat(row.body()).contains("{{CRM_TOKEN}}").doesNotContain(TOKEN);
        SkillManifest m = SkillManifest.fromJson(row.manifest());
        assertThat(m.hosts()).containsExactly("cdn.example.com");
        assertThat(m.keys()).extracting(SkillManifest.KeyDecl::name).containsExactly("CRM_TOKEN");
        assertThat(row.manifest()).doesNotContain(TOKEN);

        // 交给 versionMapper.insert 的那一刻：版本记录上的 manifest 与 ai_skill 行上的一致
        assertThat(versionWrites).hasSize(1);
        assertThat(versionWrites.get(0).manifest()).isEqualTo(row.manifest());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkillSecretsFile.Entry>> entries = ArgumentCaptor.forClass(List.class);
        verify(secretService).prepare(eq("t1"), eq(42L), isNull(), entries.capture());
        assertThat(entries.getValue()).singleElement().extracting(SkillSecretsFile.Entry::value).isEqualTo(TOKEN);
        verify(secretService).save(eq(42L), eq(1), anyList());
    }

    @Test
    @DisplayName("key 算不过（没给值、范围改了、没配密钥）：整次发布失败，bundle 和库里都没写，key 也没落库")
    void failedKeyPreparationWritesNothing() throws Exception {
        session();
        Map<String, byte[]> files = new TreeMap<>();
        files.put("SKILL.md", b("---\nname: refund-flow\ndescription: 退款\n---\n调接口\n"));
        files.put("secrets.json", b("{\"CRM_TOKEN\":{\"hosts\":[\"api.customer.com\"]}}"));
        workspaceWith(files);
        doAnswer(inv -> { ((AiSkill) inv.getArgument(0)).setId(42L); return 1; }).when(aiSkillMapper).insert(any(AiSkill.class));
        recordMapperWrites();
        when(secretService.prepare(any(), any(), any(), any()))
                .thenThrow(new ServiceException(ExceptionCode.INVALID_REQUEST, "key CRM_TOKEN 没有给值"));

        assertThatThrownBy(() -> svc.publish(1L)).isInstanceOf(ServiceException.class).hasMessageContaining("CRM_TOKEN");

        verifyNoInteractions(storage);   // 没有 deletePrefix、没有 putObject
        assertThat(skillWrites).isEmpty();
        assertThat(versionWrites).isEmpty();
        verify(secretService, never()).save(any(), anyInt(), anyList());
    }

    @Test
    @DisplayName("key 先算好再动存储：prepare 排在第一次写 bundle 之前")
    void keysArePreparedBeforeTheBundleIsWritten() throws Exception {
        session();
        Map<String, byte[]> files = new TreeMap<>();
        files.put("SKILL.md", b("---\nname: refund-flow\ndescription: 退款\n---\n调接口\n"));
        workspaceWith(files);
        doAnswer(inv -> { ((AiSkill) inv.getArgument(0)).setId(42L); return 1; }).when(aiSkillMapper).insert(any(AiSkill.class));

        svc.publish(1L);

        InOrder order = inOrder(secretService, storage);
        order.verify(secretService).prepare(any(), any(), any(), any());
        order.verify(storage).deletePrefix("skills/42/1/");
        order.verify(storage).putObject(eq("skills/42/1/SKILL.md"), any(byte[].class), anyString());
        order.verify(secretService).save(eq(42L), eq(1), anyList());
    }

    @Test
    @DisplayName("改进已有 skill：用当前版本号（不是新版本号）去找上一版的 key，新 key 写在新版本下")
    void improvingPassesThePreviousVersion() throws Exception {
        SkillBuilderSession session = session();
        session.setBaseSkillId(5L);
        session.setBaseVersion(3);
        Map<String, byte[]> files = new TreeMap<>();
        files.put("SKILL.md", b("---\nname: refund-flow\ndescription: 退款\n---\n带上 {{CRM_TOKEN}} 调接口\n"));
        files.put("secrets.json", b("{\"CRM_TOKEN\":{\"hosts\":[\"api.customer.com\"]}}"));   // 没写 value：沿用上一版
        workspaceWith(files);
        AiSkill current = new AiSkill();
        current.setId(5L);
        current.setTenantId("t1");
        current.setName("refund-flow");
        current.setVersion(3);
        when(aiSkillMapper.selectById(5L)).thenReturn(current);
        when(versionMapper.selectCount(any())).thenReturn(1L);   // v3 已有版本记录，不用补
        recordMapperWrites();

        svc.publish(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkillSecretsFile.Entry>> entries = ArgumentCaptor.forClass(List.class);
        verify(secretService).prepare(eq("t1"), eq(5L), eq(3), entries.capture());
        assertThat(entries.getValue()).singleElement().extracting(SkillSecretsFile.Entry::value).isNull();
        verify(secretService).save(eq(5L), eq(4), anyList());
        assertThat(skillWrites).singleElement().extracting(SkillAtWrite::bundleKey).isEqualTo("skills/5/4/");
        assertThat(versionWrites).singleElement().extracting(VersionAtWrite::version).isEqualTo(4);
    }
}
