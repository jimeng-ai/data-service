package com.jimeng.dataserver.ai.http;

import com.jimeng.dataserver.ai.skill.manifest.SecretForms;
import com.jimeng.dataserver.ai.skill.manifest.SecretValues;
import java.util.ArrayList;
import java.util.List;

/**
 * 回包正文、响应头、错误和审计的 key 写法集合；匹配和替换只有 SecretValues 一处。
 * 时间 O(写法数 × 正文长度)，严格模式以 KMP 覆盖重叠区间并校验输出标记；堆 O(上限 + maxLength)，另加写法本身。
 * 写法数 ≤ 6 × key 数，每个 skill 至多 32 个 key，脚本还受沙箱并发上限约束。
 */
final class KeyScrubber {
    static final KeyScrubber NONE = new KeyScrubber(List.of());
    record Scrubbed(byte[] bytes, boolean stopped) {}
    private final SecretValues values;
    private final int maxLength;

    KeyScrubber(List<SkillKey> keys) {
        List<SecretValues.Item> items = new ArrayList<>();
        int longest = 0;
        for (SkillKey key : keys) {
            if (!key.available()) continue;
            for (String form : SecretForms.of(key.value())) {
                items.add(new SecretValues.Item(key.name(), form, SecretForms.isPercentForm(key.value(), form)));
                longest = Math.max(longest, form.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            }
        }
        values = SecretValues.of(items);
        maxLength = longest;
    }
    int maxLength() { return maxLength; }
    boolean secretBearingName(String name) { return !values.namesInIgnoreCase(name).isEmpty(); }
    byte[] scrub(byte[] body) { return values.scrubBytes(body, Integer.MAX_VALUE).bytes(); }
    Scrubbed scrub(byte[] body, int maxOut) {
        SecretValues.ByteReplacement result = values.scrubBytes(body, maxOut);
        return new Scrubbed(result.bytes(), result.stopped());
    }
    String scrub(String text) { return values.scrubJson(text); }
    boolean safeJson(Object value) { return values.safeJson(value); }
}
