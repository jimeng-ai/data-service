package com.jimeng.dataserver.ai.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 占位符 {{名字}} 的查找与替换（设计文档 §3.3、§4.3 第 2、5 步）。名字规则与 secrets.json 一致：{@code [A-Z][A-Z0-9_]{0,63}}。
 *
 * <p>除了原样的 {{名字}}，还认 {@code %7B%7B名字%7D%7D}（大小写十六进制都认）：Python requests 会把查询串里的大括号编码掉，
 * OkHttp 也会编码路径里的大括号。只认原样的话，key 不会被替换，占位符原样发给客户系统，表现是 401 却查不出原因。
 */
final class Placeholders {

    private static final String NAME = "[A-Z][A-Z0-9_]{0,63}";
    private static final Pattern RAW = Pattern.compile("\\{\\{(" + NAME + ")}}");
    private static final Pattern ENCODED = Pattern.compile("%7[Bb]%7[Bb](" + NAME + ")%7[Dd]%7[Dd]");

    private static final Pattern ALL = Pattern.compile(RAW.pattern() + "|" + ENCODED.pattern());

    private Placeholders() {}

    /** 网址、请求头的值、请求体里出现的全部占位符名字。请求头的名字里不认。 */
    static Set<String> find(String url, List<Map.Entry<String, String>> headers, byte[] body) {
        Set<String> names = new LinkedHashSet<>();
        collect(url, names);
        for (Map.Entry<String, String> h : headers) collect(h.getValue(), names);
        if (body != null && body.length > 0) collect(new String(body, StandardCharsets.ISO_8859_1), names);
        return names;
    }

    private static void collect(String s, Set<String> names) {
        if (s == null) return;
        Matcher m = RAW.matcher(s);
        while (m.find()) names.add(m.group(1));
        m = ENCODED.matcher(s);
        while (m.find()) names.add(m.group(1));
    }

    /** 网址里的占位符换成严格百分号编码后的值：值里的 / ? & = # 都被编码，改变不了网址的结构。 */
    static String substituteUrl(String url, Map<String, String> values) {
        return substituteOriginal(url, values, 0);
    }

    static List<Map.Entry<String, String>> substituteHeaders(List<Map.Entry<String, String>> headers, Map<String, String> values) {
        List<Map.Entry<String, String>> out = new ArrayList<>(headers.size());
        for (Map.Entry<String, String> h : headers) {
            out.add(Map.entry(h.getKey(), substituteOriginal(h.getValue(), values, 1)));
        }
        return out;
    }

    /**
     * 请求体按字节替换：用 ISO-8859-1 做字节与字符一一对应，二进制正文的其余字节一个不变。
     * 原样的 {{名字}} 换成值的 UTF-8 字节；编码过的写法（表单里常见）换成编码后的值。
     */
    static byte[] substituteBody(byte[] body, Map<String, String> values) {
        if (body == null || body.length == 0 || values.isEmpty()) return body;
        return substituteOriginal(new String(body, StandardCharsets.ISO_8859_1), values, 2)
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    /** RFC 3986：只保留 A–Z a–z 0–9 - . _ ~，其余按 UTF-8 编成 %XX（大写十六进制）。 */
    static String percentEncode(String v) {
        return com.jimeng.dataserver.ai.skill.manifest.SecretForms.percentEncode(v);
    }

    /** 一遍替换原始输入里的占位符；key 的值是不可展开的原文。mode：URL / 请求头 / 字节正文。 */
    private static String substituteOriginal(String input, Map<String, String> values, int mode) {
        Matcher matcher = ALL.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            String value = values.get(name);
            if (value == null) continue;
            String replacement = mode == 0 || mode == 2 && matcher.group(2) != null ? percentEncode(value)
                    : mode == 2 ? new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1) : value;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        return matcher.appendTail(out).toString();
    }
}
