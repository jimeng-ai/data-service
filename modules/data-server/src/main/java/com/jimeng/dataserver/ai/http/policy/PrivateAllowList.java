package com.jimeng.dataserver.ai.http.policy;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 私有化部署时显式放行的内网地址（设计文档 §4.4，配置 {@code http-gateway.allow-private}）。条目是 CIDR、单个 IP（只放行这一个地址）或域名
 * （域名写法同 {@link HostPattern}）。默认为空，SaaS 不配。
 *
 * <p>只决定「这个内网地址能不能连」，不决定「这个域名能不能访问」：被放行的地址仍必须出现在某个 key 的 hosts
 * 或 skill 声明的 hosts 里（见 {@link AddressPolicy}）。本机等地址无论怎么配都放不开（{@link BlockedAddresses#isHardBlocked}）。
 */
public final class PrivateAllowList {

    public static final PrivateAllowList EMPTY = new PrivateAllowList(List.of(), List.of());

    private record Cidr(byte[] net, int bits) {
        boolean contains(byte[] ip) {
            if (ip.length != net.length) return false;
            int full = bits / 8, rest = bits % 8;
            for (int i = 0; i < full; i++) {
                if (ip[i] != net[i]) return false;
            }
            if (rest == 0) return true;
            int mask = (0xff << (8 - rest)) & 0xff;
            return (ip[full] & mask) == (net[full] & mask);
        }
    }

    private final List<Cidr> cidrs;
    private final List<HostPattern> hosts;

    private PrivateAllowList(List<Cidr> cidrs, List<HostPattern> hosts) {
        this.cidrs = cidrs;
        this.hosts = hosts;
    }

    public static PrivateAllowList parse(List<String> entries) {
        if (entries == null || entries.isEmpty()) return EMPTY;
        List<Cidr> cidrs = new ArrayList<>();
        List<HostPattern> hosts = new ArrayList<>();
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) continue;
            String e = raw.strip();
            try {
                if (e.contains("/")) {
                    int slash = e.indexOf('/');
                    byte[] net = HostPattern.literalOrNull(e.substring(0, slash));
                    int bits = Integer.parseInt(e.substring(slash + 1));
                    if (net == null || bits < 0 || bits > net.length * 8) throw new IllegalArgumentException("不是合法的 CIDR");
                    cidrs.add(new Cidr(net, bits));
                } else {
                    HostPattern h = HostPattern.parse(e);
                    if (h.isIpLiteral()) {
                        // 单写一个 IP：放行的是这个地址本身。当成主机名的话，只有网址里直接写这个 IP 才放行，
                        // 用域名访问同一台机器反而被拒，与写的人的本意相反
                        byte[] one = HostPattern.literalOrNull(h.text());
                        cidrs.add(new Cidr(one, one.length * 8));
                    } else {
                        hosts.add(h);
                    }
                }
            } catch (RuntimeException ex) {
                throw new IllegalStateException("http-gateway.allow-private 里的「" + e + "」不是合法的 CIDR 或域名", ex);
            }
        }
        return new PrivateAllowList(List.copyOf(cidrs), List.copyOf(hosts));
    }

    public boolean isEmpty() {
        return cidrs.isEmpty() && hosts.isEmpty();
    }

    public boolean permits(String host, InetAddress ip) {
        byte[] b = ip.getAddress();
        return hosts.stream().anyMatch(h -> h.matches(host)) || cidrs.stream().anyMatch(c -> c.contains(b));
    }
}
