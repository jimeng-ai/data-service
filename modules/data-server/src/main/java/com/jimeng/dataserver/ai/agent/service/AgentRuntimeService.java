package com.jimeng.dataserver.ai.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.AgentPlugin;
import com.jimeng.persistence.entity.Plugin;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.AgentPluginMapper;
import com.jimeng.persistence.mapper.PluginMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 运行时服务：给 ClaudeService 提供 byId(agentId) → AgentRuntimeView 的能力。
 *
 * <p>所有查询走 MyBatis-Plus 多租户拦截器，跨租户的 agentId 自动查不到 → 抛 404。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRuntimeService {

    private final AgentMapper agentMapper;
    private final AgentPluginMapper agentPluginMapper;
    private final PluginMapper pluginMapper;
    private final PermissionResolver permissionResolver;

    public AgentRuntimeView byId(Long agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "Agent 不存在或无权访问: " + agentId);
        }
        // 成员只能运行被授权的 Agent；超管放行。挡住「用已知 id 直连未授权 agent 对话」。
        permissionResolver.assertCurrentAccess(ResourceType.AGENT, agentId);
        if ("DISABLED".equals(agent.getStatus())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "Agent 已禁用: " + agent.getCode());
        }

        // 拿到绑定的插件
        LambdaQueryWrapper<AgentPlugin> bindingQuery = new LambdaQueryWrapper<AgentPlugin>()
                .eq(AgentPlugin::getAgentId, agentId);
        List<AgentPlugin> bindings = agentPluginMapper.selectList(bindingQuery);

        Set<String> allowedPluginCodes = new HashSet<>();

        if (!bindings.isEmpty()) {
            List<Long> pluginIds = bindings.stream().map(AgentPlugin::getPluginId).toList();
            LambdaQueryWrapper<Plugin> pluginQuery = new LambdaQueryWrapper<Plugin>()
                    .in(Plugin::getId, pluginIds)
                    .eq(Plugin::getStatus, "PUBLISHED");
            List<Plugin> plugins = pluginMapper.selectList(pluginQuery);
            allowedPluginCodes = plugins.stream().map(Plugin::getCode).collect(Collectors.toSet());
        }

        KbBinding kb = parseKbConfig(agent.getKbConfig());

        return AgentRuntimeView.builder()
                .agentId(agent.getId())
                .tenantId(agent.getTenantId())
                .code(agent.getCode())
                .name(agent.getName())
                .systemPrompt(agent.getSystemPrompt())
                .defaultModel(agent.getModel())
                .defaultModelParams(parseJsonMap(agent.getModelParams()))
                .allowedPluginCodes(Collections.unmodifiableSet(allowedPluginCodes))
                .kbIds(kb.kbIds())
                .kbTopK(kb.topK())
                .kbScoreThreshold(kb.scoreThreshold())
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) {
        if (!StringUtils.hasText(json)) return Collections.emptyMap();
        try {
            return CommonUtil.getObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("解析 agent.model_params 失败, json={}, error={}", json, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** 解析 agent.kb_config（{kbIds:[...], topK, scoreThreshold}）。 */
    private record KbBinding(Set<Long> kbIds, Integer topK, Double scoreThreshold) {}

    @SuppressWarnings("unchecked")
    private KbBinding parseKbConfig(String json) {
        if (!StringUtils.hasText(json)) {
            return new KbBinding(Collections.emptySet(), null, null);
        }
        try {
            Map<String, Object> m = CommonUtil.getObjectMapper().readValue(json, Map.class);
            Set<Long> ids = new LinkedHashSet<>();
            Object list = m.get("kbIds");
            if (list instanceof List<?> l) {
                for (Object o : l) {
                    Long id = toLong(o);
                    if (id != null) ids.add(id);
                }
            }
            return new KbBinding(ids, toInteger(m.get("topK")), toDouble(m.get("scoreThreshold")));
        } catch (Exception e) {
            log.warn("解析 agent.kb_config 失败, json={}, error={}", json, e.getMessage());
            return new KbBinding(Collections.emptySet(), null, null);
        }
    }

    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer toInteger(Object o) {
        if (o instanceof Number n) return n.intValue();
        return null;
    }

    private Double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return null;
    }
}
