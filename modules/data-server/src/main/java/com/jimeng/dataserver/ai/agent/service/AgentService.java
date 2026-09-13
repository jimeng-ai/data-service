package com.jimeng.dataserver.ai.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.admin.rbac.grant.service.CreatorGrantService;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.AgentConnection;
import com.jimeng.persistence.entity.AgentSkill;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.AgentConnectionMapper;
import com.jimeng.persistence.mapper.AgentSkillMapper;
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
 * Agent CRUD + Agent-技能/连接绑定。
 *
 * <p>所有方法依赖 MyBatis-Plus 多租户拦截器自动注入 {@code WHERE tenant_id = ?}，
 * 业务代码不显式写租户过滤；跨租户访问会自动返回 0 行 / 404。
 */
@Service
@RequiredArgsConstructor
public class AgentService {

    /** 新建 Agent 的默认模型；须与前端 AVAILABLE_MODELS[0].value 一致。 */
    private static final String DEFAULT_AGENT_MODEL = "claude-opus-4-7";

    private final AgentMapper agentMapper;
    private final AgentSkillMapper agentSkillMapper;
    private final AgentConnectionMapper agentConnectionMapper;
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
        if (!StringUtils.hasText(agent.getModel())) {
            // 默认模型：新建对话框不填模型，这里兜底落库，避免列表/运行时 model 为空。
            // 取值须与前端可选项 AVAILABLE_MODELS[0] 保持一致，否则编辑器下拉匹配不上。
            agent.setModel(DEFAULT_AGENT_MODEL);
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
                    ExceptionCode.INVALID_REQUEST,
                    "Agent 代号「" + agent.getCode() + "」已被占用（可能属于你无权查看的部门），请换一个");
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

    /** 内置构建器 Agent 的 code（Agent 构建器 / Skill 构建器）：内部专用，不在「选择 Agent」列表与对话历史中展示。 */
    public static final java.util.Set<String> INTERNAL_AGENT_CODES =
            java.util.Set.of("__agent_builder__", "__skill_builder__");

    /** 内置构建器 Agent 的展示名快照：用于按会话 agent_name 快照过滤历史会话（含已被硬删的旧构建器 Agent 的会话）。 */
    public static final java.util.Set<String> INTERNAL_AGENT_NAMES =
            java.util.Set.of("Agent 构建器", "Skill 构建器");

    public List<Agent> list(String status) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(Agent::getStatus, status);
        }
        // 排除内置构建器 Agent——由「AI 生成 Skill/Agent」入口专用，不应出现在普通选择列表。
        wrapper.notIn(Agent::getCode, INTERNAL_AGENT_CODES);
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
     * （{@link AgentRuntimeService} 已有按字符串解析的逻辑），skillIds 取发布那一刻的绑定集合。
     */
    private String buildPublishSnapshot(Agent agent) {
        List<Long> skillIds = agentSkillMapper.selectList(
                        new LambdaQueryWrapper<AgentSkill>().eq(AgentSkill::getAgentId, agent.getId()))
                .stream().map(AgentSkill::getSkillId).toList();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("code", agent.getCode());
        snapshot.put("name", agent.getName());
        snapshot.put("systemPrompt", agent.getSystemPrompt());
        snapshot.put("model", agent.getModel());
        snapshot.put("modelParams", agent.getModelParams());
        snapshot.put("kbConfig", agent.getKbConfig());
        // 从本次发布起，快照里【一定】有 skillIds 键（哪怕是空数组）。
        // AgentRuntimeService 靠"键在不在"区分「老快照，回落实时绑定」与「明确绑定为空」，
        // 所以这里绝不能因为空集合就省略这个键。
        snapshot.put("skillIds", skillIds);
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "生成发布快照失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 未发布草稿标记

    /**
     * 给一批 Agent 回填 {@code hasUnpublishedChanges}：是否「已发布但实时配置/技能绑定领先于发布快照」。
     * 批量加载技能绑定，避免逐个查询。
     */
    public void attachDirtyFlag(List<Agent> agents) {
        if (agents == null || agents.isEmpty()) {
            return;
        }
        List<Long> ids = agents.stream().map(Agent::getId).toList();
        Map<Long, Set<Long>> skillsByAgent = new HashMap<>();
        agentSkillMapper.selectList(new LambdaQueryWrapper<AgentSkill>().in(AgentSkill::getAgentId, ids))
                .forEach(b -> skillsByAgent
                        .computeIfAbsent(b.getAgentId(), k -> new HashSet<>())
                        .add(b.getSkillId()));
        for (Agent a : agents) {
            a.setHasUnpublishedChanges(computeDirty(a,
                    skillsByAgent.getOrDefault(a.getId(), Collections.emptySet())));
        }
    }

    /** 单个 Agent 回填草稿标记。 */
    public void attachDirtyFlag(Agent agent) {
        if (agent == null) {
            return;
        }
        Set<Long> liveSkillIds = new HashSet<>(agentSkillMapper
                .selectList(new LambdaQueryWrapper<AgentSkill>().eq(AgentSkill::getAgentId, agent.getId()))
                .stream().map(AgentSkill::getSkillId).toList());
        agent.setHasUnpublishedChanges(computeDirty(agent, liveSkillIds));
    }

    /**
     * 是否存在未发布的草稿改动：仅对 PUBLISHED 生效；逐字段做语义比较（modelParams/kbConfig 归一成 Map 比，
     * skillIds 当集合比），避免 JSON 字符串格式/键序差异造成误判。
     */
    private boolean computeDirty(Agent agent, Set<Long> liveSkillIds) {
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
        // 老快照没有 skillIds 键 —— 此时运行时是「回落实时绑定」，即快照与实时本就一致，
        // 不能因为键缺失就判成 dirty，否则每个历史 agent 都会一直显示「有未发布改动」。
        if (!snap.containsKey("skillIds")) return false;
        return !liveSkillIds.equals(toLongSet(snap.get("skillIds")));
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

    /**
     * 绑定技能（幂等）。决定该 Agent 能看到哪些【租户】技能；平台技能不走绑定、恒可见。
     *
     * <p>绑定只收窄可见性、不放大：scope/owner 过滤在绑定过滤之前执行，
     * PRIVATE 技能即便被绑到 Agent 上，也仍然只对其 owner 可见。
     */
    public AgentSkill bindSkill(Long agentId, Long skillId) {
        LambdaQueryWrapper<AgentSkill> wrapper = new LambdaQueryWrapper<AgentSkill>()
                .eq(AgentSkill::getAgentId, agentId)
                .eq(AgentSkill::getSkillId, skillId);
        AgentSkill existing = agentSkillMapper.selectOne(wrapper);
        if (existing != null) {
            return existing;
        }
        AgentSkill binding = new AgentSkill();
        binding.setAgentId(agentId);
        binding.setSkillId(skillId);
        agentSkillMapper.insert(binding);
        return binding;
    }

    public int unbindSkill(Long agentId, Long skillId) {
        return agentSkillMapper.delete(new LambdaQueryWrapper<AgentSkill>()
                .eq(AgentSkill::getAgentId, agentId)
                .eq(AgentSkill::getSkillId, skillId));
    }

    public List<AgentSkill> listSkillBindings(Long agentId) {
        return agentSkillMapper.selectList(new LambdaQueryWrapper<AgentSkill>()
                .eq(AgentSkill::getAgentId, agentId));
    }

    /**
     * 授予 Agent 一条外部连接。幂等。
     *
     * <p><b>三步而不是两步，中间那步是必需的</b>：软删除的授权行仍然占着
     * {@code uk_agent_connection_tenant_agent_conn}（唯一键不含 deleted），而 selectOne 被
     * 自动加上 {@code deleted = 0} 之后看不见它。少了复活这一步，「撤销 → 再授权」会直接撞唯一键，
     * 对用户表现为「取消过的连接再也加不回去」。详见 {@code AgentConnectionMapper#reviveGrant}。
     */
    public AgentConnection grantConnection(Long agentId, Long connectionId) {
        LambdaQueryWrapper<AgentConnection> w = new LambdaQueryWrapper<AgentConnection>()
                .eq(AgentConnection::getAgentId, agentId)
                .eq(AgentConnection::getConnectionId, connectionId);
        AgentConnection existing = agentConnectionMapper.selectOne(w);
        if (existing != null) return existing;
        // 有软删行就复活它，而不是插一条新的——插会撞唯一键。
        if (agentConnectionMapper.reviveGrant(agentId, connectionId) > 0) {
            AgentConnection revived = agentConnectionMapper.selectOne(w);
            if (revived != null) return revived;
        }
        AgentConnection g = new AgentConnection();
        g.setAgentId(agentId);
        g.setConnectionId(connectionId);
        agentConnectionMapper.insert(g);
        return g;
    }

    public int revokeConnection(Long agentId, Long connectionId) {
        return agentConnectionMapper.delete(new LambdaQueryWrapper<AgentConnection>()
                .eq(AgentConnection::getAgentId, agentId)
                .eq(AgentConnection::getConnectionId, connectionId));
    }

    public List<AgentConnection> listConnectionGrants(Long agentId) {
        return agentConnectionMapper.selectList(new LambdaQueryWrapper<AgentConnection>()
                .eq(AgentConnection::getAgentId, agentId));
    }
}
