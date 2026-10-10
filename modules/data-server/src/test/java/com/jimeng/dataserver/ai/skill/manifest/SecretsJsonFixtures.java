package com.jimeng.dataserver.ai.skill.manifest;

import java.util.LinkedHashMap;
import java.util.Map;

/** 测试用的 secrets.json 样本：值集合「确定不了」的几种写法（说明 → 内容）。每一种里都藏着 marker，任何输出里出现它就是回显了条目。 */
public final class SecretsJsonFixtures {

    private SecretsJsonFixtures() {}

    public static Map<String, String> undeterminable(String token, String marker) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("写到一半（JSON 读不出来）", "{\"CRM_TOKEN\":{\"value\":\"" + token + "\",\"hosts\":[\"" + marker + "\",");
        StringBuilder keys = new StringBuilder("{");
        for (int i = 0; i < 33; i++) {
            keys.append(i == 0 ? "" : ",").append("\"K").append(i).append("\":{\"value\":\"").append(marker).append("-").append(i)
                    .append("-xx\",\"hosts\":[\"a.example.com\"]}");
        }
        m.put("33 个 key", keys.append("}").toString());
        StringBuilder candidates = new StringBuilder("{\"K_1\":{\"value\":\"" + token + "\",\"hosts\":[\"a.example.com\"]");
        for (int i = 0; i < 65; i++) candidates.append(",\"note").append(i).append("\":\"").append(marker).append("-").append(i).append("-yy\"");
        m.put("65 个候选值", candidates.append("}}").toString());
        m.put("8193 个字符的值", "{\"K_1\":{\"value\":\"" + marker + "x".repeat(8193) + "\",\"hosts\":[\"a.example.com\"]}}");
        m.put("超过 512 KB", "{\"K_1\":{\"value\":\"" + marker + "-12345678\",\"hosts\":[\"a.example.com\"]}}" + " ".repeat(SkillSecretsFile.MAX_FILE_BYTES));
        m.put("key 的名字就是它自己的值", "{\"" + token + "\":{\"value\":\"" + token + "\",\"hosts\":[\"a.example.com\"]}}");
        m.put("嵌套超过 8 层", "{\"K_1\":{\"hosts\":[\"a.example.com\"],\"note\":" + "[".repeat(8) + "\"" + marker + "-12345678\"" + "]".repeat(8) + "}}");
        return m;
    }
}
