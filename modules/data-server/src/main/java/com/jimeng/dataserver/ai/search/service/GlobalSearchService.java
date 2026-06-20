package com.jimeng.dataserver.ai.search.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.dataserver.ai.agent.service.AgentService;
import com.jimeng.dataserver.ai.plugin.service.PluginCrudService;
import com.jimeng.dataserver.ai.rag.service.KnowledgeBaseService;
import com.jimeng.dataserver.ai.search.dto.GlobalSearchResult;
import com.jimeng.dataserver.ai.skill.service.SkillTenantService;
import com.jimeng.dataserver.ai.trace.service.TraceSupport;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.entity.AiTrace;
import com.jimeng.persistence.entity.KbDocument;
import com.jimeng.persistence.entity.KnowledgeBase;
import com.jimeng.persistence.entity.Plugin;
import com.jimeng.persistence.mapper.AiTraceMapper;
import com.jimeng.persistence.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局搜索（⌘K 命令面板）：在 Agent / 文档 / Trace 三类实体上做「快速跳转」级别的模糊搜索。
 *
 * <p>定位是快速直达，不是全量检索：每类只取前 {@code limit} 条。租户隔离 + 行级隔离全部复用各实体
 * 既有的、已验证的可见性逻辑——Agent 走 RBAC（{@link PermissionResolver#filterCurrent}）、
 * 文档限当前用户可见的知识库、Trace 按 create_user 私有——本类不另起一套权限判断。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GlobalSearchService {

    /** 每类返回上限的兜底封顶，防止前端传入过大 limit。 */
    private static final int MAX_LIMIT = 20;

    private final AgentService agentService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KbDocumentMapper kbDocumentMapper;
    private final AiTraceMapper aiTraceMapper;
    private final PermissionResolver permissionResolver;
    private final PluginCrudService pluginCrudService;
    private final SkillTenantService skillTenantService;

    public GlobalSearchResult search(String q, int limit) {
        GlobalSearchResult result = new GlobalSearchResult();
        String kw = q == null ? "" : q.trim();
        if (kw.isEmpty()) {
            // 空查询不打库，直接返回三个空数组。
            return result;
        }
        int top = Math.min(Math.max(1, limit), MAX_LIMIT);
        result.setAgents(searchAgents(kw, top));
        result.setDocuments(searchDocuments(kw, top));
        result.setPlugins(searchPlugins(kw, top));
        result.setSkills(searchSkills(kw, top));
        result.setTraces(searchTraces(kw, top));
        return result;
    }

    /** name 或 description 任一非空字段（小写）包含关键词即命中。 */
    private static boolean matches(String lowerKw, String... fields) {
        for (String f : fields) {
            if (f != null && f.toLowerCase(Locale.ROOT).contains(lowerKw)) {
                return true;
            }
        }
        return false;
    }

    /** 插件：复用 PLUGIN RBAC 可见性（与 PluginAdminController.listPlugins 一致），内存按 name/description 模糊。 */
    private List<GlobalSearchResult.PluginHit> searchPlugins(String kw, int top) {
        String lower = kw.toLowerCase(Locale.ROOT);
        List<Plugin> visible = permissionResolver.filterCurrent(
                pluginCrudService.listPlugins(null), ResourceType.PLUGIN, Plugin::getId, Plugin::getCreateUser);
        return visible.stream()
                .filter(p -> matches(lower, p.getName(), p.getDescription()))
                .limit(top)
                .map(p -> {
                    GlobalSearchResult.PluginHit hit = new GlobalSearchResult.PluginHit();
                    hit.setId(p.getId());
                    hit.setName(p.getName());
                    hit.setDescription(p.getDescription());
                    hit.setStatus(p.getStatus());
                    return hit;
                })
                .collect(Collectors.toList());
    }

    /** 技能：复用 SkillTenantService.list（scope=TENANT 或 owner==当前用户），内存按 name/description 模糊。 */
    private List<GlobalSearchResult.SkillHit> searchSkills(String kw, int top) {
        String lower = kw.toLowerCase(Locale.ROOT);
        String tenantId = AdminRequestContext.requireTenantId();
        Long userId = AdminRequestContext.requireUserId();
        List<AiSkill> visible = skillTenantService.list(tenantId, userId, false);
        return visible.stream()
                .filter(s -> matches(lower, s.getName(), s.getDescription()))
                .limit(top)
                .map(s -> {
                    GlobalSearchResult.SkillHit hit = new GlobalSearchResult.SkillHit();
                    hit.setId(s.getId());
                    hit.setName(s.getName());
                    hit.setDescription(s.getDescription());
                    hit.setSkillType(s.getSkillType());
                    hit.setStatus(s.getStatus());
                    return hit;
                })
                .collect(Collectors.toList());
    }

    /** Agent：复用 RBAC 可见性（与 AgentAdminController.agents 一致），再按名称内存过滤。 */
    private List<GlobalSearchResult.AgentHit> searchAgents(String kw, int top) {
        String lower = kw.toLowerCase(Locale.ROOT);
        List<Agent> visible = permissionResolver.filterCurrent(
                agentService.list(null), ResourceType.AGENT, Agent::getId, Agent::getCreateUser);
        return visible.stream()
                .filter(a -> a.getName() != null && a.getName().toLowerCase(Locale.ROOT).contains(lower))
                .limit(top)
                .map(a -> {
                    GlobalSearchResult.AgentHit hit = new GlobalSearchResult.AgentHit();
                    hit.setId(a.getId());
                    hit.setName(a.getName());
                    hit.setStatus(a.getStatus());
                    return hit;
                })
                .collect(Collectors.toList());
    }

    /** 文档：限当前用户可见的知识库（KB 级 RBAC），按 title 模糊；租户隔离由拦截器在 kb_document 上完成。 */
    private List<GlobalSearchResult.DocumentHit> searchDocuments(String kw, int top) {
        List<KnowledgeBase> visibleKbs = permissionResolver.filterCurrent(
                knowledgeBaseService.list(), ResourceType.KNOWLEDGE_BASE,
                KnowledgeBase::getId, KnowledgeBase::getCreateUser);
        if (visibleKbs.isEmpty()) {
            return List.of();
        }
        Map<Long, String> kbNameById = visibleKbs.stream()
                .collect(Collectors.toMap(KnowledgeBase::getId, KnowledgeBase::getName, (a, b) -> a));
        List<Long> kbIds = List.copyOf(kbNameById.keySet());

        Page<KbDocument> page = new Page<>(1, top, false);
        LambdaQueryWrapper<KbDocument> wrapper = new LambdaQueryWrapper<KbDocument>()
                .in(KbDocument::getKbId, kbIds)
                .like(KbDocument::getTitle, kw)
                .orderByDesc(KbDocument::getUpdateTime);
        List<KbDocument> docs = kbDocumentMapper.selectPage(page, wrapper).getRecords();

        return docs.stream().map(d -> {
            GlobalSearchResult.DocumentHit hit = new GlobalSearchResult.DocumentHit();
            hit.setId(d.getId());
            hit.setTitle(d.getTitle());
            hit.setKbId(d.getKbId());
            hit.setKbName(kbNameById.get(d.getKbId()));
            hit.setSourceType(d.getSourceType());
            return hit;
        }).collect(Collectors.toList());
    }

    /** Trace：复用 TraceQueryService 同一套 wrapper + ownerScope（按人私有），租户隔离由拦截器完成。 */
    private List<GlobalSearchResult.TraceHit> searchTraces(String kw, int top) {
        String owner = permissionResolver.ownerScopeOrNull();
        Page<AiTrace> page = new Page<>(1, top, false);
        List<AiTrace> traces = aiTraceMapper.selectPage(page,
                TraceSupport.buildWrapper(null, null, null, kw, null, null)
                        .eq(owner != null, AiTrace::getUserId, owner)).getRecords();
        return traces.stream().map(t -> {
            GlobalSearchResult.TraceHit hit = new GlobalSearchResult.TraceHit();
            hit.setTraceId(t.getTraceId());
            hit.setAgentName(t.getAgentName());
            hit.setStatus(t.getStatus());
            hit.setCreateTime(t.getCreateTime());
            return hit;
        }).collect(Collectors.toList());
    }
}
