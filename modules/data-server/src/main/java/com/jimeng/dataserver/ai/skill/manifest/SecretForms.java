package com.jimeng.dataserver.ai.skill.manifest;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/** Key 原文及常见编码写法，发布和网关共用；只降百分号编码中的十六进制，不改原文字母。 */
public final class SecretForms {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private SecretForms() {}

    public static Set<String> of(String value) {
        Set<String> forms = new LinkedHashSet<>();
        if (value == null) return forms;
        forms.add(value);
        String pct = percentEncode(value);
        forms.add(pct);
        forms.add(lowerHex(pct));
        forms.add(URLEncoder.encode(value, StandardCharsets.UTF_8));
        String json = new String(JsonStringEncoder.getInstance().quoteAsString(value));
        forms.add(json);
        forms.add(json.replace("/", "\\/"));
        return forms;
    }

    /** 编码变体的 %HH 大小写不敏感；原文（即使字面上含 %HH）仍精确匹配。 */
    public static boolean isPercentForm(String value, String form) {
        if (form.equals(value)) return false;
        String encoded = percentEncode(value);
        return form.equals(encoded) || form.equals(lowerHex(encoded))
                || form.equals(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static String lowerHex(String value) {
        char[] chars = value.toCharArray();
        for (int i = 0; i + 2 < chars.length; i++) {
            if (chars[i] == '%') {
                chars[i + 1] = Character.toLowerCase(chars[i + 1]);
                chars[i + 2] = Character.toLowerCase(chars[i + 2]);
                i += 2;
            }
        }
        return new String(chars);
    }

    /** RFC3986 严格百分号编码；只保留 unreserved 的 ASCII 字节。 */
    public static String percentEncode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 255;
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                    || c == '-' || c == '.' || c == '_' || c == '~') out.append((char)c);
            else out.append('%').append(HEX[c >> 4]).append(HEX[c & 15]);
        }
        return out.toString();
    }
}
