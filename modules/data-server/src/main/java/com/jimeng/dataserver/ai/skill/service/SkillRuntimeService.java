package com.jimeng.dataserver.ai.skill.service;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.protocol.AiProtocolAdapter;
import com.jimeng.dataserver.ai.skill.model.ActivationResult;
import com.jimeng.dataserver.ai.skill.model.SkillApplyResult;
import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import com.jimeng.dataserver.ai.skill.model.ToolExecutionResult;
import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import com.jimeng.dataserver.ai.skill.model.ToolUseCall;
import com.jimeng.dataserver.ai.skill.source.ToolPackageRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class SkillRuntimeService {

    @Value("${skill.enabled}")
    private boolean skillEnabled;

    @Value("${skill.explicit-prefix}")
    private String explicitPrefix;

    @Value("${skill.max-selected}")
    private int maxSelected;

    @Value("${skill.skill-system-prompt}")
    private String skillSystemPrompt;

    private final ToolPackageRegistry toolPackageRegistry;
    private final SkillToolExecutorRegistryService skillToolExecutorRegistryService;

    // ------------------------------------------------------------------ public API

    public SkillApplyResult applySkillContext(Map<String, Object> body, AiProtocolAdapter adapter) {
        if (!skillEnabled || body == null) return SkillApplyResult.disabled();

        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> messages) || messages.isEmpty()) {
            return SkillApplyResult.disabled();
        }

        Map<String, ToolPackage> skillMap = aggregateToolPackages();
        if (skillMap.isEmpty()) return SkillApplyResult.disabled();

        List<String> explicitNames = extractExplicitSkillNamesAndStrip(messages);
        if (!explicitNames.isEmpty()) {
            List<ToolPackage> selected = resolveSelectedSkills(skillMap, explicitNames);
            if (selected.isEmpty()) return SkillApplyResult.disabled();
            injectFullSkillContext(body, selected, adapter);
            return SkillApplyResult.activated(toNames(selected));
        }

        // Discovery Phase
        List<ToolPackage> allSkills = selectForDiscovery(skillMap);
        if (allSkills.isEmpty()) return SkillApplyResult.disabled();
        log.info("Discovery Phase skills: {}", toNames(allSkills));
        injectDiscoveryContext(body, allSkills, adapter);
        return SkillApplyResult.discovery(toNames(allSkills));
    }

    public ActivationResult handleActivateSkills(Map<String, Object> body,
                                                  ToolUseCall activateCall,
                                                  Map<String, ToolPackage> skillMap,
                                                  AiProtocolAdapter adapter) {
        List<String> requestedNames = parseSkillNames(activateCall.getInput());

        List<String> validNames = new ArrayList<>();
        List<String> invalidNames = new ArrayList<>();
        List<ToolPackage> selected = new ArrayList<>();
        int limit = Math.max(maxSelected, 1);

        for (String name : requestedNames) {
            ToolPackage pkg = toolPackageRegistry.findByName(skillMap, name);
            if (pkg != null) {
                validNames.add(pkg.getName());
                selected.add(pkg);
            } else {
                invalidNames.add(name);
            }
            if (selected.size() >= limit) break;
        }

        Map<String, Object> toolResultPayload;
        boolean success;
        if (validNames.isEmpty()) {
            success = false;
            toolResultPayload = Map.of("error", "invalid_skill_names",
                    "message", "以下 Skill 名称无效: " + invalidNames);
        } else {
            success = true;
            toolResultPayload = invalidNames.isEmpty()
                    ? Map.of("activated_skills", validNames)
                    : Map.of("activated_skills", validNames, "invalid_skills", invalidNames);
        }

        if (!selected.isEmpty()) {
            injectFullSkillContext(body, selected, adapter);
            adapter.removeToolByName(body, "activate_skills");
        }

        Map<String, Object> toolResultBlock = adapter.buildActivationToolResultBlock(
                activateCall.getToolUseId(), activateCall.getToolName(), toolResultPayload, !success);
        return new ActivationResult(success, validNames, toolResultBlock);
    }

    public List<ToolExecutionResult> executeToolCalls(List<ToolUseCall> toolCalls) {
        return skillToolExecutorRegistryService.executeAll(toolCalls);
    }

    // ------------------------------------------------------------------ internals

    private void injectFullSkillContext(Map<String, Object> body,
                                         List<ToolPackage> skills,
                                         AiProtocolAdapter adapter) {
        String prompt = buildFullSkillPrompt(skills);
        if (StrUtil.isNotBlank(prompt)) adapter.appendSystemContent(body, prompt);
        mergeTools(body, skills, adapter);
        adapter.ensureToolChoiceAuto(body);
    }

    private void injectDiscoveryContext(Map<String, Object> body,
                                         List<ToolPackage> skills,
                                         AiProtocolAdapter adapter) {
        StringBuilder sb = new StringBuilder(skillSystemPrompt).append("\n\n");
        sb.append("以下是可用的 Skill 列表。如果用户的问题需要使用某个 Skill，");
        sb.append("请调用 activate_skills 工具激活对应的 Skill。\n\n");
        for (ToolPackage skill : skills) {
            sb.append("- **").append(skill.getName()).append("**: ").append(skill.getDescription()).append("\n");
        }
        adapter.appendSystemContent(body, sb.toString().trim());

        List<Object> tools = adapter.getToolsList(body);
        tools.add(adapter.buildActivateSkillsToolDef());
        adapter.setToolsList(body, tools);
        adapter.ensureToolChoiceAuto(body);
    }

    private void mergeTools(Map<String, Object> body, List<ToolPackage> skills, AiProtocolAdapter adapter) {
        List<Object> merged = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();

        for (Object toolDef : adapter.getToolsList(body)) {
            String name = normalizeToolName(adapter.getToolName(toolDef));
            if (StrUtil.isNotBlank(name) && names.add(name)) merged.add(toolDef);
        }

        for (ToolPackage skill : skills) {
            for (SkillToolDefinition def : skill.getTools()) {
                if (def == null || StrUtil.isBlank(def.getModelName())) continue;
                if (!names.add(def.getModelName())) continue;
                merged.add(adapter.convertToolDef(def));
            }
        }

        if (!merged.isEmpty()) adapter.setToolsList(body, merged);
    }

    private String buildFullSkillPrompt(List<ToolPackage> skills) {
        if (skills == null || skills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(skillSystemPrompt);
        for (ToolPackage skill : skills) {
            sb.append("\n[SKILL: ").append(skill.getName()).append("]\n");
            if (StrUtil.isNotBlank(skill.getDescription())) {
                sb.append("描述: ").append(skill.getDescription()).append("\n");
            }
            if (StrUtil.isNotBlank(skill.getBody())) {
                sb.append(skill.getBody()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private List<ToolPackage> selectForDiscovery(Map<String, ToolPackage> skillMap) {
        List<ToolPackage> all = new ArrayList<>(skillMap.values());
        int limit = Math.max(maxSelected, 1);
        return all.size() <= limit ? all : new ArrayList<>(all.subList(0, limit));
    }

    private List<ToolPackage> resolveSelectedSkills(Map<String, ToolPackage> skillMap,
                                                       List<String> explicitNames) {
        Set<String> dedup = new LinkedHashSet<>(explicitNames);
        List<ToolPackage> selected = new ArrayList<>();
        for (String name : dedup) {
            ToolPackage matched = toolPackageRegistry.findByName(skillMap, name);
            if (matched == null) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST, "未找到skill: " + name);
            }
            selected.add(matched);
            if (selected.size() >= Math.max(maxSelected, 1)) break;
        }
        return selected;
    }

    private List<String> extractExplicitSkillNamesAndStrip(List<?> messages) {
        if (messages == null || messages.isEmpty()) return Collections.emptyList();
        Pattern detect = buildDetectPattern();
        Pattern strip = buildStripPattern();
        List<String> names = new ArrayList<>();
        for (Object messageObj : messages) {
            if (!(messageObj instanceof Map<?, ?> rawMsg)) continue;
            String role = rawMsg.get("role") == null ? "" : String.valueOf(rawMsg.get("role"));
            if (!"user".equalsIgnoreCase(role)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) rawMsg;
            Object contentObj = message.get("content");
            if (contentObj instanceof String text) {
                names.addAll(extractNames(text, detect));
                message.put("content", stripMentions(text, strip));
            } else if (contentObj instanceof List<?> contentList) {
                for (Object blockObj : contentList) {
                    if (!(blockObj instanceof Map<?, ?> rawBlock)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> block = (Map<String, Object>) rawBlock;
                    if (!"text".equals(block.get("type"))) continue;
                    String text = block.get("text") == null ? "" : String.valueOf(block.get("text"));
                    names.addAll(extractNames(text, detect));
                    block.put("text", stripMentions(text, strip));
                }
            }
        }
        return names;
    }

    private List<String> extractNames(String text, Pattern pattern) {
        if (StrUtil.isBlank(text)) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (StrUtil.isNotBlank(name)) names.add(name.trim());
        }
        return names;
    }

    private String stripMentions(String text, Pattern strip) {
        if (text == null) return "";
        return strip.matcher(text).replaceAll("").trim().replaceAll("[\\t ]{2,}", " ");
    }

    private List<String> parseSkillNames(Map<String, Object> input) {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        Object obj = input.get("skill_names");
        if (!(obj instanceof List<?> list) || list.isEmpty()) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                String name = String.valueOf(item).trim();
                if (!name.isEmpty()) names.add(name);
            }
        }
        return names;
    }

    private Pattern buildDetectPattern() {
        String prefix = StrUtil.isBlank(explicitPrefix) ? "$" : explicitPrefix;
        return Pattern.compile("(?<!\\S)" + Pattern.quote(prefix) + "([A-Za-z][A-Za-z0-9_-]{0,63})");
    }

    private Pattern buildStripPattern() {
        String prefix = StrUtil.isBlank(explicitPrefix) ? "$" : explicitPrefix;
        return Pattern.compile("(?<!\\S)" + Pattern.quote(prefix) + "[A-Za-z][A-Za-z0-9_-]{0,63}");
    }

    private String normalizeToolName(String source) {
        if (source == null) return "";
        StringBuilder sb = new StringBuilder(source.length());
        for (char c : source.toCharArray()) {
            boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            sb.append(valid ? c : '_');
        }
        String n = sb.toString().trim();
        return n.length() > 128 ? n.substring(0, 128) : n;
    }

    private List<String> toNames(List<ToolPackage> skills) {
        return skills.stream().map(ToolPackage::getName).toList();
    }

    /**
     * 暴露给 AiConversationLoop：拿到当前请求的工具包视图（包含 Skill + 插件，
     * 已按 AgentContext 过滤——只暴露 Agent 绑定的插件）。
     */
    public Map<String, ToolPackage> aggregateToolPackages() {
        Map<String, ToolPackage> all = toolPackageRegistry.aggregate();
        return filterByAgentAllowlist(all);
    }

    /**
     * 按当前 AgentContext 过滤：
     * <ul>
     *   <li>没有 Agent 上下文 → 不过滤（兼容直接调 Claude 不带 agent_id 的旧用法）</li>
     *   <li>有 Agent 上下文 → Skill（tenantId=null）全部保留；插件只保留 Agent 绑定的</li>
     * </ul>
     */
    private Map<String, ToolPackage> filterByAgentAllowlist(Map<String, ToolPackage> packages) {
        AgentRuntimeView agent = AgentContext.get();
        if (agent == null) return packages;
        if (agent.getAllowedPluginCodes() == null) return packages;

        java.util.LinkedHashMap<String, ToolPackage> filtered = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ToolPackage> e : packages.entrySet()) {
            ToolPackage pkg = e.getValue();
            if (pkg.getTenantId() == null) {
                // Skill 全局可见
                filtered.put(e.getKey(), pkg);
            } else if (agent.getAllowedPluginCodes().contains(pkg.getName())) {
                // 插件按 Agent 绑定过滤
                filtered.put(e.getKey(), pkg);
            }
        }
        return filtered;
    }
}
