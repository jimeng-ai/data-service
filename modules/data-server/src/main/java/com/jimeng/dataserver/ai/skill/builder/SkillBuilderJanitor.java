package com.jimeng.dataserver.ai.skill.builder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService.ObjectInfo;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.entity.SkillBuilderSession;
import com.jimeng.persistence.mapper.AiSkillMapper;
import com.jimeng.persistence.mapper.SkillBuilderSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 回收构建器与评测留在 MinIO 上的临时对象（此前这些前缀从来没人删）：
 * <ul>
 *   <li>已发布 / 已放弃的会话：工作区保留 {@code finished-retention-days} 天（发布出的 skill 已复制成版本化 bundle，不受影响）；</li>
 *   <li>长期没动的进行中会话：超过 {@code idle-retention-days} 天标记放弃，连同它的 DRAFT 卡片一起清掉；</li>
 *   <li>评测运行的临时物化 {@code skills/eval/<runId>/} 与旧构建器试跑留下的 {@code skills/draft/}。</li>
 * </ul>
 * 跨租户扫表，必须在 {@link TenantContext#runAsSystem} 里跑。全程 best-effort：一项失败只记日志，不影响其余。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillBuilderJanitor {

    private static final long DAY_MS = 24L * 3600 * 1000;
    private static final String EVAL_ROOT = "skills/eval/";
    private static final String LEGACY_DRAFT_ROOT = "skills/draft/";

    private final SkillBuilderSessionMapper sessionMapper;
    private final AiSkillMapper aiSkillMapper;
    private final RagMinioStorageService storage;
    private final SkillBuilderProperties props;

    @Scheduled(cron = "${skill.builder.janitor-cron:0 23 4 * * *}")
    public void sweep() {
        TenantContext.runAsSystem(() -> {
            sweepFinished();
            sweepIdle();
            sweepTempPrefix(EVAL_ROOT, props.getEvalMaterializationRetentionDays());
            sweepTempPrefix(LEGACY_DRAFT_ROOT, props.getEvalMaterializationRetentionDays());
        });
    }

    void sweepFinished() {
        Date cutoff = new Date(System.currentTimeMillis() - props.getFinishedRetentionDays() * DAY_MS);
        List<SkillBuilderSession> done = sessionMapper.selectList(new LambdaQueryWrapper<SkillBuilderSession>()
                .in(SkillBuilderSession::getStatus, SkillBuilderSessionService.STATUS_PUBLISHED, SkillBuilderSessionService.STATUS_ABANDONED)
                .lt(SkillBuilderSession::getUpdateTime, cutoff));
        for (SkillBuilderSession s : done) deleteWorkspace(s);
    }

    void sweepIdle() {
        Date cutoff = new Date(System.currentTimeMillis() - props.getIdleRetentionDays() * DAY_MS);
        List<SkillBuilderSession> idle = sessionMapper.selectList(new LambdaQueryWrapper<SkillBuilderSession>()
                .eq(SkillBuilderSession::getStatus, SkillBuilderSessionService.STATUS_ACTIVE)
                .lt(SkillBuilderSession::getUpdateTime, cutoff));
        for (SkillBuilderSession s : idle) {
            try {
                if (s.getDraftSkillId() != null) {
                    AiSkill d = aiSkillMapper.selectById(s.getDraftSkillId());
                    if (d != null && SkillConst.STATUS_DRAFT.equals(d.getStatus())) aiSkillMapper.deleteById(d.getId());
                }
                SkillBuilderSession u = new SkillBuilderSession();
                u.setId(s.getId());
                u.setStatus(SkillBuilderSessionService.STATUS_ABANDONED);
                sessionMapper.updateById(u);
                deleteWorkspace(s);
                log.info("[janitor] 放弃长期未动的构建器会话 session={} tenant={}", s.getId(), s.getTenantId());
            } catch (Exception e) {
                log.warn("[janitor] 清理闲置会话失败 session={}: {}", s.getId(), e.getMessage());
            }
        }
    }

    private void deleteWorkspace(SkillBuilderSession s) {
        try {
            int n = storage.deletePrefix(s.getWorkspacePrefix());
            if (n > 0) log.info("[janitor] 清理构建器工作区 session={} objects={}", s.getId(), n);
        } catch (Exception e) {
            log.warn("[janitor] 清理工作区失败 session={}: {}", s.getId(), e.getMessage());
        }
    }

    /** root 下按第一段分组（每组 = 一次运行的物化前缀），整组都早于截止时间才删。 */
    void sweepTempPrefix(String root, int retentionDays) {
        long cutoff = System.currentTimeMillis() - retentionDays * DAY_MS;
        try {
            Map<String, Long> newest = new HashMap<>();
            for (ObjectInfo o : storage.listObjectInfos(root)) {
                String rest = o.name().substring(root.length());
                int slash = rest.indexOf('/');
                if (slash <= 0) continue;
                String group = root + rest.substring(0, slash + 1);
                long t = o.lastModified() == null ? Long.MAX_VALUE : o.lastModified().getTime();
                newest.merge(group, t, Math::max);
            }
            int removed = 0;
            for (Map.Entry<String, Long> e : newest.entrySet()) {
                if (e.getValue() < cutoff) removed += storage.deletePrefix(e.getKey());
            }
            if (removed > 0) log.info("[janitor] 清理 {} 下过期临时对象 {} 个", root, removed);
        } catch (Exception e) {
            log.warn("[janitor] 清理 {} 失败: {}", root, e.getMessage());
        }
    }
}
