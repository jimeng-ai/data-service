package com.jimeng.dataserver.ai.http.policy;

import java.net.InetAddress;
import java.util.Arrays;

/**
 * 内网 / 保留地址判定（设计文档 §4.3 第 4 步）。规则与出网代理 {@code docker/egress-proxy.mjs} 的
 * {@code isBlockedIp} 逐条一致，两边用 {@code contract/ip-block-cases.json} 同一组用例对拍，改一边必须改另一边。
 *
 * <p>不复用 {@code WebSsrfGuard}：它漏了 IPv6 ULA、NAT64 等段（§4.3）。
 *
 * <p>JDK 解析地址时会把 v4-mapped（{@code ::ffff:a.b.c.d}）归一成 {@link java.net.Inet4Address}，但
 * {@code Inet6Address.getByAddress} 能原样造出 16 字节的，所以 16 字节的 v4-mapped 也按内嵌的 v4 判；代理那边按 16 字节判，结论相同。
 */
public final class BlockedAddresses {

    private BlockedAddresses() {}

    public static boolean isBlocked(InetAddress address) {
        byte[] b = address.getAddress();
        return b.length == 4 ? blockedV4(b) : blockedV6(b);
    }

    /**
     * 任何私网白名单都放不开的地址：本机、链路本地（含云元数据 169.254.169.254）、未指定、组播 / 广播，
     * 以及内嵌了这些 v4 地址的 v6。本机上跑着 Nacos、Redis、MySQL，Nacos 的 HTTP 接口能读写全部配置。
     */
    public static boolean isHardBlocked(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length == 4) return hardV4(b);
        if (allZero(b, 0, 12)) return true;                                   // ::/96：::、::1、IPv4 兼容
        byte[] embedded = embeddedV4(b);
        if (embedded != null) return hardV4(embedded);
        return (u(b[0]) == 0xfe && (u(b[1]) & 0xc0) == 0x80) || u(b[0]) == 0xff; // fe80::/10、ff00::/8
    }

    static boolean blockedV4(byte[] b) {
        int a = u(b[0]), c = u(b[1]), d = u(b[2]);
        return a == 0 || a == 10 || a == 127 || a >= 224
                || (a == 169 && c == 254)
                || (a == 172 && c >= 16 && c <= 31)
                || (a == 192 && c == 168)
                || (a == 100 && c >= 64 && c <= 127)
                || (a == 192 && c == 0 && (d == 0 || d == 2))
                || (a == 198 && (c == 18 || c == 19))
                || (a == 198 && c == 51 && d == 100)
                || (a == 203 && c == 0 && d == 113);
    }

    static boolean blockedV6(byte[] b) {
        if (allZero(b, 0, 12)) return true;                                   // ::/96：未指定、本机、IPv4 兼容地址
        byte[] embedded = embeddedV4(b);
        if (embedded != null) return blockedV4(embedded);                     // IPv4 转换、NAT64、6to4：查内嵌地址
        if (u(b[0]) == 0x00 && u(b[1]) == 0x64 && u(b[2]) == 0xff && u(b[3]) == 0x9b
                && u(b[4]) == 0x00 && u(b[5]) == 0x01) return true;            // 64:ff9b:1::/48 本地 NAT64
        if (u(b[0]) == 0x20 && u(b[1]) == 0x01
                && ((u(b[2]) == 0x00 && u(b[3]) == 0x00) || (u(b[2]) == 0x0d && u(b[3]) == 0xb8))) return true; // Teredo、文档段
        if ((u(b[0]) & 0xfe) == 0xfc) return true;                            // fc00::/7 ULA
        if (u(b[0]) == 0xfe && (u(b[1]) & 0xc0) == 0x80) return true;         // fe80::/10 链路本地
        if (u(b[0]) == 0xfe && (u(b[1]) & 0xc0) == 0xc0) return true;         // fec0::/10 已废弃的 site-local
        return u(b[0]) == 0xff;                                               // ff00::/8 组播
    }

    /** v4-mapped（::ffff:a.b.c.d）、IPv4 转换（::ffff:0:a.b.c.d）、NAT64（64:ff9b::/96）、6to4（2002::/16）里内嵌的 v4；都不是返回 null。 */
    private static byte[] embeddedV4(byte[] b) {
        if (allZero(b, 0, 10) && u(b[10]) == 0xff && u(b[11]) == 0xff) return Arrays.copyOfRange(b, 12, 16);
        if (allZero(b, 0, 8) && u(b[8]) == 0xff && u(b[9]) == 0xff && allZero(b, 10, 12)) return Arrays.copyOfRange(b, 12, 16);
        if (u(b[0]) == 0x00 && u(b[1]) == 0x64 && u(b[2]) == 0xff && u(b[3]) == 0x9b && allZero(b, 4, 12)) {
            return Arrays.copyOfRange(b, 12, 16);
        }
        if (u(b[0]) == 0x20 && u(b[1]) == 0x02) return Arrays.copyOfRange(b, 2, 6);
        return null;
    }

    private static boolean hardV4(byte[] b) {
        int a = u(b[0]), c = u(b[1]);
        return a == 0 || a == 127 || a >= 224 || (a == 169 && c == 254);
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }

    private static int u(byte v) {
        return v & 0xff;
    }
}
