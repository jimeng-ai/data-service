package com.jimeng.dataserver.ai.skill.manifest;

import com.jimeng.dataserver.ai.skill.model.SkillRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillMarkdownRendererTest {

    @Test
    @DisplayName("渲染出来的 SKILL.md 过严格校验，字段原样取回，含 metadata.jm（构建器从库里重建草稿时不能把声明弄丢）")
    void roundTripsThroughStrictParser() {
        JmMetadata jm = new JmMetadata(List.of("api.example.com", "*.cdn.example.com"), true, 90,
                SkillRequirement.CONNECTIONS, "always");
        String md = SkillMarkdownRenderer.render("refund-flow", "查订单: 先问\"订单号\"\n再查", jm, "## 流程\n正文");
        ParsedSkill p = SkillManifestParser.parse(md, SkillManifestParser.Mode.STRICT);
        assertThat(p.errors()).isEmpty();
        assertThat(p.name()).isEqualTo("refund-flow");
        assertThat(p.description()).isEqualTo("查订单: 先问\"订单号\" 再查");
        assertThat(p.jm()).isEqualTo(jm);
        assertThat(p.body()).isEqualTo("## 流程\n正文\n");
    }

    @Test
    @DisplayName("没有扩展字段时不写 metadata 块，格式与原来的 render 逐字一致")
    void noMetadataBlockWhenEmpty() {
        assertThat(SkillMarkdownRenderer.render("a", "d", JmMetadata.EMPTY, "b"))
                .isEqualTo("---\nname: a\ndescription: \"d\"\n---\n\nb\n");
        assertThat(SkillMarkdownRenderer.render("a", "d", null, "b")).doesNotContain("metadata");
    }
}
