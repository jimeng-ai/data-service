package com.jimeng.dataserver.ai.stats.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jimeng.dataserver.ai.rag.model.IngestionStatus;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.stats.dto.DashboardSummary;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.KbDocument;
import com.jimeng.persistence.entity.KnowledgeBase;
import com.jimeng.persistence.entity.SysRole;
import com.jimeng.persistence.entity.SysUser;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.AiSkillMapper;
import com.jimeng.persistence.mapper.ChatConversationMapper;
import com.jimeng.persistence.mapper.ChatMessageMapper;
import com.jimeng.persistence.mapper.KbDocumentMapper;
import com.jimeng.persistence.mapper.KnowledgeBaseMapper;
import com.jimeng.persistence.mapper.SysRoleMapper;
import com.jimeng.persistence.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * 看板计数：资产 / 组织 / 互动量。
 *
 * <p>白名单表（agent / ai_skill / knowledge_base / chat_conversation / chat_message）
 * 由多租户拦截器自动按 tenant_id 过滤、并自动带 deleted=0；非白名单表（sys_user / sys_role）显式带 tenant_id；
 * kb_document 无 tenant_id，经本租户 kb_id 间接隔离。
 */
@Service
@RequiredArgsConstructor
public class DashboardSummaryService {

    private final AgentMapper agentMapper;
    private final AiSkillMapper aiSkillMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KbDocumentMapper kbDocumentMapper;
    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final ChatConversationMapper chatConversationMapper;
    private final ChatMessageMapper chatMessageMapper;

    public DashboardSummary summary(String tenantId) {
        DashboardSummary s = new DashboardSummary();

        // ---- 资产：白名单表，自动租户隔离 ----
        DashboardSummary.Counts agents = new DashboardSummary.Counts();
        agents.setTotal(agentMapper.selectCount(new QueryWrapper<>()));
        agents.setPublished(agentMapper.selectCount(new QueryWrapper<Agent>().eq("status", "PUBLISHED")));
        agents.setDraft(agentMapper.selectCount(new QueryWrapper<Agent>().eq("status", "DRAFT")));
        s.getAssets().setAgents(agents);

        DashboardSummary.SkillCounts skills = new DashboardSummary.SkillCounts();
        skills.setTotal(aiSkillMapper.selectCount(new QueryWrapper<>()));
        skills.setPrivateCount(aiSkillMapper.selectCount(new QueryWrapper<AiSkill>().eq("scope", SkillConst.SCOPE_PRIVATE)));
        skills.setShared(aiSkillMapper.selectCount(new QueryWrapper<AiSkill>().eq("scope", SkillConst.SCOPE_TENANT)));
        skills.setEnabled(aiSkillMapper.selectCount(new QueryWrapper<AiSkill>().eq("status", SkillConst.STATUS_ACTIVE)));
        skills.setDisabled(aiSkillMapper.selectCount(new QueryWrapper<AiSkill>().eq("status", SkillConst.STATUS_DISABLED)));
        s.getAssets().setSkills(skills);

        // ---- 知识库 + 文档：kb 自动租户隔离；文档经本租户 kb_id 间接隔离 ----
        DashboardSummary.KbCounts kb = new DashboardSummary.KbCounts();
        List<KnowledgeBase> kbs = knowledgeBaseMapper.selectList(new QueryWrapper<>());
        kb.setKbTotal(kbs.size());
        List<Long> kbIds = kbs.stream().map(KnowledgeBase::getId).toList();
        if (!kbIds.isEmpty()) {
            kb.setDocTotal(kbDocumentMapper.selectCount(new QueryWrapper<KbDocument>().in("kb_id", kbIds)));
            kb.setDocSuccess(kbDocumentMapper.selectCount(
                    new QueryWrapper<KbDocument>().in("kb_id", kbIds).eq("status", IngestionStatus.DONE.code())));
            kb.setDocFailed(kbDocumentMapper.selectCount(
                    new QueryWrapper<KbDocument>().in("kb_id", kbIds).eq("status", IngestionStatus.FAILED.code())));
            kb.setDocIngesting(kbDocumentMapper.selectCount(new QueryWrapper<KbDocument>()
                    .in("kb_id", kbIds)
                    .in("status",
                            IngestionStatus.UPLOADED.code(),
                            IngestionStatus.PARSING.code(),
                            IngestionStatus.CHUNKING.code(),
                            IngestionStatus.CONTEXTUALIZING.code(),
                            IngestionStatus.EMBEDDING.code())));
        }
        s.getAssets().setKb(kb);

        // ---- 组织：sys_user / sys_role 非白名单，显式带 tenant_id ----
        DashboardSummary.Org org = new DashboardSummary.Org();
        org.setMembers(sysUserMapper.selectCount(new QueryWrapper<SysUser>().eq("tenant_id", tenantId)));
        org.setMembersEnabled(sysUserMapper.selectCount(
                new QueryWrapper<SysUser>().eq("tenant_id", tenantId).eq("status", 1)));
        org.setMembersDisabled(sysUserMapper.selectCount(
                new QueryWrapper<SysUser>().eq("tenant_id", tenantId).eq("status", 0)));
        Date since = Date.from(LocalDate.now(ZoneId.systemDefault())
                .minusDays(6).atStartOfDay(ZoneId.systemDefault()).toInstant());
        org.setActiveMembers7d(sysUserMapper.selectCount(
                new QueryWrapper<SysUser>().eq("tenant_id", tenantId).ge("last_login_at", since)));
        org.setRoles(sysRoleMapper.selectCount(new QueryWrapper<SysRole>().eq("tenant_id", tenantId)));
        s.setOrg(org);

        // ---- 互动量：chat_* 白名单，自动租户隔离 ----
        DashboardSummary.Engagement eng = new DashboardSummary.Engagement();
        eng.setConversations(chatConversationMapper.selectCount(new QueryWrapper<>()));
        eng.setMessages(chatMessageMapper.selectCount(new QueryWrapper<>()));
        s.setEngagement(eng);

        return s;
    }
}
