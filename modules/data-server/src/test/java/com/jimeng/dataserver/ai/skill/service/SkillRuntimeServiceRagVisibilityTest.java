package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.protocol.ClaudeProtocolAdapter;
import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import com.jimeng.dataserver.ai.skill.source.ToolPackageRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 平台级 Skill「rag-knowledge」的可见性应随 Agent 是否绑定知识库而变：
 *   - 绑了库 → rag_search 直接注入为可调工具；
 *   - 没绑库 → rag-knowledge 整体不出现（既不直注、也不进发现），模型看不到 rag_search。
 *
 * 用真实的 {@link ClaudeProtocolAdapter} 驱动真实的 {@code applySkillContext}，只 mock 工具包来源，
 * 因此断言的是改动后决策链路的真实输出，而非被打桩掉的逻辑。
 */
class SkillRuntimeServiceRagVisibilityTest {

    private SkillRuntimeService service;
    private final ClaudeProtocolAdapter adapter = new ClaudeProtocolAdapter();

    @BeforeEach
    void setUp() {
        ToolPackageRegistry registry = mock(ToolPackageRegistry.class);
        // 真实的 findByName 逻辑用得上（保持非 mock 行为不影响本用例）；aggregate 打桩成「只有 rag-knowledge」。
        when(registry.aggregate()).thenReturn(ragOnlyPackages());

        service = new SkillRuntimeService(registry, mock(SkillToolExecutorRegistryService.class));
        ReflectionTestUtils.setField(service, "skillEnabled", true);
        ReflectionTestUtils.setField(service, "explicitPrefix", "@");
        ReflectionTestUtils.setField(service, "maxSelected", 5);
        ReflectionTestUtils.setField(service, "skillSystemPrompt", "你可以使用下列技能。");
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void noKbBound_hidesRagSkillEntirely() {
        AgentContext.set(agent(Collections.emptySet()));   // 未绑定任何知识库

        Map<String, Object> body = newBody("学生险如何购买");
        service.applySkillContext(body, adapter);

        assertThat(toolNames(body))
                .as("没绑库时模型不应看到 rag_search / rag_kb_list")
                .doesNotContain("rag_search", "rag_kb_list");
        assertThat(systemText(body))
                .as("没绑库时发现列表里也不应出现 rag-knowledge")
                .doesNotContain("rag-knowledge");
    }

    @Test
    void kbBound_promotesRagSearchAsCallableTool() {
        AgentContext.set(agent(Set.of(123L)));             // 绑定了知识库

        Map<String, Object> body = newBody("学生险如何购买");
        service.applySkillContext(body, adapter);

        assertThat(toolNames(body))
                .as("绑了库时 rag_search 应直接注入为可调工具")
                .contains("rag_search");
    }

    // ---------------------------------------------------------------- helpers

    private static AgentRuntimeView agent(Set<Long> kbIds) {
        return AgentRuntimeView.builder()
                .agentId(1L)
                .tenantId("test")
                .allowedPluginCodes(Collections.emptySet())
                .kbIds(kbIds)
                .build();
    }

    private static Map<String, Object> newBody(String userText) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", userText);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", new ArrayList<>(List.of(msg)));
        return body;
    }

    @SuppressWarnings("unchecked")
    private List<String> toolNames(Map<String, Object> body) {
        List<String> names = new ArrayList<>();
        Object tools = body.get("tools");
        if (tools instanceof List<?> list) {
            for (Object t : list) {
                if (t instanceof Map<?, ?> m && m.get("name") != null) {
                    names.add(String.valueOf(m.get("name")));
                }
            }
        }
        return names;
    }

    private String systemText(Map<String, Object> body) {
        Object system = body.get("system");
        return system == null ? "" : system.toString();
    }

    /** 只含 rag-knowledge 一个平台级 Skill（tenantId=null 全局可见），带 rag_search / rag_kb_list 两个工具。 */
    private static Map<String, ToolPackage> ragOnlyPackages() {
        Map<String, ToolPackage> map = new LinkedHashMap<>();
        map.put("rag-knowledge", new ToolPackage() {
            @Override public String getName() { return "rag-knowledge"; }
            @Override public String getDescription() { return "在企业知识库中检索相关资料并回答用户问题。"; }
            @Override public String getBody() { return "rag-knowledge guidance body"; }
            @Override public List<SkillToolDefinition> getTools() {
                return List.of(
                        new SkillToolDefinition("rag_search", "检索 top-K chunks", Map.of()),
                        new SkillToolDefinition("rag_kb_list", "列出可用知识库", Map.of()));
            }
            @Override public String getTenantId() { return null; }
        });
        return map;
    }
}
