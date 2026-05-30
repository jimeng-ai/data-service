package com.jimeng.dataserver.admin.rbac.grantable.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
import com.jimeng.dataserver.admin.rbac.grantable.dto.ModuleOption;
import com.jimeng.dataserver.admin.rbac.grantable.dto.ResourceOption;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.KnowledgeBase;
import com.jimeng.persistence.entity.Plugin;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.KnowledgeBaseMapper;
import com.jimeng.persistence.mapper.PluginMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 企业门户 —— 授权 UI 的候选数据源。返回当前租户（超管所在企业）的可授权资源。
 *
 * <p>Agent / KnowledgeBase / Plugin 三表在租户白名单内，查询自动按当前 {@code X-Tenant-Id} 过滤，
 * 因此天然只返回本企业的资源。
 */
@Tag(name = "企业-可授权资源", description = "授权 UI 候选：智能体 / 知识库 / 插件 / 模块")
@RestController
@RequestMapping("/data/admin/rbac/grantable")
@RequiredArgsConstructor
public class GrantableResourceController {

    private final AgentMapper agentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final PluginMapper pluginMapper;
    private final SuperAdminGuard superAdminGuard;

    @Operation(summary = "本企业智能体（可授权）")
    @GetMapping("/agents")
    public List<ResourceOption> agents() {
        superAdminGuard.requireSuperAdmin();
        return agentMapper.selectList(Wrappers.<Agent>lambdaQuery().orderByDesc(Agent::getCreateTime))
                .stream().map(a -> new ResourceOption(a.getId(), a.getCode(), a.getName())).toList();
    }

    @Operation(summary = "本企业知识库（可授权）")
    @GetMapping("/knowledge-bases")
    public List<ResourceOption> knowledgeBases() {
        superAdminGuard.requireSuperAdmin();
        return knowledgeBaseMapper.selectList(Wrappers.<KnowledgeBase>lambdaQuery().orderByDesc(KnowledgeBase::getCreateTime))
                .stream().map(k -> new ResourceOption(k.getId(), null, k.getName())).toList();
    }

    @Operation(summary = "本企业插件（可授权）")
    @GetMapping("/plugins")
    public List<ResourceOption> plugins() {
        superAdminGuard.requireSuperAdmin();
        return pluginMapper.selectList(Wrappers.<Plugin>lambdaQuery().orderByDesc(Plugin::getCreateTime))
                .stream().map(p -> new ResourceOption(p.getId(), p.getCode(), p.getName())).toList();
    }

    @Operation(summary = "可授权模块列表")
    @GetMapping("/modules")
    public List<ModuleOption> modules() {
        superAdminGuard.requireSuperAdmin();
        return List.of(
                new ModuleOption(PlatformConstant.MODULE_AGENT, "智能体"),
                new ModuleOption(PlatformConstant.MODULE_KB, "知识库"),
                new ModuleOption(PlatformConstant.MODULE_CHAT, "对话"),
                new ModuleOption(PlatformConstant.MODULE_PLUGIN, "插件")
        );
    }
}
