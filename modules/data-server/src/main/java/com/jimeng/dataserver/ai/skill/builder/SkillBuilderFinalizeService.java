package com.jimeng.dataserver.ai.skill.builder;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.eval.SkillEvalGate;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.service.AiSkillRegistryService;
import com.jimeng.dataserver.ai.skill.util.SkillMarkdownParser;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AiSkillMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;

/** DRAFT → ACTIVE。校验完整；DOER 把内存草稿 files 落 MinIO skills/{id}/1/。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillBuilderFinalizeService {
    private final AiSkillMapper aiSkillMapper;
    private final AiSkillRegistryService registry;
    private final SkillDraftStore draftStore;
    private final RagMinioStorageService minio;
    private final SkillEvalGate evalGate;

    /** 本次 finalize 的门槛结论，供控制器取回并回传给前端。ThreadLocal：同一请求线程内有效。 */
    private static final ThreadLocal<SkillEvalGate.Verdict> lastVerdict = new ThreadLocal<>();

    /** 取出并清除本次 finalize 的门槛结论。必须清除，否则会在复用的 Tomcat 线程间串。 */
    public SkillEvalGate.Verdict takeVerdict() {
        SkillEvalGate.Verdict v = lastVerdict.get();
        lastVerdict.remove();
        return v;
    }

    @Transactional
    public AiSkill finalizeDraft(Long draftId, String tenantId, Long currentUserId) {
        AiSkill s = aiSkillMapper.selectById(draftId);
        if (s == null || !Objects.equals(s.getTenantId(), tenantId) || !SkillConst.STATUS_DRAFT.equals(s.getStatus())) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "未找到该草稿");
        }
        if (!Objects.equals(s.getOwnerUserId(), currentUserId)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "只能完成自己的草稿");
        }
        SkillMarkdownParser.validate(new SkillMarkdownParser.ParsedSkill(s.getName(), s.getDescription(), s.getBody()));

        // 发布门槛：这份内容有没有通过召回评测。默认 WARN（放行但回传警告），
        // 配成 ENFORCE 则直接拦。指纹保证"测完又改坏"不会被旧成绩放行。
        Long convIdForGate = conversationIdOf(s.getOriginRef());
        SkillDraft draftForGate = convIdForGate == null ? null : draftStore.current(convIdForGate);
        String hash = SkillEvalGate.contentHash(s.getBody(),
                draftForGate == null ? null : draftForGate.getFiles());
        lastVerdict.set(evalGate.check(convIdForGate, hash));

        // bundle 落库【不能】只对 DOER 做。原先整段被 if(TYPE_DOER) 包着，于是 PROMPT skill 的
        // 附带文件——现在包括构建器写的 evals/evals.json——会在这一步凭空消失，且不报错。
        // （同一个形状的 bug 在 SkillImportService 里也有：导入 PROMPT skill 时 references/ 全丢。）
        // 规则改为：DOER 仍然必须有文件；PROMPT 有文件就存、没有就跳过。
        boolean isDoer = SkillConst.TYPE_DOER.equals(s.getSkillType());
        {
            Long conversationId = conversationIdOf(s.getOriginRef());
            SkillDraft draft = conversationId == null ? null : draftStore.current(conversationId);
            boolean hasFiles = draft != null && draft.getFiles() != null && !draft.getFiles().isEmpty();
            if (isDoer && !hasFiles) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST, "DOER skill 缺少脚本文件，请先在构建器里完成并试跑");
            }
            if (!isDoer && !hasFiles) {
                // 纯指引且没写测试用例：允许发布，但留一条日志。发布门槛另做，不在这里硬拦。
                log.info("PROMPT skill {} 没有任何附带文件（含 evals/evals.json），跳过 bundle 落库", s.getName());
            }
            if (hasFiles) {
            String prefix = "skills/" + s.getId() + "/1/";
            // 写入带 frontmatter 的规范 SKILL.md（name/description 从列重建），
            // 否则沙箱拿到的 SKILL.md 缺 frontmatter，SDK 读不到 description（何时调用的信号）。
            String skillMd = SkillMarkdownParser.render(s.getName(), s.getDescription(), s.getBody());
            try {
                minio.putObject(prefix + "SKILL.md", skillMd.getBytes(StandardCharsets.UTF_8), "text/markdown");
                for (Map.Entry<String, String> e : draft.getFiles().entrySet()) {
                    minio.putObject(prefix + e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8), "application/octet-stream");
                }
            } catch (Exception ex) {
                throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "存储 skill bundle 失败: " + ex.getMessage());
            }
            s.setBundleKey(prefix);
            s.setBundleHash(sha256Hex(s));
            }
        }

        s.setStatus(SkillConst.STATUS_ACTIVE);
        Long conversationId = conversationIdOf(s.getOriginRef());
        s.setOriginRef(null);
        aiSkillMapper.updateById(s);
        if (conversationId != null) draftStore.clear(conversationId);
        registry.reloadAndBroadcast();
        return s;
    }

    private static Long conversationIdOf(String originRef) {
        if (originRef == null || !originRef.startsWith("builder:")) return null;
        try { return Long.parseLong(originRef.substring("builder:".length())); } catch (Exception e) { return null; }
    }

    private static String sha256Hex(AiSkill s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((s.getBody() == null ? "" : s.getBody()).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return null; }
    }
}
