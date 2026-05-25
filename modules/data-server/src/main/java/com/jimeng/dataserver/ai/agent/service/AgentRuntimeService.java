package com.jimeng.dataserver.ai.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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

    public AgentRuntimeView byId(Long agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "Agent 不存在或无权访问: " + agentId);
        }
        if ("DISABLED".equals(agent.getStatus())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "Agent 已禁用: " + agent.getCode());
        }

        // 拿到绑定的插件
        LambdaQueryWrapper<AgentPlugin> bindingQuery = new LambdaQueryWrapper<AgentPlugin>()
                .eq(AgentPlugin::getAgentId, agentId);
        List<AgentPlugin> bindings = agentPluginMapper.selectList(bindingQuery);

        Set<String> allowedPluginCodes = new HashSet<>();
        Map<String, String> credentialAliases = new HashMap<>();

        if (!bindings.isEmpty()) {
            List<Long> pluginIds = bindings.stream().map(AgentPlugin::getPluginId).toList();
            LambdaQueryWrapper<Plugin> pluginQuery = new LambdaQueryWrapper<Plugin>()
                    .in(Plugin::getId, pluginIds)
                    .eq(Plugin::getStatus, "PUBLISHED");
            List<Plugin> plugins = pluginMapper.selectList(pluginQuery);
            Map<Long, Plugin> pluginById = plugins.stream()
                    .collect(Collectors.toMap(Plugin::getId, Function.identity()));

            for (AgentPlugin b : bindings) {
                Plugin p = pluginById.get(b.getPluginId());
                if (p == null) continue;  // 插件未发布或已删
                allowedPluginCodes.add(p.getCode());
                if (StringUtils.hasText(b.getCredentialAlias())) {
                    credentialAliases.put(p.getCode(), b.getCredentialAlias());
                }
            }
        }

        return AgentRuntimeView.builder()
                .agentId(agent.getId())
                .tenantId(agent.getTenantId())
                .code(agent.getCode())
                .name(agent.getName())
                .systemPrompt(agent.getSystemPrompt())
                .defaultModel(agent.getModel())
                .defaultModelParams(parseJsonMap(agent.getModelParams()))
                .allowedPluginCodes(Collections.unmodifiableSet(allowedPluginCodes))
                .pluginCredentialAliases(Collections.unmodifiableMap(credentialAliases))
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
}
