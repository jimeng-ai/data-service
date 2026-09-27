package com.jimeng.dataserver.ai.skill.builder;

import cn.hutool.core.util.StrUtil;
import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import com.jimeng.dataserver.ai.conversation.AiConversationLoop;
import com.jimeng.dataserver.ai.protocol.ClaudeProtocolAdapter;
import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import com.jimeng.dataserver.ai.skill.model.ToolPackageKind;
import com.jimeng.dataserver.ai.skill.service.SkillRuntimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 渲染 PROMPT 类 skill 触发测试要复现的「生产发现请求」，随构建器每一轮下发给边车
 * （{@code SidecarRunPayload.skillBuilder.triggerEval}）。
 *
 * <p>为什么在这里渲染、而不是让边车自己拼：PROMPT 类 skill 在生产里走对话平面的「发现 → activate_skills」，
 * 那段发现提示词、activate_skills 的定义、同时在场的内置工具全在 data-service 里。skill-creator 的
 * run_eval.py（jimeng 适配版）只负责把它原样发出去、看模型第一步是不是激活了被测 skill。
 * 三样东西都取自生产代码本身（{@link SkillRuntimeService#discoveryHeader}、
 * {@link ClaudeProtocolAdapter#buildActivateSkillsToolDef}、{@link AiConversationLoop#builtinToolDefs}），
 * 不在这里另抄一份——抄了就会漂移，而漂移的后果是「优化出来的 description 在生产里表现不一样」且无人察觉。
 *
 * <p>工具一律按 Anthropic 形态渲染：沙箱里的调用走 Anthropic Messages 协议（与容器里的 claude CLI 同一条通道）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillTriggerContextFactory {

    private final SkillRuntimeService skillRuntimeService;
    private final ClaudeProtocolAdapter anthropicAdapter;
    private final AiConversationLoop conversationLoop;
    private final AgentSandboxProperties sandboxProps;
    private final SkillBuilderProperties props;

    /**
     * 必须在带租户上下文的线程上调用（候选取自当前租户可见的 skill）。
     *
     * @param excludeName 被构建 skill 的名字（改进已有 skill 时它本来就在列表里，要排除掉，由边车把新描述补在末尾）
     */
    public SidecarRunPayload.TriggerEval build(String excludeName) {
        SidecarRunPayload.TriggerEval t = new SidecarRunPayload.TriggerEval();
        t.setHeader(skillRuntimeService.discoveryHeader());
        t.setLineTemplate(SkillRuntimeService.DISCOVERY_LINE_TEMPLATE);
        t.setCandidates(candidates(excludeName));

        List<Map<String, Object>> tools = new ArrayList<>();
        // 顺序与生产一致：applySkillContext（发现阶段追加 activate_skills）先于 injectBuiltinTools。
        tools.add(asMap(anthropicAdapter.buildActivateSkillsToolDef()));
        for (Object def : conversationLoop.builtinToolDefs(anthropicAdapter)) {
            Map<String, Object> m = asMap(def);
            if (m != null) tools.add(m);
        }
        tools.removeIf(Objects::isNull);
        t.setTools(tools);
        t.setActivateToolName("activate_skills");
        t.setModel(StrUtil.blankToDefault(props.getTriggerModel(),
                StrUtil.blankToDefault(props.getModel(), sandboxProps.getLlm().getModel())));
        t.setMaxTokens(1024);
        return t;
    }

    /** 当前租户里与被测 skill 竞争的其它 skill（只取发现阶段会展示的 SKILL 类包），上限见配置。 */
    private List<SidecarRunPayload.Candidate> candidates(String excludeName) {
        List<SidecarRunPayload.Candidate> out = new ArrayList<>();
        try {
            for (ToolPackage p : skillRuntimeService.aggregateToolPackages().values()) {
                if (p.getKind() == ToolPackageKind.PLUGIN) continue;
                if (excludeName != null && excludeName.equals(p.getName())) continue;
                SidecarRunPayload.Candidate c = new SidecarRunPayload.Candidate();
                c.setName(p.getName());
                c.setDescription(String.valueOf(p.getDescription()));
                out.add(c);
                if (out.size() >= props.getTriggerCandidatesMax()) break;
            }
        } catch (Exception e) {
            // 候选只是「让测试更像生产」的竞争者；取不到时退化为只测被测 skill 自己，不让整轮失败。
            log.warn("触发测试候选 skill 获取失败，退化为无竞争者: {}", e.getMessage());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object def) {
        return def instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
