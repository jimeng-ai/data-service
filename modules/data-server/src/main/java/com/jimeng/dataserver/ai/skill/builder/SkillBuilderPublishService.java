package com.jimeng.dataserver.ai.skill.builder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.DraftView;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.PublishResult;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.ReviewMeta;
import com.jimeng.dataserver.ai.skill.service.AiSkillRegistryService;
import com.jimeng.dataserver.ai.skill.util.SkillBundleRules;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.entity.AiSkillVersion;
import com.jimeng.persistence.entity.SkillBuilderSession;
import com.jimeng.persistence.mapper.AiSkillMapper;
import com.jimeng.persistence.mapper.AiSkillVersionMapper;
import com.jimeng.persistence.mapper.SkillBuilderSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 发布构建器会话里的 skill：按 skill-creator quick_validate 的规则校验 → 把工作区里的 skill 目录复制成
 * {@code skills/{id}/{version}/}（SKILL.md 原样、不重新渲染 frontmatter）→ 写 ai_skill 与 ai_skill_version → 刷新注册表。
 *
 * <p>没有评测闸门：skill-creator 的方法是人来看对照结果、人来决定，发布关口只拦「格式不合规」这种客观错误，
 * 其余（没跑过测试、最近一轮评审没交反馈）作为 warnings 回给界面。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillBuilderPublishService {

    private final SkillBuilderSessionService sessions;
    private final SkillBuilderSessionMapper sessionMapper;
    private final SkillWorkspaceReader reader;
    private final RagMinioStorageService storage;
    private final AiSkillMapper aiSkillMapper;
    private final AiSkillVersionMapper versionMapper;
    private final AiSkillRegistryService registry;
    private final ChatConversationService conversationService;

    @Transactional
    public PublishResult publish(Long sessionId) {
        SkillBuilderSession session = sessions.requireActive(sessions.requireOwned(sessionId));
        if (conversationService.hasActiveGeneration(session.getConversationId())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "构建器还在工作，等这一轮结束再发布");
        }
        SkillWorkspaceReader.Listing l = reader.list(session.getWorkspacePrefix());
        DraftView draft = reader.draft(l, session.getSkillTypeOverride());
        if (draft == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "工作区里还没有 skill（/work/skill/<name>/SKILL.md）");
        }
        if (!draft.getValidationErrors().isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "SKILL.md 不符合发布规则：" + String.join("；", draft.getValidationErrors()));
        }
        String dir = draft.getDirName();
        List<String> files = reader.skillFiles(l, dir).keySet().stream().filter(SkillBundleRules::isPartOfSkill).toList();

        AiSkill row;
        int version;
        if (session.getBaseSkillId() != null) {
            row = aiSkillMapper.selectById(session.getBaseSkillId());
            if (row == null) throw new ServiceException(ExceptionCode.NOT_FOUND, "要改进的 skill 已不存在");
            if (!Objects.equals(row.getVersion(), session.getBaseVersion())) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST, "这个 skill 在你改进期间被更新成了 v" + row.getVersion()
                        + "，请从 skill 详情重新进入「用 AI 改进」");
            }
            if (!row.getName().equals(draft.getName())) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST, "改进已有 skill 时不能改名（原名「" + row.getName()
                        + "」，现在是「" + draft.getName() + "」）");
            }
            recordVersionIfMissing(row);
            version = row.getVersion() + 1;
        } else {
            assertNameFree(draft.getName(), session);
            row = session.getDraftSkillId() == null ? null : aiSkillMapper.selectById(session.getDraftSkillId());
            if (row == null || !SkillConst.STATUS_DRAFT.equals(row.getStatus())) {
                row = new AiSkill();
                row.setTenantId(session.getTenantId());
                row.setOwnerUserId(session.getOwnerUserId());
                row.setScope(SkillConst.SCOPE_PRIVATE);
                row.setSource(SkillConst.SOURCE_AI_GEN);
                row.setStatus(SkillConst.STATUS_DRAFT);
                row.setName(draft.getName());
                row.setSkillType(draft.getEffectiveType());
                row.setVersion(1);
                aiSkillMapper.insert(row);   // 先拿到 id，bundle 前缀要用
            }
            version = 1;
        }

        String bundlePrefix = "skills/" + row.getId() + "/" + version + "/";
        String hash = copyBundle(session.getWorkspacePrefix() + SkillWorkspaceLayout.skillDirPrefix(dir), bundlePrefix, files);

        row.setName(draft.getName());
        row.setDescription(draft.getDescription());
        row.setBody(draft.getBody());
        row.setSkillType(draft.getEffectiveType());
        row.setBundleKey(bundlePrefix);
        row.setBundleHash(hash);
        row.setVersion(version);
        if (session.getBaseSkillId() == null) {
            row.setStatus(SkillConst.STATUS_ACTIVE);
            row.setOriginRef(null);
        }
        // 改进已有 skill 时不动 status：停用中的 skill 发布新版本后仍是停用，由属主自己决定何时启用。
        aiSkillMapper.updateById(row);
        if (session.getBaseSkillId() == null && row.getOriginRef() == null) {
            // updateById 跳过 null 字段；清掉 DRAFT 时留下的 builder-session: 标记要显式写。
            aiSkillMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiSkill>()
                    .eq(AiSkill::getId, row.getId()).set(AiSkill::getOriginRef, null));
        }

        AiSkillVersion v = new AiSkillVersion();
        v.setTenantId(row.getTenantId());
        v.setSkillId(row.getId());
        v.setVersion(version);
        v.setName(row.getName());
        v.setDescription(row.getDescription());
        v.setSkillType(row.getSkillType());
        v.setBundleKey(bundlePrefix);
        v.setBundleHash(hash);
        v.setBuilderSessionId(session.getId());
        versionMapper.insert(v);

        SkillBuilderSession u = new SkillBuilderSession();
        u.setId(session.getId());
        u.setStatus(SkillBuilderSessionService.STATUS_PUBLISHED);
        u.setPublishedSkillId(row.getId());
        if (session.getBaseSkillId() == null) u.setDraftSkillId(row.getId());
        sessionMapper.updateById(u);

        registry.reloadAndBroadcast();
        log.info("构建器会话发布 session={} skill={} name={} v{} type={}", session.getId(), row.getId(), row.getName(),
                version, row.getSkillType());

        PublishResult r = new PublishResult();
        r.setSkillId(String.valueOf(row.getId()));
        r.setName(row.getName());
        r.setVersion(version);
        r.setStatus(row.getStatus());
        r.setSkillType(row.getSkillType());
        r.setWarnings(warnings(l, draft));
        return r;
    }

    /** 同租户里别的已发布 skill（自己的，或共享给全团队的）不能重名：发现阶段按名字激活，重名就分不清是哪一个。 */
    private void assertNameFree(String name, SkillBuilderSession session) {
        List<AiSkill> same = aiSkillMapper.selectList(new LambdaQueryWrapper<AiSkill>()
                .eq(AiSkill::getName, name)
                .ne(AiSkill::getStatus, SkillConst.STATUS_DRAFT));
        for (AiSkill s : same) {
            if (Objects.equals(s.getOwnerUserId(), session.getOwnerUserId()) || SkillConst.SCOPE_TENANT.equals(s.getScope())) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST, "已经有一个叫「" + name + "」的 skill，换个名字，"
                        + "或者从那个 skill 的详情里「用 AI 改进」它");
            }
        }
    }

    /** 老 skill（本功能之前发布的 v1）没有版本记录：改进前补一条，版本历史才完整。 */
    private void recordVersionIfMissing(AiSkill row) {
        Long n = versionMapper.selectCount(new LambdaQueryWrapper<AiSkillVersion>()
                .eq(AiSkillVersion::getSkillId, row.getId())
                .eq(AiSkillVersion::getVersion, row.getVersion()));
        if (n != null && n > 0) return;
        AiSkillVersion v = new AiSkillVersion();
        v.setTenantId(row.getTenantId());
        v.setSkillId(row.getId());
        v.setVersion(row.getVersion());
        v.setName(row.getName());
        v.setDescription(row.getDescription());
        v.setSkillType(row.getSkillType());
        v.setBundleKey(row.getBundleKey());
        v.setBundleHash(row.getBundleHash());
        versionMapper.insert(v);
    }

    /**
     * 服务端复制工作区里的 skill 目录到版本化 bundle，返回全部文件的 sha256（路径与内容都参与：
     * 旧实现只哈希正文，改了脚本 hash 不变）。目标前缀若已有残留（上一次复制到一半失败）先清掉。
     */
    private String copyBundle(String sourcePrefix, String bundlePrefix, List<String> files) {
        try {
            storage.deletePrefix(bundlePrefix);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String rel : files.stream().sorted().toList()) {
                storage.copyObject(sourcePrefix + rel, bundlePrefix + rel);
                md.update(rel.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                try (InputStream in = storage.download(bundlePrefix + rel)) {
                    byte[] buf = new byte[64 * 1024];
                    for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
                }
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "保存 skill 文件失败: " + e.getMessage());
        }
    }

    private List<String> warnings(SkillWorkspaceReader.Listing l, DraftView draft) {
        List<String> w = new ArrayList<>();
        if (draft.getIterations() == 0) {
            w.add("还没有跑过测试用例：skill-creator 建议至少跑一轮「带 skill / 不带 skill」的对照、看过结果再发布");
        }
        ReviewMeta review = reader.latestReview(l, draft.getDirName());
        if (review != null && !review.isFeedbackSubmitted()) {
            w.add("第 " + review.getIteration() + " 轮的评审页还没有提交反馈");
        }
        if (!reader.hasOptimizationReport(l, draft.getDirName())) {
            w.add("还没有做 description 触发优化（可选：决定这个 skill 会不会在该用的时候被想起来）");
        }
        if (draft.getOtherSkillDirs() != null && !draft.getOtherSkillDirs().isEmpty()) {
            w.add("工作区里还有别的 skill 目录（" + String.join("、", draft.getOtherSkillDirs()) + "），这次发布的是「"
                    + draft.getDirName() + "」");
        }
        return w;
    }

    /** 给测试用：bundle 里会有哪些文件（与 copyBundle 的取舍一致）。 */
    static List<String> bundleFiles(Map<String, ?> skillFiles) {
        return skillFiles.keySet().stream().filter(SkillBundleRules::isPartOfSkill).sorted().toList();
    }
}
