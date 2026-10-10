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
import com.jimeng.dataserver.ai.skill.manifest.PreviousKeys;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifestParser;
import com.jimeng.dataserver.ai.skill.manifest.SkillPackageProcessor;
import com.jimeng.dataserver.ai.skill.manifest.SkillSecretsFile;
import com.jimeng.dataserver.ai.skill.secret.SkillSecretService;
import com.jimeng.dataserver.ai.skill.service.AiSkillRegistryService;
import com.jimeng.dataserver.ai.skill.util.SkillBundleRules;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.entity.AiSkillVersion;
import com.jimeng.persistence.entity.SkillBuilderSession;
import com.jimeng.persistence.entity.SkillSecret;
import com.jimeng.persistence.mapper.AiSkillMapper;
import com.jimeng.persistence.mapper.AiSkillVersionMapper;
import com.jimeng.persistence.mapper.SkillBuilderSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 发布构建器会话里的 skill：按 skill-creator quick_validate 的规则校验 → 读出工作区里的 skill 目录，交给
 * {@link SkillPackageProcessor} 提取 secrets.json、把文本文件里的 key 原文换成占位符（设计文档 §3.4）→ 写成
 * {@code skills/{id}/{version}/}（SKILL.md 除了 key 原文变占位符，不重新渲染 frontmatter）→ 写 ai_skill 与
 * ai_skill_version，key 加密写进 skill_secret → 刷新注册表。
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
    private final SkillSecretService secretService;
    private final SkillBuilderProperties props;

    @Transactional
    public PublishResult publish(Long sessionId) {
        SkillBuilderSession session = sessions.requireActive(sessions.requireOwned(sessionId));
        if (conversationService.hasActiveGeneration(session.getConversationId())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "构建器还在工作，等这一轮结束再发布");
        }
        SkillWorkspaceReader.Listing l = reader.list(session.getWorkspacePrefix());
        // 发布要把整个目录读进内存：先只看列举结果里的大小，超了就拒绝。★ 排在最前面：超限时什么都不读、不解析、不抹值，
        // 连上一版的 key 都不解密（草稿读取会读 SKILL.md 和 secrets.json、做校验和替换，成本都跟着大小走）。
        String dir = reader.locateSkill(l).dir();
        if (dir == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "工作区里还没有 skill（/work/skill/<name>/SKILL.md）");
        }
        Map<String, RagMinioStorageService.ObjectInfo> inWorkspace = reader.skillFiles(l, dir);
        long totalBytes = inWorkspace.entrySet().stream()
                .filter(e -> SkillBundleRules.isPartOfSkill(e.getKey())).mapToLong(e -> e.getValue().size()).sum();
        if (totalBytes > props.getPublishMaxBytes()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "skill 里的文件一共 " + megabytes(totalBytes)
                    + " MB，超过了发布的上限 " + megabytes(props.getPublishMaxBytes()) + " MB：把大文件拿掉，或者拆成几个 skill");
        }
        // 改进已有 skill：上一版所有 key 的值，最先解密出来（不看 secrets.json 在不在、合不合规、这一条写没写 value）——
        // 所有报错文案的抹值、沿用条目的替换、残留检查都要用它们。解不出来（没配密钥、密文坏了）整次发布拒绝，文案只带 key 名，
        // 不会跳过核对。解一次，草稿读取和发布处理器共用同一份。
        PreviousKeys previous = session.getBaseSkillId() == null ? PreviousKeys.NONE
                : PreviousKeys.decrypted(secretService.previousKeys(session.getBaseSkillId(), session.getBaseVersion()).allValues());
        DraftView draft = reader.draft(l, session.getSkillTypeOverride(), previous);
        if (draft == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "工作区里还没有 skill（/work/skill/<name>/SKILL.md）");
        }
        if (!draft.getValidationErrors().isEmpty()) {
            // 这些文案 reader.draft 里已经抹过 key 的值（ServiceException 构造时就会把它记进 ERROR 日志）
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "SKILL.md 不符合发布规则：" + String.join("；", draft.getValidationErrors()));
        }
        // 读出属于 skill 的全部文件交给发布处理器：提取 secrets.json、把 key 原文换成占位符、解析 SKILL.md（设计文档 §3.4）。
        Map<String, byte[]> raw = new TreeMap<>();
        for (String rel : inWorkspace.keySet()) {
            if (SkillBundleRules.isPartOfSkill(rel)) {
                raw.put(rel, reader.readBytes(l, SkillWorkspaceLayout.skillDirPrefix(dir) + rel));
            }
        }
        SkillPackageProcessor.Output out = SkillPackageProcessor.process(raw, SkillManifestParser.Mode.STRICT, true, previous);

        AiSkill row;
        int version;
        Integer previousVersion = null;
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
            previousVersion = row.getVersion();
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

        // key 先算好再动存储：没给值、范围改了、没配密钥都在这里失败，bundle 和库都还没写（事务回滚掉刚插的 DRAFT 行）。
        List<SkillSecret> secretRows = secretService.prepare(row.getTenantId(), row.getId(), previousVersion, out.secrets());

        String bundlePrefix = "skills/" + row.getId() + "/" + version + "/";
        String hash = writeBundle(bundlePrefix, out.files());
        // 解析结果存库，运行时只读它（设计文档 §3.6）；key 只记名字和发送范围，不含值。
        String manifestJson = SkillManifest.of(out.skill().jm(), SkillSecretsFile.decls(out.secrets())).toJson();

        row.setName(out.skill().name());
        row.setDescription(out.skill().description());
        row.setBody(out.skill().body());
        row.setManifest(manifestJson);
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
        v.setManifest(manifestJson);
        versionMapper.insert(v);
        secretService.save(row.getId(), version, secretRows);
        scheduleWorkspaceSecretsRewrite(session, dir, out.secrets());

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

    /**
     * 发布提交之后，把工作区里的 secrets.json 改写成不带值的声明（格式同「用 AI 改进」时放进工作区的那份）：
     * 值已经加密存进 skill_secret，工作区里那份明文没有理由再留着（会话读接口和 MinIO 里最多还能看 7 天）。
     * <b>必须在提交之后做</b>：在事务里做的话，后面提交失败，工作区的值就已经丢了，用户得重新给。
     * 失败只记 WARN（只带 key 名），发布照样成功，清理任务按保留期回收。
     */
    private void scheduleWorkspaceSecretsRewrite(SkillBuilderSession session, String dir, List<SkillSecretsFile.Entry> secrets) {
        if (secrets.isEmpty()) return;   // 工作区里本来就没有 secrets.json
        String path = session.getWorkspacePrefix() + SkillWorkspaceLayout.skillDirPrefix(dir) + SkillBundleRules.SECRETS_JSON;
        byte[] valueLess = SkillSecretsFile.valueLessJson(SkillSecretsFile.decls(secrets)).getBytes(StandardCharsets.UTF_8);   // 闭包里不留值
        List<String> names = secrets.stream().map(SkillSecretsFile.Entry::name).toList();
        Long sessionId = session.getId();
        Runnable rewrite = () -> {
            try {
                storage.putObject(path, valueLess, "application/json");
            } catch (Exception e) {
                log.warn("发布之后把工作区里的 secrets.json 改写成不带值失败，由清理任务回收 session={} keys={} cause={}",
                        sessionId, names, e.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    rewrite.run();
                }
            });
        } else {
            rewrite.run();   // 没有事务可等（不会发生在 @Transactional 的 publish 里）
        }
    }

    private static String megabytes(long bytes) {
        return String.format("%.1f", bytes / 1024.0 / 1024.0);
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

    /**
     * 老 skill（本功能之前发布的 v1）没有版本记录：改进前补一条，版本历史才完整。manifest 带上 ai_skill 行上的那份，
     * 与同一行的 bundle 字段是同一时刻的东西。包内可见只为了让测试直接调它（走 {@link #publish} 要搭十几处 mock）。
     */
    void recordVersionIfMissing(AiSkill row) {
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
        v.setManifest(row.getManifest());
        versionMapper.insert(v);
    }

    /**
     * 把处理过的文件写成版本化 bundle，返回全部文件的 sha256（路径与内容都参与）。目标前缀若已有残留先清掉。
     * 以前是存储端整份复制；现在文本文件里的 key 原文要换成占位符、secrets.json 要拿掉，只能读出来处理完再写。
     */
    private String writeBundle(String bundlePrefix, Map<String, byte[]> files) {
        try {
            storage.deletePrefix(bundlePrefix);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, byte[]> f : new TreeMap<>(files).entrySet()) {
                storage.putObject(bundlePrefix + f.getKey(), f.getValue(),
                        f.getKey().endsWith(".md") ? "text/markdown" : "application/octet-stream");
                md.update(f.getKey().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(f.getValue());
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

    /** 给测试用：bundle 里会有哪些文件（与 writeBundle 的取舍一致）。 */
    static List<String> bundleFiles(Map<String, ?> skillFiles) {
        return skillFiles.keySet().stream().filter(SkillBundleRules::isPartOfSkill)
                .filter(p -> !SkillBundleRules.isSecretsFile(p)).sorted().toList();
    }
}
