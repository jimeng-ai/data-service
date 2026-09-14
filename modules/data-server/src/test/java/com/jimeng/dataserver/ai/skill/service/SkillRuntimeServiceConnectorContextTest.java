package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.connector.service.ConnectorOverviewService;
import com.jimeng.dataserver.ai.protocol.ClaudeProtocolAdapter;
import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import com.jimeng.dataserver.ai.skill.source.ToolPackageRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 连接器上下文（表清单概览 + 这一轮命中的口径）怎么进 system 提示。
 *
 * <p>三条断言各自堵一个具体的失败：
 * <ul>
 *   <li><b>改写用户原话</b> → 「用户说的话」和「模型看到的话」不再是同一句，答案错了就复原不出来；</li>
 *   <li><b>把整段历史拿去命中</b> → 三轮前提过一次「销售额」，之后每轮都重新注入一遍，
 *       真正相关的那条被稀释掉；</li>
 *   <li><b>连接器技能不在场也注入</b> → 模型手上根本没有 conn_* 工具，给一份表清单只会诱导它瞎调。</li>
 * </ul>
 */
class SkillRuntimeServiceConnectorContextTest {

    private static final String OVERVIEW = "【已接入数据源的表清单】t_ord | 订单表";
    private static final String METRICS = "[数据源 crm] 已确认的业务口径：销售额＝扣除退款";

    private SkillRuntimeService service;
    private ConnectorOverviewService overviewService;
    private final ClaudeProtocolAdapter adapter = new ClaudeProtocolAdapter();

    @BeforeEach
    void setUp() {
        ToolPackageRegistry registry = stubbedRegistry(connectorOnlyPackages());
        overviewService = mock(ConnectorOverviewService.class);

        service = new SkillRuntimeService(registry, mock(SkillToolExecutorRegistryService.class),
                new com.jimeng.dataserver.ai.agent.builder.DraftAgentToolPackage(),
                new com.jimeng.dataserver.ai.skill.builder.DraftSkillToolPackage(),
                overviewService);
        ReflectionTestUtils.setField(service, "skillEnabled", true);
        ReflectionTestUtils.setField(service, "explicitPrefix", "@");
        ReflectionTestUtils.setField(service, "maxSelected", 5);
        ReflectionTestUtils.setField(service, "skillSystemPrompt", "你可以使用下列技能。");

        AgentContext.set(AgentRuntimeView.builder().agentId(1L).tenantId("test").build());
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    /** ★ 口径是<b>附加</b>在用户原话旁边的说明，用户那句话一个字都不准动。 */
    @Test
    void metricBlockIsAppendedBeside_theUserTextIsNeverRewritten() {
        when(overviewService.buildRequestContext(any())).thenReturn(
                ConnectorOverviewService.RequestContext.builder()
                        .overview(OVERVIEW).metricContext(METRICS).build());

        Map<String, Object> body = newBody(List.of("上个月销售额多少"));
        service.applySkillContext(body, adapter);

        assertThat(systemText(body)).contains(OVERVIEW).contains(METRICS);
        assertThat(userContents(body))
                .as("替换用户输入会把「对话可复核」这个前提悄悄抽掉")
                .containsExactly("上个月销售额多少");
    }

    /** ★ 只拿这一轮的话去命中，绝不传对话历史。 */
    @Test
    void onlyTheCurrentTurnIsHandedToTheMatcher() {
        when(overviewService.buildRequestContext(any())).thenReturn(
                ConnectorOverviewService.RequestContext.builder().build());

        Map<String, Object> body = newBody(List.of("上个月销售额多少", "那客单价呢"));
        service.applySkillContext(body, adapter);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(overviewService).buildRequestContext(text.capture());
        assertThat(text.getValue())
                .as("传历史＝三轮前提过的词每轮都被重新注入一遍")
                .isEqualTo("那客单价呢")
                .doesNotContain("销售额");
    }

    @Test
    void emptyContext_injectsNothing() {
        when(overviewService.buildRequestContext(any())).thenReturn(
                ConnectorOverviewService.RequestContext.builder().build());

        Map<String, Object> body = newBody(List.of("今天天气怎么样"));
        service.applySkillContext(body, adapter);

        assertThat(systemText(body)).doesNotContain("数据源").doesNotContain("口径");
    }

    @Test
    void withoutTheConnectorSkill_theContextIsNeverEvenBuilt() {
        ReflectionTestUtils.setField(service, "toolPackageRegistry", stubbedRegistry(Map.of()));

        service.applySkillContext(newBody(List.of("上个月销售额多少")), adapter);

        verifyNoInteractions(overviewService);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * {@code findByName} 也要打桩：它是「连接器技能在不在场」这个判断的唯一入口，
     * 不打桩的 mock 永远返回 null，于是整段注入会被跳过，而用例看起来只是"什么都没注入"。
     */
    @SuppressWarnings("unchecked")
    private static ToolPackageRegistry stubbedRegistry(Map<String, ToolPackage> packages) {
        ToolPackageRegistry registry = mock(ToolPackageRegistry.class);
        when(registry.aggregate()).thenReturn(packages);
        when(registry.findByName(any(), any())).thenAnswer(inv -> {
            // 先落成 Object 再取名字：直接把 getArgument 塞进 String.valueOf，
            // 泛型会推成 char[] 那个重载，运行期抛 ClassCastException。
            Object name = inv.getArgument(1);
            return ((Map<String, ToolPackage>) inv.getArgument(0)).get(String.valueOf(name));
        });
        return registry;
    }

    private static Map<String, Object> newBody(List<String> userTurns) {
        List<Object> messages = new ArrayList<>();
        for (String t : userTurns) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "user");
            msg.put("content", t);
            messages.add(msg);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", messages);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static List<String> userContents(Map<String, Object> body) {
        List<String> out = new ArrayList<>();
        for (Object m : (List<Object>) body.get("messages")) {
            out.add(String.valueOf(((Map<String, Object>) m).get("content")));
        }
        return out;
    }

    private static String systemText(Map<String, Object> body) {
        Object system = body.get("system");
        return system == null ? "" : system.toString();
    }

    /** 只含平台级 Skill「connector」（tenantId=null 全局可见）。 */
    private static Map<String, ToolPackage> connectorOnlyPackages() {
        Map<String, ToolPackage> map = new LinkedHashMap<>();
        map.put("connector", new ToolPackage() {
            @Override public String getName() { return "connector"; }
            @Override public String getDescription() { return "访问企业已接入的外部系统。"; }
            @Override public String getBody() { return "connector guidance body"; }
            @Override public List<SkillToolDefinition> getTools() {
                return List.of(new SkillToolDefinition("conn_catalog", "看目录", Map.of()));
            }
            @Override public String getTenantId() { return null; }
        });
        return map;
    }
}
