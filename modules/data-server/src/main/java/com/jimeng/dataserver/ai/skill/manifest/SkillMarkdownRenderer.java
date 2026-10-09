package com.jimeng.dataserver.ai.skill.manifest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.stream.Collectors;

/**
 * 由库里的列重建一份带完整 frontmatter 的 SKILL.md（构建器拿已发布的纯 PROMPT skill 当起点、评测平台 skill 时用）。
 * 输出必须能过严格模式，并且原样带上 {@code metadata.jm}：只渲染 name / description 的话，用构建器改一次再发布，
 * 声明过的域名和超时就静默丢了。
 *
 * <p>字符串用 JSON 引号写：JSON 字符串是合法的 YAML 双引号标量，冒号、引号、反斜杠、{@code *} 开头都不会被 YAML 误读。
 */
public final class SkillMarkdownRenderer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SkillMarkdownRenderer() {}

    public static String render(String name, String description, JmMetadata jm, String body) {
        String desc = description == null ? "" : description.replace("\r", " ").replace("\n", " ").strip();
        StringBuilder sb = new StringBuilder("---\n");
        sb.append("name: ").append(name == null ? "" : name.strip()).append('\n');
        sb.append("description: ").append(quote(desc)).append('\n');
        String jmBlock = jmYaml(jm);
        if (!jmBlock.isEmpty()) {
            sb.append("metadata:\n  jm:\n").append(jmBlock);
        }
        sb.append("---\n\n").append(body == null ? "" : body.strip()).append('\n');
        return sb.toString();
    }

    private static String jmYaml(JmMetadata jm) {
        if (jm == null) return "";
        StringBuilder sb = new StringBuilder();
        if (!jm.hosts().isEmpty()) {
            sb.append("    hosts: [")
              .append(jm.hosts().stream().map(SkillMarkdownRenderer::quote).collect(Collectors.joining(", ")))
              .append("]\n");
        }
        if (jm.web()) sb.append("    web: true\n");
        if (jm.timeoutSeconds() != SkillManifestParser.DEFAULT_TIMEOUT_SECONDS) {
            sb.append("    timeout-seconds: ").append(jm.timeoutSeconds()).append('\n');
        }
        if (jm.requires() != null) sb.append("    requires: ").append(jm.requires().token()).append('\n');
        if (!JmMetadata.BINDING_OPTIONAL.equals(jm.binding())) sb.append("    binding: ").append(jm.binding()).append('\n');
        return sb.toString();
    }

    static String quote(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
