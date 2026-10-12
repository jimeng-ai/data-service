package com.jimeng.dataserver.ai.http;

import java.util.List;

/**
 * 解密后的一个 key，只活在一次网关请求里。范围以 skill_secret 行为准；methods / paths 为 null = 不限。
 * ★ toString 绝不带出 value。
 */
public record SkillKey(String name, Long skillId, String skillName, List<String> hosts, List<String> methods,
                       List<String> paths, String value) {
    public SkillKey {
        hosts = hosts == null ? List.of() : List.copyOf(hosts);
        methods = methods == null ? null : List.copyOf(methods);
        paths = paths == null ? null : List.copyOf(paths);
    }

    /** value 为 null = 平台解不开这个 key（没配加密密钥、密钥换过、密文坏了），见 SkillSecretService#loadKeys（R31）。 */
    public boolean available() {
        return value != null;
    }

    @Override
    public String toString() {
        return "SkillKey[" + name + " of " + skillName + " → " + hosts + (available() ? "" : "，解不开") + "]";
    }
}
