package com.jimeng.dataserver.ai.skill.manifest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.dataserver.ai.skill.model.SkillRequirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 存进 {@code ai_skill.manifest} / {@code ai_skill_version.manifest} 的解析结果（设计文档 §3.6）：
 * {@code metadata.jm} 的字段，加上 key 的声明（名字和发送范围，<b>不含值</b>）。运行时只读它，不再重新解析 SKILL.md。
 *
 * <p>key 能不能发往某处，以 {@code skill_secret} 行上的范围为准（§3.4 第 2 条）；这里的 {@link #keys} 只给人和模型看。
 *
 * <p>JSON 用自己的 ObjectMapper：Spring 的那个全局开了 write_numbers_as_strings，会把 timeoutSeconds 写成字符串。
 *
 * <p>★ data-server 的 Jackson 是 2.11.1（redisson 的 BOM 钉住的），不认 record：每个组件都写明 {@code @JsonProperty}，
 * 规范构造器标 {@code @JsonCreator}。漏了，写出是空对象或抛「no properties discovered」，读回抛「no Creators」。
 * {@code SkillManifestTest} 用真实 ObjectMapper 往返兜着。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillManifest(@JsonProperty("hosts") List<String> hosts,
                            @JsonProperty("web") Boolean web,
                            @JsonProperty("timeoutSeconds") Integer timeoutSeconds,
                            @JsonProperty("requires") String requires,
                            @JsonProperty("binding") String binding,
                            @JsonProperty("keys") List<KeyDecl> keys) {

    private static final Logger log = LoggerFactory.getLogger(SkillManifest.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** key 的声明。methods / paths 为 null = 不限。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KeyDecl(@JsonProperty("name") String name,
                          @JsonProperty("hosts") List<String> hosts,
                          @JsonProperty("methods") List<String> methods,
                          @JsonProperty("paths") List<String> paths) {
        @JsonCreator
        public KeyDecl {
        }
    }

    public static final SkillManifest EMPTY = new SkillManifest(List.of(), false, null, null, null, List.of());

    @JsonCreator
    public SkillManifest {
        hosts = hosts == null ? List.of() : List.copyOf(hosts);
        web = Boolean.TRUE.equals(web);
        timeoutSeconds = timeoutSeconds == null || timeoutSeconds <= 0
                ? SkillManifestParser.DEFAULT_TIMEOUT_SECONDS
                : Math.min(timeoutSeconds, SkillManifestParser.MAX_TIMEOUT_SECONDS);
        keys = keys == null ? List.of() : List.copyOf(keys);
    }

    public static SkillManifest of(JmMetadata jm, List<KeyDecl> keys) {
        JmMetadata j = jm == null ? JmMetadata.EMPTY : jm;
        return new SkillManifest(j.hosts(), j.web(), j.timeoutSeconds(),
                j.requires() == null ? null : j.requires().token(), j.binding(), keys);
    }

    public JmMetadata toJm() {
        return new JmMetadata(hosts, web, timeoutSeconds,
                requires == null ? null : SkillRequirement.parse(requires), binding);
    }

    /** 有没有 HTTP 声明：hosts、key、web 三者之一（设计文档 §4.1）。有才把 http_request 并入工具列表。 */
    public boolean hasHttp() {
        return !hosts.isEmpty() || web || !keys.isEmpty();
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("manifest 序列化失败", e);
        }
    }

    /** NULL / 空串（老数据）返回 {@link #EMPTY}；坏 JSON 也返回 EMPTY 并报错——按「没有 HTTP 声明」处理是失败即关闭的方向。 */
    public static SkillManifest fromJson(String json) {
        if (json == null || json.isBlank()) return EMPTY;
        try {
            SkillManifest m = JSON.readValue(json, SkillManifest.class);
            return m == null ? EMPTY : m;
        } catch (Exception e) {
            log.error("skill manifest 不是合法的 JSON，按没有扩展字段处理：{}", e.getMessage());
            return EMPTY;
        }
    }
}
