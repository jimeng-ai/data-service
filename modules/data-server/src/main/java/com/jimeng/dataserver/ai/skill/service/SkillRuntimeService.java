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
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    private final com.jimeng.dataserver.ai.agent.builder.DraftAgentToolPackage draftAgentToolPackage;
    private final com.jimeng.dataserver.ai.skill.builder.DraftSkillToolPackage draftSkillToolPackage;

    // ------------------------------------------------------------------ public API

    public SkillApplyResult applySkillContext(Map<String, Object> body, AiProtocolAdapter adapter) {
        // 构建器会话：只注入 draft_agent，绕开常规插件/技能聚合与发现（无视全局 skill 开关）。
        if (body != null && Boolean.TRUE.equals(body.remove("__agent_builder_mode__"))) {
            injectFullSkillContext(body, java.util.List.of(draftAgentToolPackage), adapter);
            return SkillApplyResult.activated(java.util.List.of(draftAgentToolPackage.getName()));
        }
        if (body != null && Boolean.TRUE.equals(body.remove("__skill_builder_mode__"))) {
            injectFullSkillContext(body, java.util.List.of(draftSkillToolPackage), adapter);
            return SkillApplyResult.activated(java.util.List.of(draftSkillToolPackage.getName()));
        }
        if (!skillEnabled || body == null) return SkillApplyResult.disabled();

        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> messages) || messages.isEmpty()) {
            return SkillApplyResult.disabled();
        }

        Map<String, ToolPackage> skillMap = aggregateToolPackages();
        if (skillMap.isEmpty()) return SkillApplyResult.disabled();

        // 拆分：kind==PLUGIN 的包（含租户私有插件）直接作为 tool_use 工具注入——绑了就能用，不必先 activate_skills；
        // kind==SKILL 的包（含未来的租户私有 Skill）走「发现 → activate_skills 激活」以免铺满上下文。
        List<ToolPackage> boundPlugins = new ArrayList<>();
        Map<String, ToolPackage> skillOnly = new LinkedHashMap<>();
        for (Map.Entry<String, ToolPackage> e : skillMap.entrySet()) {
            if (e.getValue().getKind() == com.jimeng.dataserver.ai.skill.model.ToolPackageKind.PLUGIN) {
                boundPlugins.add(e.getValue());
            } else {
                skillOnly.put(e.getKey(), e.getValue());
            }
        }
        // 平台级 Skill「rag-knowledge」可见性取决于当前 Agent 是否绑定知识库：绑了→提升为直接注入工具；
        // 没绑→直接摘除（没有库可检索，暴露只会诱导模型盲调 rag.search 失败）。
        resolveRagSkillVisibility(skillOnly, boundPlugins);

        if (!boundPlugins.isEmpty()) {
            injectFullSkillContext(body, boundPlugins, adapter);
            log.info("插件工具直接注入(tool_use): {}", toNames(boundPlugins));
        }

        List<String> explicitNames = extractExplicitSkillNamesAndStrip(messages);
        if (!explicitNames.isEmpty()) {
            List<ToolPackage> selected = resolveSelectedSkills(skillOnly, explicitNames);
            if (!selected.isEmpty()) {
                injectFullSkillContext(body, selected, adapter);
                List<String> names = new ArrayList<>(toNames(selected));
                names.addAll(toNames(boundPlugins));
                return SkillApplyResult.activated(names);
            }
        }

        // Skill 发现阶段：按与用户问题的相关性挑选要展示的 Skill
        String userText = latestUserText(messages);
        List<ToolPackage> discoverySkills = selectForDiscovery(skillOnly, userText);
        if (!discoverySkills.isEmpty()) {
            // 确定性自动激活：挑出「与用户问题词面相关、且最相关的本租户 PROMPT 技能」，直接注入其完整正文，
            // 不再依赖模型自觉调用 activate_skills——这是 ai-image-prompts 这类「该用就得用」的租户技能此前
            // 激活不稳定（约 83%）的根因。其余候选仍走发现，模型可按需再激活。
            ToolPackage auto = pickAutoActivateTenantSkill(discoverySkills, userText);
            if (auto != null) {
                List<ToolPackage> remaining = new ArrayList<>(discoverySkills);
                remaining.remove(auto);
                injectFullSkillContext(body, List.of(auto), adapter);
                if (!remaining.isEmpty()) injectDiscoveryContext(body, remaining, adapter);
                log.info("Auto-activated tenant skill: {}; remaining discovery: {}", auto.getName(), toNames(remaining));
                return remaining.isEmpty()
                        ? SkillApplyResult.autoActivated(List.of(auto.getName()))
                        : SkillApplyResult.discoveryWithAutoActivated(toNames(discoverySkills), List.of(auto.getName()));
            }
            log.info("Discovery Phase skills: {}", toNames(discoverySkills));
            injectDiscoveryContext(body, discoverySkills, adapter);
            return SkillApplyResult.discovery(toNames(discoverySkills));
        }

        // 没有需要发现的 Skill；若已直接注入插件工具，按 activated 让对话循环执行其 tool 调用
        if (!boundPlugins.isEmpty()) {
            return SkillApplyResult.activated(toNames(boundPlugins));
        }
        return SkillApplyResult.disabled();
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

    /** 平台级知识检索 Skill 的名称（SKILL.md frontmatter name）。 */
    private static final String RAG_SKILL_NAME = "rag-knowledge";

    /**
     * 根据当前 Agent 是否绑定知识库，决定平台级 Skill「rag-knowledge」的可见性：
     * <ul>
     *   <li><b>绑定了知识库</b>：把它从「待发现」集合移到「直接注入」集合，让 rag.search 像绑定插件一样
     *       立即可调，省去 discovery→activate_skills 往返；kb_id 已由 system 提示给定 + 执行器兜底。</li>
     *   <li><b>未绑定知识库</b>：直接从候选里摘除——没有库可检索，暴露 RAG 只会诱导模型盲调一次
     *       rag.search 失败（执行器无 kb_id 可解析），徒增困惑。</li>
     *   <li><b>无 Agent 上下文</b>（直接调 Claude 不带 agent_id 的旧用法）：不干预，仍走正常发现流程。</li>
     * </ul>
     * 其它平台 Skill 一律保持原样走发现。
     */
    private void resolveRagSkillVisibility(Map<String, ToolPackage> skillOnly, List<ToolPackage> boundPlugins) {
        AgentRuntimeView agent = AgentContext.get();
        if (agent == null) return;
        boolean kbBound = agent.getKbIds() != null && !agent.getKbIds().isEmpty();
        for (Iterator<Map.Entry<String, ToolPackage>> it = skillOnly.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, ToolPackage> e = it.next();
            if (!RAG_SKILL_NAME.equals(e.getValue().getName())) continue;
            if (kbBound) {
                boundPlugins.add(e.getValue());
                log.info("Agent 绑定知识库，rag-knowledge 提升为直接注入工具 kbIds={}", agent.getKbIds());
            } else {
                log.info("Agent 未绑定知识库，隐藏 rag-knowledge 技能 agentId={}", agent.getAgentId());
            }
            it.remove();
            break;
        }
    }

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
        // 强约束的激活指引：模型常因「内置工具能直接完成」而跳过明显相关的 Skill（例如收到生图请求时
        // 直接调 generate_image，却不先激活『图像提示词优化』Skill）。这里要求：在调用任何其它工具或直接
        // 作答之前，先逐一比对下列 Skill 的适用场景，只要明显相关就必须先 activate_skills 激活并遵循其指引。
        sb.append("下面是当前可用的 Skill 列表（已按与用户请求的相关性挑选）。\n");
        sb.append("**在直接作答或调用其它任何工具（包括 generate_image 等内置工具）之前**，");
        sb.append("先逐一对照每个 Skill 的「适用场景/触发场景」判断它是否与用户当前请求相关：\n");
        sb.append("- 只要某个 Skill 明显相关，你【必须】先调用 activate_skills 激活它，激活后严格遵循该 Skill 的指引再继续后续动作；\n");
        sb.append("- 不要在存在明显相关 Skill 的情况下跳过激活、直接用其它工具或凭空作答；\n");
        sb.append("- 若确实没有相关 Skill，可不激活、正常继续。\n\n");
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
        // 调用工具前先用一句自然语言说明意图，提升可观测性（这是给用户看的说明，不是思维链/分析过程）
        sb.append("\n\n【调用工具的礼貌约定】在发起任何工具调用之前，先用一句简短、自然的话告诉用户")
          .append("你接下来要做什么、为什么需要这个工具（例如“好的，我来帮你查询泉港的实时天气”），")
          .append("然后再发起工具调用。这句话是面向用户的说明，不要输出分析、推理或思维链内容。");
        return sb.toString().trim();
    }

    private static final Pattern WORD_TOKEN = Pattern.compile("[a-z0-9]{2,}");

    /**
     * 发现阶段挑选要展示给模型的 Skill：按与用户问题的词面相关性降序排序，<b>同分时租户自有 Skill 优先于
     * 平台 Skill</b>（避免用户自己的 Skill 被平台 Skill 挤出 {@code skill.max-selected} 上限——这正是
     * 之前「租户 Skill 一直不被发现/调用」的根因），最后截断到上限。
     */
    private List<ToolPackage> selectForDiscovery(Map<String, ToolPackage> skillMap, String query) {
        List<ToolPackage> all = new ArrayList<>(skillMap.values());
        int limit = Math.max(maxSelected, 1);
        if (all.size() <= limit) return all;
        return rankForDiscovery(all, tokenize(query), limit);
    }

    /** 纯函数：按相关性(降序) + 租户优先 稳定排序后取前 limit 个。便于单测。 */
    static List<ToolPackage> rankForDiscovery(List<ToolPackage> all, Set<String> queryTokens, int limit) {
        List<ToolPackage> sorted = new ArrayList<>(all);
        // List.sort 稳定：相关性与租户标记都相同的元素保留原插入顺序。
        sorted.sort(Comparator
                .comparingInt((ToolPackage p) -> -relevanceScore(queryTokens, p))   // 相关性高在前
                .thenComparingInt(p -> p.getTenantId() != null ? 0 : 1));            // 同分租户(0)优先于平台(1)
        return new ArrayList<>(sorted.subList(0, Math.min(limit, sorted.size())));
    }

    /**
     * 从发现候选里挑「确定性自动激活」的本租户 PROMPT 技能：与用户问题有词面相关（relevanceScore&gt;0）、含正文，
     * 取相关性最高的一个；无则返回 null（回退到普通发现 → 模型自行 activate_skills）。
     *
     * <p>仅限<b>本租户技能</b>（tenantId!=null）：平台技能（如 brand-guidelines/design-system）仍走发现，
     * 避免对所有对话强行注入平台技能正文。租户技能是租户为自己用例显式创建的，「该用就得用」，故确定性激活。
     * 静态 + 包级可见，便于单测。
     */
    static ToolPackage pickAutoActivateTenantSkill(List<ToolPackage> discovered, String query) {
        Set<String> qTokens = tokenize(query);
        if (qTokens.isEmpty()) return null;
        ToolPackage best = null;
        int bestScore = 0;
        for (ToolPackage p : discovered) {
            if (p.getTenantId() == null) continue;          // 仅本租户技能
            if (StrUtil.isBlank(p.getBody())) continue;     // 需有正文(指引)才值得注入
            int s = relevanceScore(qTokens, p);
            if (s > bestScore) {
                bestScore = s;
                best = p;
            }
        }
        return bestScore > 0 ? best : null;
    }

    /** Skill 文本(名称+描述)与用户问题词元的交集大小。 */
    private static int relevanceScore(Set<String> queryTokens, ToolPackage pkg) {
        if (queryTokens == null || queryTokens.isEmpty()) return 0;
        Set<String> skillTokens = tokenize(pkg.getName() + " " + pkg.getDescription());
        int score = 0;
        for (String t : skillTokens) {
            if (queryTokens.contains(t)) score++;
        }
        return score;
    }

    /** 把文本切成可比较词元：英文/数字单词(len≥2) + 中文相邻二字组(bigram) + 孤立汉字，全部小写。 */
    static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isEmpty()) return tokens;
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher m = WORD_TOKEN.matcher(lower);
        while (m.find()) tokens.add(m.group());
        for (String run : lower.replaceAll("[^\\u4e00-\\u9fa5]+", " ").trim().split("\\s+")) {
            if (run.isEmpty()) continue;
            if (run.length() == 1) {
                tokens.add(run);
                continue;
            }
            for (int i = 0; i + 1 < run.length(); i++) tokens.add(run.substring(i, i + 2));
        }
        return tokens;
    }

    /** 取最后一条 user 消息文本(content 支持 String 或块数组)，用于 Skill 发现相关性打分。 */
    private static String latestUserText(List<?> messages) {
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!(messages.get(i) instanceof Map<?, ?> msg)) continue;
            if (!"user".equals(String.valueOf(msg.get("role")))) continue;
            Object content = msg.get("content");
            if (content instanceof String s) return s;
            if (content instanceof List<?> blocks) {
                StringBuilder sb = new StringBuilder();
                for (Object b : blocks) {
                    if (b instanceof Map<?, ?> bm && "text".equals(bm.get("type"))) {
                        sb.append(String.valueOf(bm.get("text"))).append(' ');
                    }
                }
                return sb.toString();
            }
            return "";
        }
        return "";
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
     *   <li>有 Agent 上下文 → kind==SKILL 全部保留；kind==PLUGIN 只保留 Agent 绑定的</li>
     * </ul>
     */
    private Map<String, ToolPackage> filterByAgentAllowlist(Map<String, ToolPackage> packages) {
        AgentRuntimeView agent = AgentContext.get();
        if (agent == null) return packages;
        if (agent.getAllowedPluginCodes() == null) return packages;

        java.util.LinkedHashMap<String, ToolPackage> filtered = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ToolPackage> e : packages.entrySet()) {
            ToolPackage pkg = e.getValue();
            if (pkg.getKind() == com.jimeng.dataserver.ai.skill.model.ToolPackageKind.SKILL) {
                // Skill（含未来租户私有 Skill）全部保留
                filtered.put(e.getKey(), pkg);
            } else if (agent.getAllowedPluginCodes().contains(pkg.getName())) {
                // 插件按 Agent 绑定过滤
                filtered.put(e.getKey(), pkg);
            }
        }
        return filtered;
    }
}
