package com.jimeng.dataserver.ai.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.admin.rbac.grant.service.CreatorGrantService;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.AgentPlugin;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.AgentPluginMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final CreatorGrantService creatorGrantService;

    // ------------------------------------------------------------------ Agent CRUD

    @Transactional
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
        // 先释放被【软删行】占用的同代号唯一键：逻辑删除(deleted=1)后死行仍占着
        // uk_agent_tenant_code(tenant_id, code)，不释放就无法重建删过的代号（且列表看不到死行）。
        // 无死行时返回 0、无副作用。
        agentMapper.releaseDeletedCode(agent.getCode());
        try {
            agentMapper.insert(agent);
        } catch (DuplicateKeyException e) {
            // 释放后仍冲突 → 占用者是【活跃】Agent（可能是同租户其他成员的、当前用户无权见）。
            // 转成业务错误，前端会直接弹出该文案，而不是吞掉 SQL 原始异常。
            throw new ServiceException(
                    ExceptionCode.INVALID_REQUEST, "Agent 代号「" + agent.getCode() + "」已存在，请换一个");
        }
        // 成员自授权：否则建完 Agent 后列表过滤不到、读详情 assertCurrentAccess 抛 4001。
        creatorGrantService.grantNewResourceToCreator(ResourceType.AGENT, agent.getId());
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

    /**
     * 发布：冻结当前实时配置 + 插件绑定为发布快照，并置 status=PUBLISHED。
     *
     * <p>对话端只读这份快照（见 {@link AgentRuntimeService#byId(Long, boolean)}），
     * 因此发布前的"保存草稿"只在调试台生效，发布后改动才对终端用户可见。
     */
    @Transactional
    public Agent publish(Long id) {
        Agent agent = getById(id);
        agent.setPublishedSnapshot(buildPublishSnapshot(agent));
        agent.setStatus("PUBLISHED");
        agentMapper.updateById(agent);
        return agent;
    }

    public Agent unpublish(Long id) {
        Agent agent = getById(id);
        // 下架仅改状态、保留旧快照（方便再次发布对比）；对话端凭 status!=PUBLISHED 即拒绝，故快照留着也读不到。
        agent.setStatus("DRAFT");
        agentMapper.updateById(agent);
        return agent;
    }

    /**
     * 冻结一份发布快照。modelParams / kbConfig 原样保留为 JSON 字符串
     * （{@link AgentRuntimeService} 已有按字符串解析的逻辑），pluginIds 取发布那一刻的绑定集合。
     */
    private String buildPublishSnapshot(Agent agent) {
        List<AgentPlugin> bindings = agentPluginMapper.selectList(
                new LambdaQueryWrapper<AgentPlugin>().eq(AgentPlugin::getAgentId, agent.getId()));
        List<Long> pluginIds = bindings.stream().map(AgentPlugin::getPluginId).toList();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("code", agent.getCode());
        snapshot.put("name", agent.getName());
        snapshot.put("systemPrompt", agent.getSystemPrompt());
        snapshot.put("model", agent.getModel());
        snapshot.put("modelParams", agent.getModelParams());
        snapshot.put("kbConfig", agent.getKbConfig());
        snapshot.put("pluginIds", pluginIds);
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "生成发布快照失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 未发布草稿标记

    /**
     * 给一批 Agent 回填 {@code hasUnpublishedChanges}：是否「已发布但实时配置/插件绑定领先于发布快照」。
     * 批量加载插件绑定，避免逐个查询。
     */
    public void attachDirtyFlag(List<Agent> agents) {
        if (agents == null || agents.isEmpty()) {
            return;
        }
        List<Long> ids = agents.stream().map(Agent::getId).toList();
        Map<Long, Set<Long>> bindingsByAgent = new HashMap<>();
        agentPluginMapper.selectList(new LambdaQueryWrapper<AgentPlugin>().in(AgentPlugin::getAgentId, ids))
                .forEach(b -> bindingsByAgent
                        .computeIfAbsent(b.getAgentId(), k -> new HashSet<>())
                        .add(b.getPluginId()));
        for (Agent a : agents) {
            a.setHasUnpublishedChanges(
                    computeDirty(a, bindingsByAgent.getOrDefault(a.getId(), Collections.emptySet())));
        }
    }

    /** 单个 Agent 回填草稿标记。 */
    public void attachDirtyFlag(Agent agent) {
        if (agent == null) {
            return;
        }
        Set<Long> livePluginIds = new HashSet<>(agentPluginMapper
                .selectList(new LambdaQueryWrapper<AgentPlugin>().eq(AgentPlugin::getAgentId, agent.getId()))
                .stream().map(AgentPlugin::getPluginId).toList());
        agent.setHasUnpublishedChanges(computeDirty(agent, livePluginIds));
    }

    /**
     * 是否存在未发布的草稿改动：仅对 PUBLISHED 生效；逐字段做语义比较（modelParams/kbConfig 归一成 Map 比，
     * pluginIds 当集合比），避免 JSON 字符串格式/键序差异造成误判。
     */
    private boolean computeDirty(Agent agent, Set<Long> livePluginIds) {
        if (!"PUBLISHED".equals(agent.getStatus())) {
            return false;
        }
        if (!StringUtils.hasText(agent.getPublishedSnapshot())) {
            return true; // 已发布但无快照（异常/历史数据）→ 视为待发布
        }
        Map<String, Object> snap;
        try {
            snap = CommonUtil.getObjectMapper().readValue(agent.getPublishedSnapshot(), Map.class);
        } catch (Exception e) {
            return true; // 快照损坏 → 提示重新发布
        }
        if (!strEq(agent.getCode(), snap.get("code"))) return true;
        if (!strEq(agent.getName(), snap.get("name"))) return true;
        if (!strEq(agent.getSystemPrompt(), snap.get("systemPrompt"))) return true;
        if (!strEq(agent.getModel(), snap.get("model"))) return true;
        if (!jsonEq(agent.getModelParams(), snap.get("modelParams"))) return true;
        if (!jsonEq(agent.getKbConfig(), snap.get("kbConfig"))) return true;
        return !livePluginIds.equals(toLongSet(snap.get("pluginIds")));
    }

    /** 字符串语义相等：null 与 "" 视为相同。 */
    private boolean strEq(String live, Object snap) {
        String a = live == null ? "" : live;
        String b = snap == null ? "" : String.valueOf(snap);
        return a.equals(b);
    }

    /** 把「JSON 字符串 或 已解析对象」都归一成 Map 后比较，避免格式/键序差异。 */
    private boolean jsonEq(String liveJson, Object snapVal) {
        return toMap(liveJson).equals(toMap(snapVal));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        if (o instanceof String s && StringUtils.hasText(s)) {
            try {
                return CommonUtil.getObjectMapper().readValue(s, Map.class);
            } catch (Exception e) {
                return Collections.emptyMap();
            }
        }
        return Collections.emptyMap();
    }

    private Set<Long> toLongSet(Object o) {
        Set<Long> set = new HashSet<>();
        if (o instanceof List<?> l) {
            for (Object e : l) {
                if (e instanceof Number n) {
                    set.add(n.longValue());
                } else if (e != null) {
                    try {
                        set.add(Long.parseLong(String.valueOf(e).trim()));
                    } catch (NumberFormatException ignore) {
                        // 跳过非法 id
                    }
                }
            }
        }
        return set;
    }

    // ------------------------------------------------------------------ Agent-Plugin 绑定

    public AgentPlugin bindPlugin(Long agentId, Long pluginId) {
        // 幂等：已绑定直接返回
        LambdaQueryWrapper<AgentPlugin> wrapper = new LambdaQueryWrapper<AgentPlugin>()
                .eq(AgentPlugin::getAgentId, agentId)
                .eq(AgentPlugin::getPluginId, pluginId);
        AgentPlugin existing = agentPluginMapper.selectOne(wrapper);
        if (existing != null) {
            return existing;
        }
        AgentPlugin binding = new AgentPlugin();
        binding.setAgentId(agentId);
        binding.setPluginId(pluginId);
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
