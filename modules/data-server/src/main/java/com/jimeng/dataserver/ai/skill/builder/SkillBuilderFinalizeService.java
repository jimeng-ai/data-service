package com.jimeng.dataserver.ai.skill.builder;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.service.AiSkillRegistryService;
import com.jimeng.dataserver.ai.skill.util.SkillMarkdownParser;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.mapper.AiSkillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;

/** DRAFT → ACTIVE。校验完整；DOER 把内存草稿 files 落 MinIO skills/{id}/1/。 */
@Service
@RequiredArgsConstructor
public class SkillBuilderFinalizeService {
    private final AiSkillMapper aiSkillMapper;
    private final AiSkillRegistryService registry;
    private final SkillDraftStore draftStore;
    private final RagMinioStorageService minio;

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

        if (SkillConst.TYPE_DOER.equals(s.getSkillType())) {
            Long conversationId = conversationIdOf(s.getOriginRef());
            SkillDraft draft = conversationId == null ? null : draftStore.current(conversationId);
            if (draft == null || draft.getFiles() == null || draft.getFiles().isEmpty()) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST, "DOER skill 缺少脚本文件，请先在构建器里完成并试跑");
            }
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
