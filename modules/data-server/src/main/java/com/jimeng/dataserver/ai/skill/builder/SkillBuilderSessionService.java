package com.jimeng.dataserver.ai.skill.builder;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.ConversationView;
import com.jimeng.dataserver.ai.chat.dto.ChatDtos.CreateConversationRequest;
import com.jimeng.dataserver.ai.chat.service.ChatConversationService;
import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.DraftUpdateEvent;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.DraftView;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.ReportView;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.ReviewView;
import com.jimeng.dataserver.ai.skill.builder.SkillBuilderDtos.SessionView;
import com.jimeng.dataserver.ai.skill.util.SkillBundleRules;
import com.jimeng.dataserver.ai.skill.util.SkillMarkdownParser;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.entity.SkillBuilderSession;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.AiSkillMapper;
import com.jimeng.persistence.mapper.SkillBuilderSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Skill 构建器会话：创建 / 恢复、归属校验、每轮结束后从工作区同步草稿、评审页与反馈、运行方式。
 *
 * <p>会话 = 一个挂在隐藏构建器 Agent（{@link #BUILDER_AGENT_CODE}）上的普通对话 + MinIO 上的一个工作区前缀。
 * 聊天记录、run 的流式与重连全部复用对话链路的原语；skill-creator 的状态全在工作区里（见 {@link SkillWorkspaceLayout}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillBuilderSessionService {

    /** 隐藏的构建器 Agent（AgentService.INTERNAL_AGENT_CODES 里登记过，不出现在任何 Agent 列表）。 */
    public static final String BUILDER_AGENT_CODE = "__skill_builder__";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ABANDONED = "ABANDONED";
    /** 新建 skill 时 DRAFT 行的 origin_ref 前缀；列表页据此把草稿卡片接回会话。 */
    public static final String ORIGIN_REF_PREFIX = "builder-session:";
    private static final long FEEDBACK_MAX_BYTES = 1024 * 1024;

    private final SkillBuilderSessionMapper sessionMapper;
    private final AiSkillMapper aiSkillMapper;
    private final AgentMapper agentMapper;
    private final ChatConversationService conversationService;
    private final RagMinioStorageService storage;
    private final SkillWorkspaceReader reader;
    private final SkillBuilderProperties props;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ 创建 / 恢复

    public SessionView create(String baseSkillIdStr) {
        String tenantId = AdminRequestContext.requireTenantId();
        Long userId = AdminRequestContext.requireUserId();
        AiSkill base = null;
        if (StrUtil.isNotBlank(baseSkillIdStr)) {
            base = requireImprovableSkill(parseId(baseSkillIdStr, "baseSkillId"), userId);
            // 同一个 skill 同一个人只留一个进行中的改进会话：再点「用 AI 改进」回到原来那个，而不是另起一份工作区。
            SkillBuilderSession existing = sessionMapper.selectOne(new LambdaQueryWrapper<SkillBuilderSession>()
                    .eq(SkillBuilderSession::getOwnerUserId, userId)
                    .eq(SkillBuilderSession::getBaseSkillId, base.getId())
                    .eq(SkillBuilderSession::getStatus, STATUS_ACTIVE)
                    .orderByDesc(SkillBuilderSession::getId)
                    .last("limit 1"));
            if (existing != null) return view(existing, true);
        }

        Agent builder = ensureBuilderAgent();
        CreateConversationRequest cr = new CreateConversationRequest();
        cr.setAgentId(String.valueOf(builder.getId()));
        cr.setAgentName(builder.getName());
        cr.setTitle(base == null ? "生成 Skill" : "改进 Skill：" + base.getName());
        ConversationView conv = conversationService.create(cr);

        SkillBuilderSession s = new SkillBuilderSession();
        s.setId(IdWorker.getId());
        s.setTenantId(tenantId);
        s.setOwnerUserId(userId);
        s.setConversationId(conv.getId());
        s.setWorkspacePrefix(SkillWorkspaceLayout.workspacePrefix(props.getWorkspaceRoot(), tenantId, s.getId()));
        s.setStatus(STATUS_ACTIVE);
        if (base != null) {
            s.setBaseSkillId(base.getId());
            s.setBaseVersion(base.getVersion());
            seedFromPublished(s.getWorkspacePrefix(), base);
        }
        sessionMapper.insert(s);
        log.info("Skill 构建器会话已创建 session={} conversation={} base={}", s.getId(), conv.getId(),
                base == null ? "-" : base.getId());
        return view(s, true);
    }

    /** 改进已有 skill：只能是自己的、已发布（ACTIVE / DISABLED）的 skill。 */
    private AiSkill requireImprovableSkill(Long skillId, Long userId) {
        AiSkill s = aiSkillMapper.selectById(skillId);   // ai_skill 是租户隔离表，别的租户的行查不到
        if (s == null) throw new ServiceException(ExceptionCode.NOT_FOUND, "skill 不存在");
        if (!Objects.equals(s.getOwnerUserId(), userId)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "只能改进自己创建的 skill");
        }
        if (SkillConst.STATUS_DRAFT.equals(s.getStatus())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "草稿请直接继续编辑，不需要「改进」");
        }
        return s;
    }

    /**
     * 把已发布 skill 当前版本的 bundle 复制进工作区 skill/&lt;name&gt;/，作为改进的起点（skill-creator 会先快照它当基线）。
     * 没有 bundle 的老 skill（上传 / 导入的纯 PROMPT）按库里的列重建一份带 frontmatter 的 SKILL.md。
     */
    private void seedFromPublished(String prefix, AiSkill base) {
        String dirPrefix = prefix + SkillWorkspaceLayout.skillDirPrefix(base.getName());
        try {
            boolean hasSkillMd = false;
            if (StrUtil.isNotBlank(base.getBundleKey())) {
                for (String obj : storage.listObjects(base.getBundleKey())) {
                    String rel = obj.substring(base.getBundleKey().length());
                    if (!SkillBundleRules.isPartOfSkill(rel)) continue;
                    storage.copyObject(obj, dirPrefix + rel);
                    if (SkillBundleRules.SKILL_MD.equals(rel)) hasSkillMd = true;
                }
            }
            if (!hasSkillMd) {
                String md = SkillMarkdownParser.render(base.getName(), base.getDescription(), base.getBody());
                storage.putObject(dirPrefix + SkillBundleRules.SKILL_MD, md.getBytes(StandardCharsets.UTF_8), "text/markdown");
            }
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "把 skill 放进构建器工作区失败: " + e.getMessage());
        }
    }

    /** 取（或懒建）当前租户的隐藏构建器 Agent。它只承载对话，模型与提示词都不再用（构建在沙箱里跑）。 */
    Agent ensureBuilderAgent() {
        String tenantId = TenantContext.get();
        Agent existing = agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getTenantId, tenantId)
                .eq(Agent::getCode, BUILDER_AGENT_CODE)
                .last("limit 1"));
        if (existing != null) return existing;
        Agent a = new Agent();
        a.setCode(BUILDER_AGENT_CODE);
        a.setName("Skill 构建器");
        a.setDescription("在沙箱里按 skill-creator 的方法帮你新建或改进 Skill 的内置助手");
        a.setStatus("PUBLISHED");
        a.setModel(StrUtil.blankToDefault(props.getModel(), "skill-builder"));
        a.setSystemPrompt("（Skill 构建器在沙箱里运行 skill-creator，这里的提示词不会被使用）");
        agentMapper.insert(a);
        log.info("已为租户 {} 懒建 Skill 构建器 Agent id={}", tenantId, a.getId());
        return a;
    }

    // ------------------------------------------------------------------ 读

    /** 会话属主（超管也不能进别人的构建器：会话里是别人未发布的草稿与对话）。 */
    public SkillBuilderSession requireOwned(Long sessionId) {
        SkillBuilderSession s = sessionMapper.selectById(sessionId);   // 租户隔离表
        if (s == null) throw new ServiceException(ExceptionCode.NOT_FOUND, "构建器会话不存在");
        if (!Objects.equals(s.getOwnerUserId(), AdminRequestContext.requireUserId())) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "构建器会话不存在");
        }
        return s;
    }

    /** 按 id 取会话，不做归属校验——只给已在请求线程上校验过归属的后台流程用。 */
    SkillBuilderSession load(Long sessionId) {
        SkillBuilderSession s = sessionMapper.selectById(sessionId);
        if (s == null) throw new ServiceException(ExceptionCode.NOT_FOUND, "构建器会话不存在");
        return s;
    }

    public SessionView get(Long sessionId) {
        return view(requireOwned(sessionId), true);
    }

    SessionView view(SkillBuilderSession s, boolean withMessages) {
        SessionView v = new SessionView();
        v.setSessionId(String.valueOf(s.getId()));
        v.setConversationId(String.valueOf(s.getConversationId()));
        v.setStatus(s.getStatus());
        v.setSkillTypeOverride(s.getSkillTypeOverride());
        if (s.getBaseSkillId() != null) {
            v.setBaseSkillId(String.valueOf(s.getBaseSkillId()));
            v.setBaseVersion(s.getBaseVersion());
            AiSkill base = aiSkillMapper.selectById(s.getBaseSkillId());
            v.setBaseSkillName(base == null ? null : base.getName());
        }
        SkillWorkspaceReader.Listing l = reader.list(s.getWorkspacePrefix());
        DraftView draft = reader.draft(l, s.getSkillTypeOverride());
        v.setDraft(draft);
        if (draft != null) {
            v.setReview(reader.latestReview(l, draft.getDirName()));
            v.setHasOptimizationReport(reader.hasOptimizationReport(l, draft.getDirName()));
        }
        if (s.getLastRunId() != null && conversationService.hasActiveGeneration(s.getConversationId())) {
            v.setActiveRunId(s.getLastRunId());
        }
        if (withMessages) {
            v.setMessages(conversationService.detail(s.getConversationId()).getMessages());
        }
        return v;
    }

    public ReviewView review(Long sessionId) {
        SkillBuilderSession s = requireOwned(sessionId);
        SkillWorkspaceReader.Listing l = reader.list(s.getWorkspacePrefix());
        String dir = reader.locateSkill(l).dir();
        Integer n = dir == null ? null : reader.latestReviewIteration(l, dir);
        if (n == null) return null;
        ReviewView v = new ReviewView();
        v.setIteration(n);
        String reviewRel = SkillWorkspaceLayout.reviewPath(dir, n);
        if (l.objects().get(reviewRel).size() > props.getReviewMaxBytes()) {
            v.setTooLarge(true);
        } else {
            v.setHtml(new String(reader.readBytes(l, reviewRel), StandardCharsets.UTF_8));
        }
        String fbRel = SkillWorkspaceLayout.feedbackPath(dir, n);
        if (l.has(fbRel)) {
            try {
                v.setFeedback(objectMapper.readValue(reader.readBytes(l, fbRel), Object.class));
            } catch (Exception e) {
                log.warn("feedback.json 解析失败 session={} path={}: {}", sessionId, fbRel, e.getMessage());
            }
        }
        return v;
    }

    public ReportView optimizationReport(Long sessionId) {
        SkillBuilderSession s = requireOwned(sessionId);
        SkillWorkspaceReader.Listing l = reader.list(s.getWorkspacePrefix());
        String dir = reader.locateSkill(l).dir();
        if (dir == null || !reader.hasOptimizationReport(l, dir)) return null;
        String rel = SkillWorkspaceLayout.optimizationReportPath(dir);
        ReportView v = new ReportView();
        if (l.objects().get(rel).size() > props.getReviewMaxBytes()) {
            v.setTooLarge(true);
        } else {
            v.setHtml(new String(reader.readBytes(l, rel), StandardCharsets.UTF_8));
        }
        return v;
    }

    // ------------------------------------------------------------------ 写

    /**
     * 保存评审页提交的反馈，位置与 skill-creator 服务器模式完全一致（&lt;iteration&gt;/feedback.json），
     * 下一轮它照常从那里读。运行中也可以保存：边车写回时只删「本轮恢复出来、跑完不见了」的文件，
     * 这份是恢复之后才出现的，不会被删，也不会被覆盖。
     */
    public void saveFeedback(Long sessionId, Integer iteration, Map<String, Object> feedback) {
        SkillBuilderSession s = requireActive(requireOwned(sessionId));
        if (iteration == null || feedback == null || !(feedback.get("reviews") instanceof List<?>)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "反馈格式不对：需要 iteration 与 {reviews:[...]}");
        }
        SkillWorkspaceReader.Listing l = reader.list(s.getWorkspacePrefix());
        String dir = reader.locateSkill(l).dir();
        if (dir == null || !l.has(SkillWorkspaceLayout.reviewPath(dir, iteration))) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "第 " + iteration + " 轮没有评审页");
        }
        byte[] json;
        try {
            json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(feedback);
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "反馈无法序列化: " + e.getMessage());
        }
        if (json.length > FEEDBACK_MAX_BYTES) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "反馈太长（超过 1MB）");
        }
        try {
            storage.putObject(s.getWorkspacePrefix() + SkillWorkspaceLayout.feedbackPath(dir, iteration), json, "application/json");
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "保存反馈失败: " + e.getMessage());
        }
    }

    public void setSkillType(Long sessionId, String skillType) {
        SkillBuilderSession s = requireActive(requireOwned(sessionId));
        if (skillType != null && !SkillWorkspaceReader.validType(skillType)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "运行方式只能是 PROMPT 或 DOER");
        }
        s.setSkillTypeOverride(skillType);
        // updateById 默认跳过 null 字段；「恢复自动推断」要显式写 NULL。
        sessionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SkillBuilderSession>()
                .eq(SkillBuilderSession::getId, s.getId())
                .set(SkillBuilderSession::getSkillTypeOverride, skillType));
    }

    SkillBuilderSession requireActive(SkillBuilderSession s) {
        if (!STATUS_ACTIVE.equals(s.getStatus())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    STATUS_PUBLISHED.equals(s.getStatus()) ? "这个会话已经发布过了，如需再改请从 skill 详情里「用 AI 改进」" : "这个会话已放弃");
        }
        return s;
    }

    void recordRun(Long sessionId, String runId) {
        SkillBuilderSession u = new SkillBuilderSession();
        u.setId(sessionId);
        u.setLastRunId(runId);
        sessionMapper.updateById(u);
    }

    /**
     * 一轮结束后：从工作区读草稿，新建 skill 的会话同步（或首次创建）DRAFT 行，返回推给前端的 draft-update。
     * 改进已有 skill 的会话不建 DRAFT 行——那个 skill 在列表里本来就在，再冒出一张同名草稿卡片只会让人困惑。
     */
    DraftUpdateEvent syncAfterRun(Long sessionId, Boolean workspacePersisted) {
        SkillBuilderSession s = sessionMapper.selectById(sessionId);
        DraftUpdateEvent ev = new DraftUpdateEvent();
        ev.setWorkspacePersisted(workspacePersisted);
        if (s == null) return ev;
        SkillWorkspaceReader.Listing l = reader.list(s.getWorkspacePrefix());
        DraftView draft = reader.draft(l, s.getSkillTypeOverride());
        ev.setDraft(draft);
        if (draft == null) return ev;
        ev.setReview(reader.latestReview(l, draft.getDirName()));
        ev.setHasOptimizationReport(reader.hasOptimizationReport(l, draft.getDirName()));
        if (s.getBaseSkillId() == null) upsertDraftRow(s, draft);
        return ev;
    }

    private void upsertDraftRow(SkillBuilderSession s, DraftView draft) {
        String name = StrUtil.maxLength(StrUtil.blankToDefault(draft.getName(), draft.getDirName()), 61);
        String desc = draft.getDescription() == null ? null : StrUtil.maxLength(draft.getDescription(), 1021);
        AiSkill row = s.getDraftSkillId() == null ? null : aiSkillMapper.selectById(s.getDraftSkillId());
        if (row == null) {
            row = new AiSkill();
            row.setTenantId(s.getTenantId());
            row.setOwnerUserId(s.getOwnerUserId());
            row.setScope(SkillConst.SCOPE_PRIVATE);
            row.setSource(SkillConst.SOURCE_AI_GEN);
            row.setStatus(SkillConst.STATUS_DRAFT);
            row.setVersion(1);
            row.setOriginRef(ORIGIN_REF_PREFIX + s.getId());
            fill(row, name, desc, draft);
            aiSkillMapper.insert(row);
            SkillBuilderSession u = new SkillBuilderSession();
            u.setId(s.getId());
            u.setDraftSkillId(row.getId());
            sessionMapper.updateById(u);
        } else if (SkillConst.STATUS_DRAFT.equals(row.getStatus())) {
            fill(row, name, desc, draft);
            aiSkillMapper.updateById(row);
        }
    }

    private static void fill(AiSkill row, String name, String desc, DraftView draft) {
        row.setName(name);
        row.setDescription(desc);
        row.setBody(draft.getBody());
        row.setSkillType(draft.getEffectiveType());
    }

    /** DRAFT 卡片被删 = 放弃这个会话（工作区由清理任务回收）。 */
    public void abandonByDraftSkill(Long draftSkillId) {
        List<SkillBuilderSession> hits = sessionMapper.selectList(new LambdaQueryWrapper<SkillBuilderSession>()
                .eq(SkillBuilderSession::getDraftSkillId, draftSkillId)
                .eq(SkillBuilderSession::getStatus, STATUS_ACTIVE));
        for (SkillBuilderSession s : hits) {
            SkillBuilderSession u = new SkillBuilderSession();
            u.setId(s.getId());
            u.setStatus(STATUS_ABANDONED);
            sessionMapper.updateById(u);
        }
    }

    static Long parseId(String raw, String field) {
        try {
            return Long.valueOf(raw.trim());
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, field + " 不是合法的 id");
        }
    }
}
