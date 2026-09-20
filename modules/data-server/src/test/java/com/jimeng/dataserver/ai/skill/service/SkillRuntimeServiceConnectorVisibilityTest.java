package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.connector.service.ConnectorOverviewService;
import com.jimeng.dataserver.ai.protocol.ClaudeProtocolAdapter;
import com.jimeng.dataserver.ai.skill.model.SkillRequirement;
import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import com.jimeng.dataserver.ai.skill.source.ToolPackageRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 平台级 Skill「connector」的可见性随 Agent 有没有被授权连接而变——{@code requires: connections}。
 *
 * <h3>这个用例钉的是一个上线后实测出来的静默失效</h3>
 * 在 {@code requires} 之前，connector 是磁盘平台技能、对所有 Agent 恒可见，于是它一直走
 * discovery：第一轮发给模型的 tools 数组里只有 {@code activate_skills / skill_search / skill_install}，
 * <b>一个 {@code conn_*} 都没有</b>——而同一次请求的 system prompt 里已经塞进了 19 张表的清单，
 * 还写着「以 {@code conn_describe} 的返回为准」。说明书点名要调的工具不在模型手上。
 *
 * <p>实测里模型确实靠自己调 {@code activate_skills} 补救回来了，所以这不是「功能不可用」，
 * 而是<b>每一轮都多烧两次 LLM 往返 + 重新注入一遍 SKILL.md，并且每轮重赌一次</b>。
 * 「这个 Agent 能不能查库」应当由管理员的一次确定性配置决定，不该是模型每轮的一次判断。
 *
 * <p>与 {@link SkillRuntimeServiceRagVisibilityTest} 同构：用真实 adapter 驱动真实
 * {@code applySkillContext}，只打桩工具包来源与授权查询。
 */
class SkillRuntimeServiceConnectorVisibilityTest {

    private SkillRuntimeService service;
    private ConnectorOverviewService overview;
    private final ClaudeProtocolAdapter adapter = new ClaudeProtocolAdapter();

    @BeforeEach
    void setUp() {
        ToolPackageRegistry registry = mock(ToolPackageRegistry.class);
        when(registry.aggregate()).thenReturn(connectorOnlyPackages());
        overview = mock(ConnectorOverviewService.class);

        service = new SkillRuntimeService(registry, mock(SkillToolExecutorRegistryService.class),
                new com.jimeng.dataserver.ai.agent.builder.DraftAgentToolPackage(),
                new com.jimeng.dataserver.ai.skill.builder.DraftSkillToolPackage(),
                overview);
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
    @DisplayName("★ 授权了连接 → conn_* 第一轮就在 tools 里，不必先 activate_skills")
    void grantedConnections_promotesConnToolsImmediately() {
        when(overview.hasGrantedConnections(anyLong())).thenReturn(true);
        AgentContext.set(agent());

        Map<String, Object> body = newBody("EFI_VOUCHERDTL 有多少行？");
        service.applySkillContext(body, adapter);

        assertThat(toolNames(body))
                .as("授权了连接就该直注，模型第一轮手上就得有查库工具")
                .contains("conn_list", "conn_catalog", "conn_query");
        assertThat(toolNames(body))
                .as("直注之后就不该再要求模型先激活一次")
                .doesNotContain("activate_skills");
    }

    @Test
    @DisplayName("没授权连接 → connector 整个隐藏，既不直注也不进发现列表")
    void noGrantedConnections_hidesConnectorEntirely() {
        when(overview.hasGrantedConnections(anyLong())).thenReturn(false);
        AgentContext.set(agent());

        Map<String, Object> body = newBody("EFI_VOUCHERDTL 有多少行？");
        service.applySkillContext(body, adapter);

        assertThat(toolNames(body))
                .as("没授权时模型不该看到任何 conn_* 工具")
                .doesNotContain("conn_list", "conn_catalog", "conn_query");
        assertThat(systemText(body))
                .as("没授权时发现列表里也不该出现 connector——暴露它只会诱导模型盲调一次必然失败的工具")
                .doesNotContain("connector");
    }

    @Test
    @DisplayName("授权查询抛异常 → 按「没授权」处理，不把必然失败的工具塞给模型")
    void grantLookupFailureFallsBackToHidden() {
        when(overview.hasGrantedConnections(anyLong())).thenThrow(new RuntimeException("db down"));
        AgentContext.set(agent());

        Map<String, Object> body = newBody("查一下订单表");
        service.applySkillContext(body, adapter);

        // 判错成「没绑」的代价是少一个工具（吵闹、用户会问）；判错成「有绑」的代价是
        // 一堆必然失败的 conn_* 调用（安静、只表现为答得莫名其妙）。选前者。
        assertThat(toolNames(body)).doesNotContain("conn_list", "conn_query");
    }

    @Test
    @DisplayName("无 Agent 上下文（直连 /data/claude/messages 不带 agent_id）→ 不干预，照常走发现")
    void noAgentContextKeepsDiscovery() {
        AgentContext.clear();

        Map<String, Object> body = newBody("查一下订单表");
        service.applySkillContext(body, adapter);

        // 这里刻意不 fail-closed：「看不见」和「不能用」是两件事，真正的闸在 ConnectorGateway。
        assertThat(systemText(body)).contains("connector");
    }

    // ---------------------------------------------------------------- helpers

    private static AgentRuntimeView agent() {
        return AgentRuntimeView.builder().agentId(1L).tenantId("test").kbIds(Set.of()).build();
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
        if (body.get("tools") instanceof List<?> list) {
            for (Object t : list) {
                if (t instanceof Map<?, ?> m && m.get("name") != null) names.add(String.valueOf(m.get("name")));
            }
        }
        return names;
    }

    private String systemText(Map<String, Object> body) {
        Object system = body.get("system");
        return system == null ? "" : system.toString();
    }

    /** 只含 connector 一个平台级 Skill，声明与真实 SKILL.md 一致的 requires。 */
    private static Map<String, ToolPackage> connectorOnlyPackages() {
        Map<String, ToolPackage> map = new LinkedHashMap<>();
        map.put("connector", new ToolPackage() {
            @Override public String getName() { return "connector"; }
            @Override public String getDescription() { return "访问企业已接入的外部系统——查客户的业务数据库。"; }
            @Override public String getBody() { return "connector guidance body"; }
            @Override public List<SkillToolDefinition> getTools() {
                return List.of(
                        new SkillToolDefinition("conn_list", "列出可用连接", Map.of()),
                        new SkillToolDefinition("conn_catalog", "列出对象", Map.of()),
                        new SkillToolDefinition("conn_query", "只读查询", Map.of()));
            }
            @Override public String getTenantId() { return null; }
            @Override public SkillRequirement getRequires() { return SkillRequirement.CONNECTIONS; }
        });
        return map;
    }
}
