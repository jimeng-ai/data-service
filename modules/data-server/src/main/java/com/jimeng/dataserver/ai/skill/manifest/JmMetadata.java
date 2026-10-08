package com.jimeng.dataserver.ai.skill.manifest;

import com.jimeng.dataserver.ai.skill.model.SkillRequirement;

import java.util.List;

/**
 * SKILL.md frontmatter 里 {@code metadata.jm} 的五个字段（设计文档 §3.2）。平台扩展字段只放这里，
 * 所以 skill 仍是标准 skill，拿到别的平台也能用。
 *
 * @param hosts          不带 key 也要访问的域名，已规范化（{@code HostPattern.text()}）
 * @param web            true = 可以访问任意公网地址（端口限 80 / 443）
 * @param timeoutSeconds 这个 skill 发出的单次请求的总超时，1–180，默认 60
 * @param requires       只给平台原语说明书用（connector / rag-knowledge），null = 没有前置资源
 * @param binding        只给平台 skill 用（S2b 消费），always / optional，默认 optional
 */
public record JmMetadata(List<String> hosts, boolean web, int timeoutSeconds, SkillRequirement requires, String binding) {

    public static final String BINDING_ALWAYS = "always";
    public static final String BINDING_OPTIONAL = "optional";

    public static final JmMetadata EMPTY =
            new JmMetadata(List.of(), false, SkillManifestParser.DEFAULT_TIMEOUT_SECONDS, null, BINDING_OPTIONAL);

    public JmMetadata {
        hosts = hosts == null ? List.of() : List.copyOf(hosts);
        binding = binding == null ? BINDING_OPTIONAL : binding;
    }
}
