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

import java.util.ArrayList;
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

    /** 兼容旧调用：默认对话端语义（只读已发布快照）。 */
    public AgentRuntimeView byId(Long agentId) {
        return byId(agentId, false);
    }

    /**
     * 加载 Agent 运行时视图。
     *
     * @param preview {@code true}=调试台：读 Agent 实时字段（草稿，"保存草稿"即生效）；
     *                {@code false}=对话端：只读发布快照，未发布 / 已下架直接拒绝。
     */
    public AgentRuntimeView byId(Long agentId, boolean preview) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "Agent 不存在或无权访问: " + agentId);
        }
        // 成员只能运行被授权的 Agent；超管放行。挡住「用已知 id 直连未授权 agent 对话」。
        permissionResolver.assertCurrentAccess(ResourceType.AGENT, agentId);
        if ("DISABLED".equals(agent.getStatus())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "Agent 已禁用: " + agent.getCode());
        }

        if (preview) {
            // 调试台：用实时字段 + 实时插件绑定。
            List<Long> pluginIds = listBoundPluginIds(agentId);
            return buildView(agent, agent.getSystemPrompt(), agent.getModel(),
                    agent.getModelParams(), agent.getKbConfig(), pluginIds);
        }

        // 对话端：必须已发布并存在快照，否则拒绝。
        if (!"PUBLISHED".equals(agent.getStatus()) || !StringUtils.hasText(agent.getPublishedSnapshot())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "Agent 尚未发布，无法对话: " + agent.getCode());
        }
        return buildViewFromSnapshot(agent);
    }

    /** 从发布快照还原运行时视图。快照里 modelParams / kbConfig 仍是 JSON 字符串，pluginIds 是发布时冻结的绑定集合。 */
    @SuppressWarnings("unchecked")
    private AgentRuntimeView buildViewFromSnapshot(Agent agent) {
        Map<String, Object> snap;
        try {
            snap = CommonUtil.getObjectMapper().readValue(agent.getPublishedSnapshot(), Map.class);
        } catch (Exception e) {
            log.warn("解析 agent.published_snapshot 失败, agentId={}, error={}", agent.getId(), e.getMessage());
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "Agent 发布快照损坏，请重新发布: " + agent.getCode());
        }
        List<Long> pluginIds = new ArrayList<>();
        if (snap.get("pluginIds") instanceof List<?> l) {
            for (Object o : l) {
                Long id = toLong(o);
                if (id != null) pluginIds.add(id);
            }
        }
        // modelParams / kbConfig 兼容两种表示：Java 发布写入的是 JSON 字符串，
        // 历史 SQL 回填写入的可能是嵌套 JSON 对象（model_params 为 json 列时）。统一归一化成字符串再交给下游解析。
        return buildView(agent,
                asString(snap.get("systemPrompt")),
                asString(snap.get("model")),
                asJsonString(snap.get("modelParams")),
                asJsonString(snap.get("kbConfig")),
                pluginIds);
    }

    /** 用给定的人设/模型/参数/知识库/插件 id 集合拼装运行时视图。插件 id → code 时只放行 PUBLISHED 插件。 */
    private AgentRuntimeView buildView(Agent agent, String systemPrompt, String model,
                                       String modelParams, String kbConfig, List<Long> pluginIds) {
        Set<String> allowedPluginCodes = new HashSet<>();
        if (pluginIds != null && !pluginIds.isEmpty()) {
            LambdaQueryWrapper<Plugin> pluginQuery = new LambdaQueryWrapper<Plugin>()
                    .in(Plugin::getId, pluginIds)
                    .eq(Plugin::getStatus, "PUBLISHED");
            List<Plugin> plugins = pluginMapper.selectList(pluginQuery);
            allowedPluginCodes = plugins.stream().map(Plugin::getCode).collect(Collectors.toSet());
        }

        KbBinding kb = parseKbConfig(kbConfig);

        return AgentRuntimeView.builder()
                .agentId(agent.getId())
                .tenantId(agent.getTenantId())
                .code(agent.getCode())
                .name(agent.getName())
                .systemPrompt(systemPrompt)
                .defaultModel(model)
                .defaultModelParams(parseJsonMap(modelParams))
                .allowedPluginCodes(Collections.unmodifiableSet(allowedPluginCodes))
                .kbIds(kb.kbIds())
                .kbTopK(kb.topK())
                .kbScoreThreshold(kb.scoreThreshold())
                .kbRerank(kb.rerank())
                .build();
    }

    private List<Long> listBoundPluginIds(Long agentId) {
        return agentPluginMapper.selectList(new LambdaQueryWrapper<AgentPlugin>()
                        .eq(AgentPlugin::getAgentId, agentId))
                .stream().map(AgentPlugin::getPluginId).toList();
    }

    private String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 归一化为 JSON 字符串：已是字符串则原样返回；是对象/集合则序列化。供 modelParams / kbConfig 解析前统一形态。 */
    private String asJsonString(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s;
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            log.warn("快照字段序列化失败, value={}, error={}", o, e.getMessage());
            return null;
        }
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

    /** 解析 agent.kb_config（{kbIds:[...], topK, scoreThreshold, rerank}）。 */
    private record KbBinding(Set<Long> kbIds, Integer topK, Double scoreThreshold, Boolean rerank) {}

    @SuppressWarnings("unchecked")
    private KbBinding parseKbConfig(String json) {
        if (!StringUtils.hasText(json)) {
            return new KbBinding(Collections.emptySet(), null, null, null);
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
            return new KbBinding(ids, toInteger(m.get("topK")), toDouble(m.get("scoreThreshold")),
                    toBoolean(m.get("rerank")));
        } catch (Exception e) {
            log.warn("解析 agent.kb_config 失败, json={}, error={}", json, e.getMessage());
            return new KbBinding(Collections.emptySet(), null, null, null);
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

    private Boolean toBoolean(Object o) {
        if (o instanceof Boolean b) return b;
        if (o != null) return Boolean.parseBoolean(String.valueOf(o).trim());
        return null;
    }
}
