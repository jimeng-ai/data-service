package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import com.jimeng.dataserver.ai.skill.model.ToolPackageKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 按 Agent 的技能可见性（{@code SkillRuntimeService.isVisibleToAgent}）。
 *
 * <p>这段逻辑替代了插件下线后失去的「按 Agent 限定工具范围」，有三个只要写错就【静默出错】的点，
 * 每一个这里都钉了用例：
 *
 * <ol>
 *   <li><b>判别依据必须是 tenantId 而非 kind。</b> 磁盘上的平台技能
 *       （gaode-poi / rag-knowledge / design-system）也是 kind==SKILL，但没有 ai_skill 行、
 *       永远绑不上 agent_skill。按 kind 判会让每个 agent 同时丢掉这三个（含 RAG 提升）。</li>
 *   <li><b>allowedSkillIds 是三态。</b> null（老发布快照没有 skillIds 键）= 无信息、不过滤；
 *       空集 = 明确不绑。把 null 当空集，库里 8 个已发布 agent 会在上线瞬间全部丢光租户技能。</li>
 *   <li><b>按 id 匹配而非 name。</b> ai_skill 刻意没有 name 唯一键，同名行可共存。</li>
 * </ol>
 */
class AgentSkillVisibilityTest {

    /**
     * 直接测私有判别方法：它是纯函数（不读任何字段），且是整条链路里唯一做可见性决策的地方。
     * 用 mock 实例只为拿到一个对象——私有方法不会被 Mockito 拦截，反射调用跑的是真实字节码。
     */
    private final SkillRuntimeService svc = mock(SkillRuntimeService.class);

    private boolean visible(ToolPackage pkg, AgentRuntimeView agent) {
        return Boolean.TRUE.equals(
                ReflectionTestUtils.invokeMethod(svc, "isVisibleToAgent", pkg, agent));
    }

    private static ToolPackage pkg(String name, String tenantId, Long sourceId, ToolPackageKind kind) {
        return new ToolPackage() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "d"; }
            @Override public String getBody() { return "b"; }
            @Override public List<SkillToolDefinition> getTools() { return List.of(); }
            @Override public String getTenantId() { return tenantId; }
            @Override public Long getSourceId() { return sourceId; }
            @Override public ToolPackageKind getKind() { return kind; }
        };
    }

    private static AgentRuntimeView agent(Set<Long> skillIds, Set<String> pluginCodes) {
        return AgentRuntimeView.builder()
                .agentId(1L).tenantId("t1").code("c").name("n")
                .allowedSkillIds(skillIds)
                .allowedPluginCodes(pluginCodes)
                .build();
    }

    @Test
    @DisplayName("平台技能（tenantId==null）恒可见，不受绑定约束")
    void platformSkillAlwaysVisible() {
        ToolPackage rag = pkg("rag-knowledge", null, null, ToolPackageKind.SKILL);
        assertThat(visible(rag, agent(Set.of(), Set.of()))).isTrue();          // 明确不绑任何技能
        assertThat(visible(rag, agent(Set.of(999L), Set.of()))).isTrue();      // 绑了别的
        assertThat(visible(rag, agent(null, null))).isTrue();                  // 无绑定信息
    }

    @Test
    @DisplayName("allowedSkillIds==null（老快照）→ 不过滤，租户技能仍全部可见")
    void nullMeansNoFiltering() {
        ToolPackage s = pkg("报销助手", "t1", 100L, ToolPackageKind.SKILL);
        assertThat(visible(s, agent(null, null))).isTrue();
    }

    @Test
    @DisplayName("allowedSkillIds==空集（明确不绑）→ 租户技能全部不可见。null 与空集含义不同")
    void emptyMeansNothingBound() {
        ToolPackage s = pkg("报销助手", "t1", 100L, ToolPackageKind.SKILL);
        assertThat(visible(s, agent(Set.of(), Set.of()))).isFalse();
    }

    @Test
    @DisplayName("租户技能按 id 匹配：绑了才可见，没绑就不可见")
    void tenantSkillFilteredById() {
        ToolPackage bound = pkg("A", "t1", 100L, ToolPackageKind.SKILL);
        ToolPackage unbound = pkg("B", "t1", 200L, ToolPackageKind.SKILL);
        AgentRuntimeView a = agent(Set.of(100L), Set.of());
        assertThat(visible(bound, a)).isTrue();
        assertThat(visible(unbound, a)).isFalse();
    }

    @Test
    @DisplayName("同名不同 id 的两个租户技能，只有被绑的那个可见（name 不是绑定键）")
    void sameNameDifferentIdDisambiguatedById() {
        ToolPackage first = pkg("同名技能", "t1", 100L, ToolPackageKind.SKILL);
        ToolPackage second = pkg("同名技能", "t1", 200L, ToolPackageKind.SKILL);
        AgentRuntimeView a = agent(Set.of(200L), Set.of());
        assertThat(visible(first, a)).isFalse();
        assertThat(visible(second, a)).isTrue();
    }

    @Test
    @DisplayName("插件仍按 code 过滤，与技能互不干扰")
    void pluginStillFilteredByCode() {
        ToolPackage p = pkg("miaodong", "t1", null, ToolPackageKind.PLUGIN);
        assertThat(visible(p, agent(Set.of(), Set.of("miaodong")))).isTrue();
        assertThat(visible(p, agent(Set.of(), Set.of("other")))).isFalse();
        assertThat(visible(p, agent(Set.of(), null))).isFalse();
        // 技能绑定不会意外放行插件
        assertThat(visible(p, agent(null, null))).isFalse();
    }
}
