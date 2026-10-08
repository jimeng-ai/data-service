package com.jimeng.dataserver.ai.http.policy;

import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * skill 里声明的一个域名（{@code metadata.jm.hosts}、{@code secrets.json} 的 {@code hosts}、私网白名单的主机名）。
 *
 * <p>写法：精确域名 {@code api.example.com}；最前面一层通配 {@code *.example.com}；IP 字面量（v6 可带方括号）。
 * 通配<b>只匹配一层</b>：{@code *.example.com} 匹配 {@code a.example.com}，不匹配 {@code a.b.example.com}，也不匹配
 * {@code example.com} 本身——与证书通配、与 paths 里 {@code *} 只匹配一层是同一个口径。
 *
 * <p>★ 解析只做字面检查，<b>绝不做 DNS</b>：IPv4 自己逐段解析；含冒号的才交给 {@link InetAddress#getByName}
 * （JDK 对含冒号的串只按 v6 字面量解析，不合法直接抛，不会去查 DNS）。
 */
public final class HostPattern {

    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-f:.]+");

    private final String text;
    private final String wildcardSuffix;
    private final byte[] ip;

    private HostPattern(String text, String wildcardSuffix, byte[] ip) {
        this.text = text;
        this.wildcardSuffix = wildcardSuffix;
        this.ip = ip;
    }

    public static HostPattern parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("不能为空");
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (s.contains("://") || s.contains("/") || s.contains("@") || s.contains("?") || s.contains("#")
                || s.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("只写域名，不要带协议、路径、账号或空白");
        }
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);

        byte[] literal = literalOrNull(s);
        if (literal != null) {
            return new HostPattern(s, null, literal);
        }
        if (IPV4.matcher(s).matches() || (s.contains(":") && IPV6_CHARS.matcher(s).matches())) {
            throw new IllegalArgumentException("不是合法的 IP 地址");
        }
        if (s.contains(":")) throw new IllegalArgumentException("不是合法的域名或 IP 地址（域名后面不要带端口）");

        boolean wildcard = s.startsWith("*.");
        String rest = wildcard ? s.substring(2) : s;
        if (rest.contains("*")) throw new IllegalArgumentException("通配只能写在最前面，例如 *.example.com");
        String ascii;
        try {
            ascii = IDN.toASCII(rest, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不是合法的域名");
        }
        if (ascii.isEmpty() || ascii.length() > 253) throw new IllegalArgumentException("不是合法的域名");
        String[] labels = ascii.split("\\.", -1);
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) throw new IllegalArgumentException("不是合法的域名");
        }
        if (wildcard && labels.length < 2) {
            throw new IllegalArgumentException("通配至少要写到二级域名，例如 *.example.com");
        }
        return wildcard ? new HostPattern("*." + ascii, "." + ascii, null) : new HostPattern(ascii, null, null);
    }

    /** host 取自已解析的网址（OkHttp 的 {@code HttpUrl.host()}），大小写、结尾的点、v6 方括号都在这里抹平。 */
    public boolean matches(String host) {
        if (host == null || host.isBlank()) return false;
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (ip != null) {
            byte[] other = literalOrNull(h);
            return other != null && Arrays.equals(ip, other);
        }
        if (wildcardSuffix == null) return h.equals(text);
        if (!h.endsWith(wildcardSuffix)) return false;
        String head = h.substring(0, h.length() - wildcardSuffix.length());
        return !head.isEmpty() && !head.contains(".");
    }

    public boolean isIpLiteral() {
        return ip != null;
    }

    /** 规范化后的写法，存库、给人看都用它。 */
    public String text() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }

    /** IP 字面量转字节（v4-mapped 的 v6 归一成 4 字节）；不是 IP 字面量返回 null。永不查 DNS。 */
    static byte[] literalOrNull(String s) {
        if (IPV4.matcher(s).matches()) {
            String[] parts = s.split("\\.");
            byte[] b = new byte[4];
            for (int i = 0; i < 4; i++) {
                int v = Integer.parseInt(parts[i]);
                if (v > 255) return null;
                b[i] = (byte) v;
            }
            return b;
        }
        if (s.contains(":") && IPV6_CHARS.matcher(s).matches()) {
            try {
                return InetAddress.getByName(s).getAddress();
            } catch (UnknownHostException e) {
                return null;
            }
        }
        return null;
    }
}
