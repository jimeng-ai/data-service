package com.jimeng.dataserver.ai.skill.manifest;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.skill.model.SkillRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.jimeng.dataserver.ai.skill.manifest.SkillManifestParser.Mode.LENIENT;
import static com.jimeng.dataserver.ai.skill.manifest.SkillManifestParser.Mode.STRICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillManifestParserTest {

    private static final String OK = "---\nname: refund-flow\ndescription: 处理退款\n---\n\n## 流程\n先查订单。\n";

    @Test
    @DisplayName("取出 name / description / 正文（正文开头的空行去掉）")
    void extractsFields() {
        ParsedSkill p = SkillManifestParser.parse(OK, STRICT);
        assertThat(p.valid()).isTrue();
        assertThat(p.name()).isEqualTo("refund-flow");
        assertThat(p.description()).isEqualTo("处理退款");
        assertThat(p.body()).isEqualTo("## 流程\n先查订单。\n");
        assertThat(p.jm()).isEqualTo(JmMetadata.EMPTY);
    }

    @Test
    @DisplayName("CRLF和BOM都能解析（Windows 上编辑过的文件），两种模式都一样")
    void CRLF和BOM都能解析() {
        String crlf = "﻿" + OK.replace("\n", "\r\n");
        for (SkillManifestParser.Mode mode : SkillManifestParser.Mode.values()) {
            ParsedSkill p = SkillManifestParser.parse(crlf, mode);
            assertThat(p.errors()).as(mode.name()).isEmpty();
            assertThat(p.name()).isEqualTo("refund-flow");
            assertThat(p.description()).isEqualTo("处理退款");
        }
    }

    @Test
    @DisplayName("宽松模式：不认识的顶层键忽略并记下来，不报错；严格模式报错")
    void lenientIgnoresUnknownTopKeys() {
        String md = "---\nname: pdf\ndescription: d\nversion: 2\nauthor: x\n---\nb\n";
        ParsedSkill lenient = SkillManifestParser.parse(md, LENIENT);
        assertThat(lenient.valid()).isTrue();
        assertThat(lenient.ignoredKeys()).containsExactly("author", "version");
        ParsedSkill strict = SkillManifestParser.parse(md, STRICT);
        assertThat(strict.errors()).anyMatch(e -> e.contains("不允许的键") && e.contains("author") && e.contains("version"));
    }

    @Test
    @DisplayName("宽松模式：名字沿用上传 / 导入一直接受的形状（可大写、可下划线、字母开头、≤64）")
    void lenientNameShape() {
        assertThat(SkillManifestParser.parse("---\nname: PDF_Tool\ndescription: d\n---\nb\n", LENIENT).valid()).isTrue();
        assertThat(SkillManifestParser.parse("---\nname: 1pdf\ndescription: d\n---\nb\n", LENIENT).valid()).isFalse();
        assertThat(SkillManifestParser.parse("---\nname: PDF_Tool\ndescription: d\n---\nb\n", STRICT).valid()).isFalse();
    }

    @Test
    @DisplayName("宽松模式：正文不能为空（单文件上传一直这么要求）；严格模式不查正文")
    void lenientRequiresBody() {
        String md = "---\nname: a\ndescription: d\n---\n\n";
        assertThat(SkillManifestParser.parse(md, LENIENT).errors()).contains("SKILL.md 正文不能为空");
        assertThat(SkillManifestParser.parse(md, STRICT).valid()).isTrue();
    }

    @Test
    @DisplayName("YAML 写错时，报错里提示用引号把值括起来")
    void yamlErrorHint() {
        ParsedSkill p = SkillManifestParser.parse("---\nname: a\ndescription: use when: x\n---\nb\n", LENIENT);
        assertThat(p.errors()).singleElement().asString()
                .startsWith("frontmatter 不是合法的 YAML").contains("引号");
    }

    @Test
    @DisplayName("metadata.jm 五个字段都能取出；hosts 去重并规范化")
    void extractsJm() {
        String md = "---\nname: a\ndescription: d\nmetadata:\n  author: x\n  jm:\n"
                + "    hosts: [API.example.com, \"*.cdn.example.com\", api.example.com]\n"
                + "    web: true\n    timeout-seconds: 120\n    requires: connections\n    binding: always\n---\nb\n";
        ParsedSkill p = SkillManifestParser.parse(md, STRICT);
        assertThat(p.errors()).isEmpty();
        assertThat(p.jm().hosts()).containsExactly("api.example.com", "*.cdn.example.com");
        assertThat(p.jm().web()).isTrue();
        assertThat(p.jm().timeoutSeconds()).isEqualTo(120);
        assertThat(p.jm().requires()).isEqualTo(SkillRequirement.CONNECTIONS);
        assertThat(p.jm().binding()).isEqualTo(JmMetadata.BINDING_ALWAYS);
    }

    @Test
    @DisplayName("metadata.jm 缺省：hosts 空、web=false、超时 60、无 requires、binding=optional")
    void jmDefaults() {
        ParsedSkill p = SkillManifestParser.parse("---\nname: a\ndescription: d\nmetadata:\n  jm: {}\n---\nb\n", STRICT);
        assertThat(p.errors()).isEmpty();
        assertThat(p.jm()).isEqualTo(new JmMetadata(java.util.List.of(), false, 60, null, JmMetadata.BINDING_OPTIONAL));
    }

    @Test
    @DisplayName("metadata.jm 写错在宽松模式下也报错：那是平台自己的命名空间，拼错不能静默")
    void jmErrorsInBothModes() {
        String md = "---\nname: a\ndescription: d\nmetadata:\n  jm:\n    timeout: 60\n    web: 1\n    binding: sometimes\n---\nb\n";
        ParsedSkill p = SkillManifestParser.parse(md, LENIENT);
        assertThat(p.errors()).anyMatch(e -> e.startsWith("metadata.jm 里有不认识的键") && e.contains("timeout"));
        assertThat(p.errors()).anyMatch(e -> e.startsWith("metadata.jm.web"));
        assertThat(p.errors()).anyMatch(e -> e.startsWith("metadata.jm.binding"));
    }

    @Test
    @DisplayName("hosts 太多或写成字符串都报错")
    void hostsShape() {
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 51; i++) many.append(i == 0 ? "" : ", ").append("h").append(i).append(".example.com");
        many.append("]");
        assertThat(SkillManifestParser.parse("---\nname: a\ndescription: d\nmetadata:\n  jm:\n    hosts: " + many + "\n---\nb\n", STRICT)
                .errors()).anyMatch(e -> e.startsWith("metadata.jm.hosts"));
        assertThat(SkillManifestParser.parse("---\nname: a\ndescription: d\nmetadata:\n  jm:\n    hosts: api.example.com\n---\nb\n", STRICT)
                .errors()).anyMatch(e -> e.startsWith("metadata.jm.hosts"));
    }

    @Test
    @DisplayName("orThrow 把每条错误都列出来；合规时原样返回")
    void orThrowListsErrors() {
        assertThat(SkillManifestParser.parse(OK, STRICT).orThrow().name()).isEqualTo("refund-flow");
        assertThatThrownBy(() -> SkillManifestParser.parse("---\nname: A_B\ndescription: <x>\n---\nb\n", STRICT).orThrow())
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("kebab-case").hasMessageContaining("尖括号");
    }
}
