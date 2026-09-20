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
import com.jimeng.dataserver.ai.skill.model.SkillRequirement;
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

    /**
     * 连接器「默认注入目录」+「这一轮命中的口径」的来源。
     *
     * <h4>★ 加这条依赖之前必须确认它闭不上环</h4>
     * 本类身处 {@code ProviderRegistry → ChatClient → AiConversationLoop → SkillRuntimeService →
     * SkillToolExecutorRegistryService → ConnectorToolExecutor} 这条链上，仓库已经被同一个环
     * 咬过两次。{@code ConnectorOverviewService} 只注入四个 MyBatis mapper、
     * {@code ConnectorProperties}（一个 {@code @ConfigurationProperties} 叶子）和
     * {@code MetricRewriter}（纯匹配函数，自己没有任何注入依赖），
     * <b>没有任何一条边能回到 {@code ProviderRegistry} / {@code ClaudeService} / {@code ChatClient}</b>，
     * 所以这条新边是安全的。往这里加别的连接器组件前，把那条链重新走一遍——
     * 尤其别换成 {@code ConnectorGateway}（它拖着 registry / loader / audit / redis / PendingWriteService）。
     */
    private final com.jimeng.dataserver.ai.connector.service.ConnectorOverviewService connectorOverviewService;

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
        // 有前置资源声明（SKILL.md 的 requires:）的平台 Skill：绑了→提升为直接注入工具；
        // 没绑→直接摘除（没有资源可用，暴露它只会诱导模型盲调一次必然失败的工具）。
        // 今天 rag-knowledge 声明 knowledge_bases、connector 声明 connections。
        resolveResourceBoundSkills(skillOnly, boundPlugins);

        // 平台级 Skill「connector」在场且 Agent 真的授权了连接器时，把表清单概览 + 这一轮命中的口径
        // 直接注入上下文，省掉模型开口前的那次 conn_catalog 往返。与上面 RAG 那段一样，取决于 Agent 绑了什么。
        injectConnectorContext(body, skillMap, adapter, messages);

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

        // Skill 发现阶段：把候选 Skill 的「名称 + 描述」全部展示给模型，由模型自行逐一比对、判断是否需要
        // activate_skills 激活——不做任何关键词命中式的服务端自动激活（关键词匹配会因描述里的通用词
        // 误命中，例如知识库上传请求里的「内容」撞上图像技能描述，被强行激活；详见 selectForDiscovery）。
        List<ToolPackage> discoverySkills = selectForDiscovery(skillOnly, latestUserText(messages));
        if (!discoverySkills.isEmpty()) {
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

    /**
     * 按 Skill 自己声明的前置资源（SKILL.md 的 {@code requires:}）决定它的可见性：
     * <ul>
     *   <li><b>资源已绑</b>：从「待发现」移到「直接注入」，工具立刻在手上，省掉 discovery→activate 往返。</li>
     *   <li><b>资源未绑</b>：直接从候选里摘除——没有资源可用，暴露它只会诱导模型盲调一次必然失败的工具。</li>
     *   <li><b>没有声明 {@code requires}</b>：一个字不动，照常走发现。</li>
     *   <li><b>无 Agent 上下文</b>（直连 {@code /data/claude/messages} 不带 agent_id 的旧用法）：
     *       同样不干预，仍走正常发现流程——这里不 fail-closed，因为「看不见」和「不能用」是两件事，
     *       真正的闸在执行侧（{@code ConnectorGateway} / rag 执行器），那里才是 fail-closed 的。</li>
     * </ul>
     *
     * <h3>这段代码为什么从「rag 专用」改成「按声明驱动」</h3>
     * 它原本叫 {@code resolveRagSkillVisibility}，整段只认 {@code "rag-knowledge"} 一个字面量。
     * 后来 {@code connector} 撞上同一个需求（绑了连接才该直注），<b>而没有人来加第二个 if</b>——
     * 于是 connector 一直走发现：模型每轮都得先自己调一次 {@code activate_skills} 才拿得到
     * {@code conn_*}，而表清单概览却在同一轮被无条件塞进了 system。
     * 实测代价是每轮多两次 LLM 往返（activate + conn_list）+ 重新注入一遍 SKILL.md，
     * 并且「这个 Agent 能不能查库」从一次管理员配置退化成模型每轮重赌一次。
     *
     * <p>同一个形状出现第二次就该抽象。现在新增一个有前置资源的平台 Skill，
     * <b>只需在它自己的 SKILL.md 里写一行</b>，本方法和运行时一个字都不用改。
     */
    private void resolveResourceBoundSkills(Map<String, ToolPackage> skillOnly, List<ToolPackage> boundPlugins) {
        AgentRuntimeView agent = AgentContext.get();
        if (agent == null) return;
        for (Iterator<Map.Entry<String, ToolPackage>> it = skillOnly.entrySet().iterator(); it.hasNext(); ) {
            ToolPackage pkg = it.next().getValue();
            SkillRequirement req = pkg.getRequires();
            if (req == null) continue;
            if (isRequirementSatisfied(req, agent)) {
                boundPlugins.add(pkg);
                log.info("Agent 已绑定 {}，{} 提升为直接注入工具 agentId={}", req.token(), pkg.getName(), agent.getAgentId());
            } else {
                log.info("Agent 未绑定 {}，隐藏 {} 技能 agentId={}", req.token(), pkg.getName(), agent.getAgentId());
            }
            it.remove();
        }
    }

    /**
     * 某一类前置资源在当前 Agent 上到底绑没绑。
     *
     * <p>两条判据刻意<b>来源不同</b>：知识库读的是 Agent 快照里的 {@code kbIds}（随发布固化），
     * 连接读的是 {@code agent_connection} 的实时行。后者不能走快照——授权是超管随时可撤的，
     * 撤销必须立刻生效，而快照要等下一次发布。
     */
    private boolean isRequirementSatisfied(SkillRequirement req, AgentRuntimeView agent) {
        return switch (req) {
            case KNOWLEDGE_BASES -> agent.getKbIds() != null && !agent.getKbIds().isEmpty();
            // 与 ConnectorGateway / ConnectorOverviewService 同一个判据：授权只认 agent_connection。
            // 任何异常都按「没绑」处理：这里判错的代价是少一个工具（吵闹、用户会问），
            // 而判成「有」的代价是把一堆必然失败的 conn_* 塞给模型（安静、只表现为答得莫名其妙）。
            case CONNECTIONS -> {
                try {
                    yield connectorOverviewService.hasGrantedConnections(agent.getAgentId());
                } catch (Exception e) {
                    log.warn("判定 Agent 连接授权失败，本轮按未授权处理 agentId={}", agent.getAgentId(), e);
                    yield false;
                }
            }
        };
    }

    /** 平台级连接器 Skill 的名称（SKILL.md frontmatter name）。 */
    private static final String CONNECTOR_SKILL_NAME = "connector";

    /**
     * 「默认注入目录」+「这一轮的确定性口径命中」：两段都进 system 上下文。
     *
     * <p>今天模型必须先烧一次 {@code conn_catalog} 往返才知道库里有哪些表；而那份「表名 + 注释」
     * 很小，来源又是<b>我们自己的</b> {@code connector_schema} 快照，完全可以提前给。
     * 口径（{@code glossary}）也一并给：销售额扣不扣退款决定了要不要 join 退款表，
     * 它必须在<b>选表之前</b>被看见。
     *
     * <h4>★ 口径块只按【用户这一轮说的话】命中</h4>
     * 传进去的是 {@link #latestUserText}（最后一条 user 消息），<b>不是整段对话历史</b>。
     * 传历史的后果很具体：三轮前提过一次「销售额」，之后每一轮都会把它重新注入一遍，
     * 于是这套东西退化成「每次都提供给大模型作参考」——真正相关的那一条被十几条无关定义稀释掉。
     *
     * <h4>★ 它是附加上下文，不是对用户原话的改写</h4>
     * 这里只 {@code appendSystemContent}，<b>从不改 {@code messages} 里的任何一个字</b>。
     * 改写用户输入会让「用户说的话」和「模型看到的话」不再是同一句，答案错了的时候，
     * 对话记录里就没有任何材料能复原模型当时读到了什么——而本仓库的审计、trace_id 回溯、
     * 口径变更留痕全都建立在「对话可复核」这个前提上。
     *
     * <h4>★ 「工具没给却先给了表清单」这个坑，守卫曾经形同虚设</h4>
     * 下面第一条守卫写的是「connector 这个包在不在工具包视图里」，防的是
     * <b>「模型没有 conn_* 工具，却先拿到一份表清单，于是被诱导去调不存在的工具」</b>。
     * 想法是对的，但判据错了：{@code connector} 是<b>磁盘平台技能，对所有 Agent 恒可见</b>，
     * 这个条件<b>恒为真</b>，所以它一次也没防住——实测过：概览照常注入 system，
     * 而同一次请求的 tools 数组里只有 {@code activate_skills / skill_search / skill_install}。
     *
     * <p>现在真正兑现这条守卫的是 SKILL.md 里的 {@code requires: connections}：
     * 授权了 → connector 被提升为直接注入，工具和概览<b>同时出现</b>；
     * 没授权 → 它被整个隐藏，而 {@code buildRequestContext} 同样返回空。两侧同源，不会再分叉。
     * 下面这条判空<b>保留</b>，作为「有人把 connector 从磁盘上删了」时的兜底。
     *
     * <p><b>两个前提都满足才注入，缺一不注入一个字节：</b>
     * <ul>
     *   <li>{@code connector} 这个平台 Skill 在本次请求的工具包视图里；</li>
     *   <li>当前 Agent 真的被授权了连接器（{@code agent_connection} 里有行）——这一条由
     *       {@code ConnectorOverviewService} 自己判，并且和 {@code ConnectorGateway} 一样 fail-closed：
     *       没有 {@code AgentContext} / 没有租户上下文 = 谁也没授权。</li>
     * </ul>
     *
     * <p>注入是叠加增强，<b>任何失败都只是少一段文字</b>：概览服务内部吞掉全部异常并返回空，
     * 这里只判空，外加一层 catch 兜住"它哪天不再吞异常了"。用一个可选优化的故障去打断一轮对话是错的。
     */
    private void injectConnectorContext(Map<String, Object> body,
                                         Map<String, ToolPackage> allPackages,
                                         AiProtocolAdapter adapter,
                                         List<?> messages) {
        if (toolPackageRegistry.findByName(allPackages, CONNECTOR_SKILL_NAME) == null) return;
        com.jimeng.dataserver.ai.connector.service.ConnectorOverviewService.RequestContext ctx;
        try {
            ctx = connectorOverviewService.buildRequestContext(latestUserText(messages));
        } catch (Exception e) {
            log.warn("构建连接器上下文失败，本轮不注入", e);
            return;
        }
        if (ctx == null) return;
        if (StrUtil.isNotBlank(ctx.getOverview())) {
            adapter.appendSystemContent(body, ctx.getOverview());
            log.info("连接器目录概览已注入 chars={}", ctx.getOverview().length());
        }
        if (StrUtil.isNotBlank(ctx.getMetricContext())) {
            adapter.appendSystemContent(body, ctx.getMetricContext());
            log.info("已确认口径命中并注入 chars={}", ctx.getMetricContext().length());
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
        sb.append("下面是当前可用的全部 Skill 列表。\n");
        sb.append("**在直接作答或调用其它任何工具（包括 generate_image 等内置工具）之前**，");
        sb.append("请你自己逐一阅读每个 Skill 的「适用场景/触发场景」描述，判断它是否与用户当前请求相关：\n");
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
     * 发现阶段安全上限：只有当候选 Skill 数量<b>超过</b>它时，才退化到「关键词相关性排序 + 截断」以免上下文膨胀；
     * 常规数量下把<b>全部</b> Skill 的「名称 + 描述」都展示给模型，由模型自行判断是否激活——不做关键词预筛。
     * 注意：这与 {@code skill.max-selected}（单次 activate_skills 可激活的数量上限）无关。
     */
    private static final int DISCOVERY_MAX = 30;

    /**
     * 发现阶段挑选要展示给模型的 Skill：常规情况下<b>展示全部候选</b>（让模型读到每一个 Skill 的描述、自行判断
     * 是否需要激活），不按关键词预筛——关键词命中式预筛/自动激活会因描述中的通用词误命中而错误激活，是此前
     * 「知识库等无关请求也激活图像技能」的根因。仅当候选数量超过 {@link #DISCOVERY_MAX} 这一安全上限时，
     * 才退化为按词面相关性排序（同分租户自有 Skill 优先）并截断，纯粹为防上下文膨胀。
     */
    static List<ToolPackage> selectForDiscovery(Map<String, ToolPackage> skillMap, String query) {
        List<ToolPackage> all = new ArrayList<>(skillMap.values());
        if (all.size() <= DISCOVERY_MAX) return all;
        return rankForDiscovery(all, tokenize(query), DISCOVERY_MAX);
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

    /** Skill 文本(名称+描述)与用户问题词元的交集大小。仅用于 {@link #rankForDiscovery} 的超量兜底排序。 */
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

        java.util.LinkedHashMap<String, ToolPackage> filtered = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ToolPackage> e : packages.entrySet()) {
            ToolPackage pkg = e.getValue();
            if (isVisibleToAgent(pkg, agent)) {
                filtered.put(e.getKey(), pkg);
            }
        }
        return filtered;
    }

    /**
     * 一个工具包对当前 Agent 是否可见。
     *
     * <p><b>判别依据是 {@code getTenantId()==null}，不是 {@code getKind()}。</b>
     * 磁盘上的平台技能（gaode-poi / rag-knowledge / design-system）也是 kind==SKILL，
     * 但它们没有 ai_skill 行、永远绑不上 agent_skill；按 kind 判会让每个 agent 同时丢掉这三个
     * （含 RAG 提升），而且不报错。
     *
     * <p>{@code allowedSkillIds == null} 表示"无绑定信息"（老发布快照），保持旧的全可见行为；
     * 空集合表示"明确不绑"。两者含义不同，不能合并。
     */
    private boolean isVisibleToAgent(ToolPackage pkg, AgentRuntimeView agent) {
        // 平台工具包：全局可见，不参与按 Agent 的绑定过滤。
        if (pkg.getTenantId() == null) return true;

        // 租户技能：按 agent_skill 绑定过滤。
        java.util.Set<Long> allowed = agent.getAllowedSkillIds();
        if (allowed == null) return true;           // 无绑定信息 → 不过滤（向后兼容）
        Long sourceId = pkg.getSourceId();
        if (sourceId == null) return true;          // 拿不到 DB 主键 → 不敢判，放行（fail-open 但仅限此分支）
        return allowed.contains(sourceId);
    }
}
