package com.jimeng.dataserver.ai.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.AgentPlugin;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.AgentPluginMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Agent CRUD + Agent-插件绑定。
 *
 * <p>所有方法依赖 MyBatis-Plus 多租户拦截器自动注入 {@code WHERE tenant_id = ?}，
 * 业务代码不显式写租户过滤；跨租户访问会自动返回 0 行 / 404。
 */
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentMapper agentMapper;
    private final AgentPluginMapper agentPluginMapper;

    // ------------------------------------------------------------------ Agent CRUD

    public Agent create(Agent agent) {
        if (!StringUtils.hasText(agent.getCode())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "Agent code 不能为空");
        }
        if (!StringUtils.hasText(agent.getName())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "Agent name 不能为空");
        }
        if (!StringUtils.hasText(agent.getStatus())) {
            agent.setStatus("DRAFT");
        }
        agentMapper.insert(agent);
        return agent;
    }

    public Agent update(Agent agent) {
        if (agent.getId() == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "Agent id 不能为空");
        }
        agentMapper.updateById(agent);
        return agentMapper.selectById(agent.getId());
    }

    public Agent getById(Long id) {
        Agent agent = agentMapper.selectById(id);
        if (agent == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "Agent 不存在: " + id);
        }
        return agent;
    }

    public List<Agent> list(String status) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(Agent::getStatus, status);
        }
        wrapper.orderByDesc(Agent::getCreateTime);
        return agentMapper.selectList(wrapper);
    }

    public int delete(Long id) {
        return agentMapper.deleteById(id);
    }

    public Agent publish(Long id) {
        Agent agent = getById(id);
        agent.setStatus("PUBLISHED");
        agentMapper.updateById(agent);
        return agent;
    }

    // ------------------------------------------------------------------ Agent-Plugin 绑定

    public AgentPlugin bindPlugin(Long agentId, Long pluginId, String credentialAlias) {
        // 复用 mybatis-plus 的 deleteById/insertId；这里查一下是否已绑过
        LambdaQueryWrapper<AgentPlugin> wrapper = new LambdaQueryWrapper<AgentPlugin>()
                .eq(AgentPlugin::getAgentId, agentId)
                .eq(AgentPlugin::getPluginId, pluginId);
        AgentPlugin existing = agentPluginMapper.selectOne(wrapper);
        if (existing != null) {
            existing.setCredentialAlias(credentialAlias);
            agentPluginMapper.updateById(existing);
            return existing;
        }
        AgentPlugin binding = new AgentPlugin();
        binding.setAgentId(agentId);
        binding.setPluginId(pluginId);
        binding.setCredentialAlias(credentialAlias);
        agentPluginMapper.insert(binding);
        return binding;
    }

    @Transactional
    public int unbindPlugin(Long agentId, Long pluginId) {
        LambdaQueryWrapper<AgentPlugin> wrapper = new LambdaQueryWrapper<AgentPlugin>()
                .eq(AgentPlugin::getAgentId, agentId)
                .eq(AgentPlugin::getPluginId, pluginId);
        return agentPluginMapper.delete(wrapper);
    }

    public List<AgentPlugin> listBindings(Long agentId) {
        LambdaQueryWrapper<AgentPlugin> wrapper = new LambdaQueryWrapper<AgentPlugin>()
                .eq(AgentPlugin::getAgentId, agentId);
        return agentPluginMapper.selectList(wrapper);
    }
}
