package com.jimeng.dataserver.ai.search;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.dataserver.ai.agent.service.AgentService;
import com.jimeng.dataserver.ai.plugin.service.PluginCrudService;
import com.jimeng.dataserver.ai.rag.service.KnowledgeBaseService;
import com.jimeng.dataserver.ai.search.dto.GlobalSearchResult;
import com.jimeng.dataserver.ai.search.service.GlobalSearchService;
import com.jimeng.dataserver.ai.skill.service.SkillTenantService;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.entity.Plugin;
import com.jimeng.persistence.mapper.AiTraceMapper;
import com.jimeng.persistence.mapper.KbDocumentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 全局搜索新增的「插件 / 技能」两类命中的单元测试。聚焦验证：
 * 1) 按 description 命中（name 不含关键词也能搜到）；
 * 2) limit 截断。
 * agents/documents/traces 的依赖全部 mock 成空，避免噪声。
 */
class GlobalSearchServiceTest {

    @BeforeEach
    void setUpContext() {
        // searchSkills 会调 AdminRequestContext.requireTenantId()/requireUserId()，
        // 单测无 servlet 请求，需用 TenantContext + 异步 userId 把上下文补上，否则抛「无请求上下文」。
        TenantContext.set("t1");
        AdminRequestContext.setAsyncUserId(1L);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        AdminRequestContext.clearAsyncUserId();
    }

    private Plugin plugin(long id, String name, String desc) {
        Plugin p = new Plugin();
        p.setId(id);
        p.setName(name);
        p.setDescription(desc);
        p.setStatus("PUBLISHED");
        return p;
    }

    private AiSkill skill(long id, String name, String desc) {
        AiSkill s = new AiSkill();
        s.setId(id);
        s.setName(name);
        s.setDescription(desc);
        s.setSkillType("DOER");
        s.setStatus("ACTIVE");
        return s;
    }

    private GlobalSearchService newService(PluginCrudService plugins, SkillTenantService skills,
                                           PermissionResolver resolver) {
        AgentService agentService = Mockito.mock(AgentService.class);
        when(agentService.list(any())).thenReturn(List.of());
        KnowledgeBaseService kbService = Mockito.mock(KnowledgeBaseService.class);
        when(kbService.list()).thenReturn(List.of()); // visibleKbs 空 → documents 直接空
        KbDocumentMapper kbDocumentMapper = Mockito.mock(KbDocumentMapper.class);
        AiTraceMapper aiTraceMapper = Mockito.mock(AiTraceMapper.class);
        when(aiTraceMapper.selectPage(any(), any())).thenReturn(new Page<>());
        when(resolver.ownerScopeOrNull()).thenReturn(null);
        // filterCurrent 透传：直接返回传入的 list（第 1 个参数）
        when(resolver.filterCurrent(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        return new GlobalSearchService(agentService, kbService, kbDocumentMapper,
                aiTraceMapper, resolver, plugins, skills);
    }

    @Test
    void matchesPluginByDescription() {
        PluginCrudService plugins = Mockito.mock(PluginCrudService.class);
        when(plugins.listPlugins(null)).thenReturn(List.of(
                plugin(1, "无关名字", "用于查询天气预报"),
                plugin(2, "另一个", "发送邮件")));
        SkillTenantService skills = Mockito.mock(SkillTenantService.class);
        when(skills.list(any(), any(), eq(false))).thenReturn(List.of());
        PermissionResolver resolver = Mockito.mock(PermissionResolver.class);

        GlobalSearchResult r = newService(plugins, skills, resolver).search("天气", 5);

        assertThat(r.getPlugins()).extracting(GlobalSearchResult.PluginHit::getId).containsExactly(1L);
    }

    @Test
    void limitsSkillResults() {
        PluginCrudService plugins = Mockito.mock(PluginCrudService.class);
        when(plugins.listPlugins(null)).thenReturn(List.of());
        SkillTenantService skills = Mockito.mock(SkillTenantService.class);
        when(skills.list(any(), any(), eq(false))).thenReturn(
                IntStream.range(0, 10).mapToObj(i -> skill(i, "报告生成器" + i, ""))
                        .collect(Collectors.toList()));
        PermissionResolver resolver = Mockito.mock(PermissionResolver.class);

        GlobalSearchResult r = newService(plugins, skills, resolver).search("报告", 3);

        assertThat(r.getSkills()).hasSize(3);
    }
}
