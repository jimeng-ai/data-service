package com.jimeng.dataserver.ai.http;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.jimeng.dataserver.ai.skill.manifest.SkillManifest;

/**
 * 「本次可用的 skill」里的一个版本（设计文档 §4.1）：对话平面是本轮已激活的 skill，沙箱是运行凭证里列出的 skill。
 * key、hosts、web 都只从这些 skill 里取。skillId 为 null 的（平台 skill）在 S1 没有 key。
 */
public record SkillGrant(@JsonProperty("skillId") Long skillId, @JsonProperty("version") int version,
                         @JsonProperty("skillName") String skillName, @JsonProperty("manifest") SkillManifest manifest) {
    // ★ Task 8 把它序列化进 Redis 的运行登记。data-server 的 Jackson 是 2.11.1，不认 record：组件写明 JSON 名、构造器标 @JsonCreator
    @JsonCreator
    public SkillGrant {
        manifest = manifest == null ? SkillManifest.EMPTY : manifest;
    }
}
