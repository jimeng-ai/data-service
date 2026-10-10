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
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.manifest.SkillMarkdownRenderer;
import com.jimeng.dataserver.ai.skill.manifest.SkillSecretsFile;
import com.jimeng.dataserver.ai.skill.secret.SkillSecretService;
import com.jimeng.dataserver.ai.skill.util.SkillBundleRules;
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
    /**
     * 第一次同步时草稿的文字就给不出来（secrets.json 读不出来、SKILL.md 太大，或者 SKILL.md 里有 key 的值没能整段换成占位符）：
     * DRAFT 行先用这个名字和说明占位，修好之后的下一轮同步会换成真的。
     */
    static final String UNREADABLE_DRAFT_NAME = "草稿";
    static final String UNREADABLE_DRAFT_BODY = "（草稿里的 secrets.json 读不出来或超出限制，修好后再同步）";
    static final String TOO_LARGE_DRAFT_BODY = "（草稿里的 SKILL.md 太大了，改小后再同步）";
    static final String OVERLAPPING_VALUES_DRAFT_BODY = "（草稿的 SKILL.md 里有 key 的值叠在了一起、没能整段换成占位符，分开写之后再同步）";
    static final String DECODED_VALUE_DRAFT_BODY = "（草稿的 SKILL.md 解析出来含有 key 的值（多半是用转义或续行拼出来的），改成 {{名字}} 之后再同步）";

    private final SkillBuilderSessionMapper sessionMapper;
    private final AiSkillMapper aiSkillMapper;
    private final AgentMapper agentMapper;
    private final ChatConversationService conversationService;
    private final RagMinioStorageService storage;
    private final SkillWorkspaceReader reader;
    private final SkillBuilderProperties props;
    private final ObjectMapper objectMapper;
    private final SkillSecretService secretService;

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
     * 没有 bundle 的老 skill（上传 / 导入的纯 PROMPT）按库里的列重建一份带 frontmatter 的 SKILL.md，
     * 并带上 {@code ai_skill.manifest} 里的 {@code metadata.jm}：不带的话，用构建器改一次再发布，声明过的域名和超时就静默丢了。
     *
     * <p>key 也是一样：这个版本在 {@code skill_secret} 里有的 key，写一份<b>不带值</b>的 secrets.json 放进工作区（名字和发送范围
     * 取自 skill_secret 行，不取 manifest——key 能发到哪里只看那张表）。不写的话，改进之后的新版本里没有 secrets.json，
     * 新版本就没有 key，而 SKILL.md 里还是 {@code {{名字}}}，运行时静默解析不到。发布时没写 value 的条目沿用上一版的值。
     * 老 bundle 里残留的 secrets.json（S1 之前的 bundle 里可能有，里面是 key 原文）不带进来。
     *
     * <p>包内可见只为了让测试直接调它：走 {@link #create} 要搭一整条会话链路（请求上下文、对话、工作区读取）。
     */
    void seedFromPublished(String prefix, AiSkill base) {
        String dirPrefix = prefix + SkillWorkspaceLayout.skillDirPrefix(base.getName());
        try {
            boolean hasSkillMd = false;
            if (StrUtil.isNotBlank(base.getBundleKey())) {
                for (String obj : storage.listObjects(base.getBundleKey())) {
                    String rel = obj.substring(base.getBundleKey().length());
                    if (!SkillBundleRules.isPartOfSkill(rel)) continue;
                    if (SkillBundleRules.isSecretsFile(rel)) continue;
                    storage.copyObject(obj, dirPrefix + rel);
                    if (SkillBundleRules.SKILL_MD.equals(rel)) hasSkillMd = true;
                }
            }
            if (!hasSkillMd) {
                String md = SkillMarkdownRenderer.render(base.getName(), base.getDescription(),
                        SkillManifest.fromJson(base.getManifest()).toJm(), base.getBody());
                storage.putObject(dirPrefix + SkillBundleRules.SKILL_MD, md.getBytes(StandardCharsets.UTF_8), "text/markdown");
            }
            List<SkillManifest.KeyDecl> keys = secretService.declarationsOf(base.getId(), base.getVersion());
            if (!keys.isEmpty()) {
                storage.putObject(dirPrefix + SkillBundleRules.SECRETS_JSON,
                        SkillSecretsFile.valueLessJson(keys).getBytes(StandardCharsets.UTF_8), "application/json");
            }
        } catch (Exception e) {
            // 只记异常类名：存储异常的 message 带着对象名（文件名里可能有 key 的值），ServiceException 构造时就进 ERROR 日志
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "把 skill 放进构建器工作区失败（" + e.getClass().getSimpleName() + "）");
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
        byte[] html = readReviewFile(l, SkillWorkspaceLayout.reviewPath(dir, n));
        if (html == null) {
            v.setTooLarge(true);
        } else {
            v.setHtml(new String(html, StandardCharsets.UTF_8));
        }
        String fbRel = SkillWorkspaceLayout.feedbackPath(dir, n);
        if (l.has(fbRel)) {
            try {
                byte[] feedback = readReviewFile(l, fbRel);
                if (feedback == null) {
                    log.warn("feedback.json 超过大小上限，不返回 session={}", sessionId);
                } else {
                    v.setFeedback(objectMapper.readValue(feedback, Object.class));
                }
            } catch (Exception e) {
                // 只记异常类名：Jackson 的出错信息里带着文件开头的原文（最多 500 个字节），反馈里可能就有 key 的值；路径里有 skill 目录名
                log.warn("feedback.json 读取或解析失败 session={} cause={}", sessionId, e.getClass().getSimpleName());
            }
        }
        return v;
    }

    public ReportView optimizationReport(Long sessionId) {
        SkillBuilderSession s = requireOwned(sessionId);
        SkillWorkspaceReader.Listing l = reader.list(s.getWorkspacePrefix());
        String dir = reader.locateSkill(l).dir();
        if (dir == null || !reader.hasOptimizationReport(l, dir)) return null;
        ReportView v = new ReportView();
        byte[] html = readReviewFile(l, SkillWorkspaceLayout.optimizationReportPath(dir));
        if (html == null) {
            v.setTooLarge(true);
        } else {
            v.setHtml(new String(html, StandardCharsets.UTF_8));
        }
        return v;
    }

    /**
     * 评审页、feedback.json、触发优化报告：上限 reviewMaxBytes。列举时就超了的不读；没超也只读上限 + 1 个字节（列举之后对象可能被换大，
     * 和草稿、发布同一个有界读取）。超了返回 null。
     *
     * <p>读失败的文案里不写路径（{@code known} 传 null）：这里不读 secrets.json，值集合没有确定（R22）。这几个路径里唯一可变的是
     * skill 目录名，读的是哪一个文件，接口本身已经说明了。
     */
    private byte[] readReviewFile(SkillWorkspaceReader.Listing l, String rel) {
        long limit = props.getReviewMaxBytes();
        RagMinioStorageService.ObjectInfo o = l.objects().get(rel);
        if (o != null && o.size() > limit) return null;
        return reader.readUpTo(l, rel, limit, null);
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
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "反馈无法序列化（" + e.getClass().getSimpleName() + "）");
        }
        if (json.length > FEEDBACK_MAX_BYTES) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "反馈太长（超过 1MB）");
        }
        try {
            storage.putObject(s.getWorkspacePrefix() + SkillWorkspaceLayout.feedbackPath(dir, iteration), json, "application/json");
        } catch (Exception e) {
            // 只记异常类名：存储异常的 message 带着对象名，路径里有 skill 目录名
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "保存反馈失败（" + e.getClass().getSimpleName() + "）");
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
        SkillWorkspaceReader.Draft read = reader.readDraft(l, s.getSkillTypeOverride());
        DraftView draft = read == null ? null : read.view();
        ev.setDraft(draft);
        if (draft == null) return ev;
        ev.setReview(reader.latestReview(l, draft.getDirName()));
        ev.setHasOptimizationReport(reader.hasOptimizationReport(l, draft.getDirName()));
        if (s.getBaseSkillId() == null) upsertDraftRow(s, read);
        return ev;
    }

    /**
     * 写进 DRAFT 行的 name / description / body 取自 {@code draft}，而 {@code draft} 里的文字是 SkillWorkspaceReader 用工作区
     * secrets.json 的值抹过的：模型把 key 原文写进 SKILL.md 时，库里只有 {@code {{名字}}}。DRAFT 行在列表里就是一张卡片，
     * 还能被设成全团队可见，库里不能有 key 原文。
     *
     * <p>草稿的文字给不出来时一个字都不写进库（已有的 DRAFT 行保留上一次同步的内容；第一次同步就建一行，正文写一句说明）：
     * <ul>
     *   <li>secrets.json 存在、却读不出来或确定不了值集合（不是合法 JSON、太大、key 或候选值太多、有值太长……）：不知道哪些是值，没法抹；</li>
     *   <li>SKILL.md 超过预览上限：不读、不解析、不抹值（运行时整份 SKILL.md 都进模型上下文，这个大小本来就用不了）；</li>
     *   <li>SKILL.md 里有已知的值没被整段换成占位符（几个值首尾相接、互相包含了一部分：被抢掉的那个会剩下一截原文，比如 {@code {{KEY_A}}-yyyy}）——
     *       和发布时同一道核对（{@link SkillWorkspaceReader.Draft#skillMdPartlyReplaced}）；</li>
     *   <li>解析出来的 name / description / 正文 / metadata.jm 里有已知的值（R33：YAML 的转义、续行拼出来的，原文里没有、替换不掉）——
     *       和发布时同一道核对（{@link SkillWorkspaceReader.Draft#parsedFieldsCarryValues}）。</li>
     * </ul>
     * 后两条只管 DRAFT 行：预览和 draft-update 只给属主看，不加这两道核对（R21 / R22）。
     */
    private void upsertDraftRow(SkillBuilderSession s, SkillWorkspaceReader.Draft read) {
        DraftView draft = read.view();
        String withheldBody = draft.isSecretsUnreadable() ? UNREADABLE_DRAFT_BODY
                : draft.isSkillMdTooLarge() ? TOO_LARGE_DRAFT_BODY
                : read.skillMdPartlyReplaced() ? OVERLAPPING_VALUES_DRAFT_BODY
                : read.parsedFieldsCarryValues() ? DECODED_VALUE_DRAFT_BODY
                : null;
        boolean withheld = withheldBody != null;
        String name = withheld || StrUtil.isBlank(draft.getName()) ? UNREADABLE_DRAFT_NAME : StrUtil.maxLength(draft.getName(), 61);
        String desc = withheld || draft.getDescription() == null ? null : StrUtil.maxLength(draft.getDescription(), 1021);
        String body = withheld ? withheldBody : draft.getBody();
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
            fill(row, name, desc, body, draft);
            aiSkillMapper.insert(row);
            SkillBuilderSession u = new SkillBuilderSession();
            u.setId(s.getId());
            u.setDraftSkillId(row.getId());
            sessionMapper.updateById(u);
        } else if (SkillConst.STATUS_DRAFT.equals(row.getStatus()) && !withheld) {
            fill(row, name, desc, body, draft);
            aiSkillMapper.updateById(row);
        }
    }

    private static void fill(AiSkill row, String name, String desc, String body, DraftView draft) {
        row.setName(name);
        row.setDescription(desc);
        row.setBody(body);
        row.setSkillType(draft.getEffectiveType());
        // 有意不同步 manifest：DRAFT 行没有运行时消费者，manifest 在发布时才写（SkillBuilderPublishService.publish），
        // 草稿期间保持 NULL。读到 DRAFT 卡片的 manifest 为空是预期，不是漏了。
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
