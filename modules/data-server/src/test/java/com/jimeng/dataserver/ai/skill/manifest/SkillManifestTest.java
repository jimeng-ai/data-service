package com.jimeng.dataserver.ai.skill.manifest;

import com.jimeng.dataserver.ai.skill.model.SkillRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillManifestTest {

    @Test
    @DisplayName("JSON 往返不丢字段；数字仍是数字（不能用 Spring 那个把数字写成字符串的 ObjectMapper）")
    void roundTrip() {
        SkillManifest m = new SkillManifest(List.of("cdn.example.com"), true, 90, "connections", "always",
                List.of(new SkillManifest.KeyDecl("CRM_TOKEN", List.of("api.example.com"), List.of("GET"), List.of("/v1/**"))));
        String json = m.toJson();
        assertThat(json).contains("\"timeoutSeconds\":90").contains("\"web\":true").doesNotContain("value");
        assertThat(SkillManifest.fromJson(json)).isEqualTo(m);
    }

    @Test
    @DisplayName("KeyDecl 的 methods / paths 有无四种组合都往返不变：null（= 不限）读回来仍是 null，不会变成空列表")
    void keyDeclNullAndNonNullCombinationsRoundTrip() {
        List<String> methods = List.of("GET", "POST");
        List<String> paths = List.of("/v1/**");
        for (List<String> m : Arrays.asList(null, methods)) {
            for (List<String> p : Arrays.asList(null, paths)) {
                SkillManifest manifest = new SkillManifest(null, null, null, null, null,
                        List.of(new SkillManifest.KeyDecl("CRM_TOKEN", List.of("api.example.com"), m, p)));
                String json = manifest.toJson();
                if (m == null) assertThat(json).as("methods 为 null 时不写出").doesNotContain("methods");
                if (p == null) assertThat(json).as("paths 为 null 时不写出").doesNotContain("paths");

                SkillManifest back = SkillManifest.fromJson(json);
                assertThat(back).isEqualTo(manifest);
                SkillManifest.KeyDecl k = back.keys().get(0);
                assertThat(k.name()).isEqualTo("CRM_TOKEN");
                assertThat(k.hosts()).containsExactly("api.example.com");
                assertThat(k.methods()).isEqualTo(m);
                assertThat(k.paths()).isEqualTo(p);
            }
        }
    }

    @Test
    @DisplayName("老数据（NULL / 空串）和坏 JSON 都按「没有扩展字段、没有 key」处理")
    void emptyAndGarbage() {
        assertThat(SkillManifest.fromJson(null)).isEqualTo(SkillManifest.EMPTY);
        assertThat(SkillManifest.fromJson(" ")).isEqualTo(SkillManifest.EMPTY);
        assertThat(SkillManifest.fromJson("{not json")).isEqualTo(SkillManifest.EMPTY);
        assertThat(SkillManifest.EMPTY.hasHttp()).isFalse();
        assertThat(SkillManifest.EMPTY.timeoutSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("hosts、web、key 任一出现就算有 HTTP 声明")
    void hasHttp() {
        assertThat(new SkillManifest(List.of("a.example.com"), false, null, null, null, null).hasHttp()).isTrue();
        assertThat(new SkillManifest(null, true, null, null, null, null).hasHttp()).isTrue();
        assertThat(new SkillManifest(null, false, null, null, null,
                List.of(new SkillManifest.KeyDecl("K_1", List.of("a.example.com"), null, null))).hasHttp()).isTrue();
        assertThat(new SkillManifest(null, null, 120, "connections", "always", null).hasHttp()).isFalse();
    }

    @Test
    @DisplayName("与 JmMetadata 互转不丢字段")
    void jmRoundTrip() {
        JmMetadata jm = new JmMetadata(List.of("a.example.com"), true, 30, SkillRequirement.KNOWLEDGE_BASES, "always");
        assertThat(SkillManifest.of(jm, List.of()).toJm()).isEqualTo(jm);
        assertThat(SkillManifest.EMPTY.toJm()).isEqualTo(JmMetadata.EMPTY);
    }
}
