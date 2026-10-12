package com.jimeng.dataserver.ai.skill.service;

import com.jimeng.dataserver.ai.agent.builder.DraftAgentToolPackage;
import com.jimeng.dataserver.ai.connector.service.ConnectorOverviewService;
import com.jimeng.dataserver.ai.protocol.ClaudeProtocolAdapter;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;
import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import com.jimeng.dataserver.ai.skill.model.ToolUseCall;
import com.jimeng.dataserver.ai.skill.source.AiSkillToolPackage;
import com.jimeng.dataserver.ai.skill.source.ToolPackageRegistry;
import com.jimeng.persistence.entity.AiSkill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillRuntimeServiceHttpToolTest {

    private final ClaudeProtocolAdapter adapter = new ClaudeProtocolAdapter();
    private SkillRuntimeService service;
    private Map<String, ToolPackage> packages;

    @BeforeEach
    void setUp() {
        AiSkill s = new AiSkill();
        s.setId(1L);
        s.setName("crm-refund");
        s.setDescription("处理退款");
        s.setBody("先查订单再退款。");
        s.setVersion(1);
        s.setManifest(new SkillManifest(List.of("cdn.example.com"), false, null, null, null, List.of(
                new SkillManifest.KeyDecl("CRM_TOKEN", List.of("api.customer.com"), List.of("GET", "POST"), null))).toJson());
        AiSkillToolPackage pkg = new AiSkillToolPackage(s);
        packages = new LinkedHashMap<>(Map.of("crm-refund", pkg));
        ToolPackageRegistry registry = mock(ToolPackageRegistry.class);
        when(registry.aggregate()).thenReturn(packages);
        when(registry.findByName(any(), eq("crm-refund"))).thenReturn(pkg);
        service = new SkillRuntimeService(registry, mock(SkillToolExecutorRegistryService.class),
                new DraftAgentToolPackage(), mock(ConnectorOverviewService.class));
        ReflectionTestUtils.setField(service, "skillEnabled", true);
        ReflectionTestUtils.setField(service, "explicitPrefix", "@");
        ReflectionTestUtils.setField(service, "maxSelected", 5);
        ReflectionTestUtils.setField(service, "skillSystemPrompt", "你可以使用下列技能。");
    }

    private static Map<String, Object> body() {
        Map<String, Object> msg = new LinkedHashMap<>(Map.of("role", "user", "content", "帮我退一下订单 123"));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("messages", new ArrayList<>(List.of(msg)));
        return b;
    }

    private static List<String> toolNames(Map<String, Object> body) {
        List<String> names = new ArrayList<>();
        if (body.get("tools") instanceof List<?> l) for (Object t : l) if (t instanceof Map<?, ?> m) names.add(String.valueOf(m.get("name")));
        return names;
    }

    @Test
    @DisplayName("发现阶段没有 http_request；激活后并入，系统提示里带上 key 的声明，并记进本轮已激活的 skill")
    void mergedOnActivation() {
        Map<String, Object> b = body();
        ActivatedSkills.begin();
        try {
            service.applySkillContext(b, adapter);
            assertThat(toolNames(b)).contains("activate_skills").doesNotContain("http_request");
            service.handleActivateSkills(b, new ToolUseCall("t1", "activate_skills", Map.of("skill_names", List.of("crm-refund"))),
                    packages, adapter);
            assertThat(toolNames(b)).contains("http_request");
            assertThat(String.valueOf(b.get("system"))).contains("{{CRM_TOKEN}}").contains("api.customer.com").contains("cdn.example.com");
            assertThat(ActivatedSkills.current()).extracting(ToolPackage::getName).containsExactly("crm-refund");
        } finally {
            ActivatedSkills.end();
        }
    }

    @Test
    void explicitActivationMergesSharedToolOnceAndPreservesOriginalBody() {
        AiSkill second = new AiSkill();
        second.setName("another");
        second.setManifest(new SkillManifest(List.of(), true, null, null, null, List.of()).toJson());
        packages.put("another", new AiSkillToolPackage(second));
        Map<String, Object> b = body();
        b.put("messages", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("role", "user", "content", "@crm-refund 请退款")))));
        ActivatedSkills.begin();
        try {
            service.applySkillContext(b, adapter);
            assertThat(toolNames(b)).contains("http_request");
            assertThat(ActivatedSkills.current()).extracting(ToolPackage::getName).containsExactly("crm-refund");
            service.handleActivateSkills(b, new ToolUseCall("t2", "activate_skills", Map.of("skill_names", List.of("another"))), packages, adapter);
            assertThat(toolNames(b).stream().filter("http_request"::equals).count()).isEqualTo(1);
            assertThat(packages.get("crm-refund").getBody()).isEqualTo("先查订单再退款。");
            assertThat(packages.get("crm-refund").getRuntimeNotes()).contains("{{CRM_TOKEN}}");
        } finally {
            ActivatedSkills.end();
        }
    }

    @Test
    void plainSkillActivationDoesNotGrantHttp() {
        AiSkill plain = new AiSkill();
        plain.setName("plain");
        packages.put("plain", new AiSkillToolPackage(plain));
        ActivatedSkills.begin();
        try {
            Map<String, Object> b = body();
            service.handleActivateSkills(b, new ToolUseCall("t2", "activate_skills", Map.of("skill_names", List.of("plain"))), packages, adapter);
            assertThat(toolNames(b)).doesNotContain("http_request");
            assertThat(String.valueOf(b.get("system"))).doesNotContain("{{CRM_TOKEN}}");
        } finally {
            ActivatedSkills.end();
        }
    }

    @Test
    void resourcePromotionAlsoRegistersActivation() {
        com.jimeng.dataserver.ai.skill.model.SkillPackage platform = new com.jimeng.dataserver.ai.skill.model.SkillPackage(
                "rag-knowledge", "查知识库", "查询指引", null, List.of(),
                new com.jimeng.dataserver.ai.skill.manifest.JmMetadata(List.of(), false, 60,
                        com.jimeng.dataserver.ai.skill.model.SkillRequirement.KNOWLEDGE_BASES, null));
        packages.clear();
        packages.put(platform.getName(), platform);
        com.jimeng.dataserver.ai.agent.runtime.AgentContext.set(com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView.builder().agentId(5L).kbIds(java.util.Set.of(1L)).build());
        ActivatedSkills.begin();
        try {
            service.applySkillContext(body(), adapter);
            assertThat(ActivatedSkills.current()).extracting(ToolPackage::getName).containsExactly("rag-knowledge");
        } finally {
            ActivatedSkills.end();
            com.jimeng.dataserver.ai.agent.runtime.AgentContext.clear();
        }
    }

}
