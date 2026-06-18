package com.jimeng.dataserver.ai.web;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;

/**
 * web_fetch 的 SSRF 防护：仅放行 http(s)、拒 IP 字面量、拒任一解析地址命中私网 / loopback /
 * link-local / 元数据(169.254.169.254) / CGNAT。镜像沙箱 egress 代理的判定口径。
 *
 * <p>v1 已知残留：校验通过后由 HttpClient 自行再解析连接，存在窄 DNS-rebinding 窗口（沙箱用 IP-pin
 * 关掉了，Java HttpClient 不易对 HTTPS pin）。data-service 是可信宿主、URL 由模型给定，风险可控。
 */
@Component
public class WebSsrfGuard {

    /** @return null 表示放行；否则返回拒绝原因。 */
    public String validate(String rawUrl) {
        URI u;
        try {
            u = URI.create(rawUrl);
        } catch (Exception e) {
            return "unparseable url";
        }
        String scheme = u.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "scheme not allowed: " + scheme;
        }
        String host = u.getHost();
        if (host == null || host.isBlank()) {
            return "no host";
        }
        if (isIpLiteral(host)) {
            return "ip literal not allowed";
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (Exception e) {
            return "dns: " + e.getMessage();
        }
        if (addrs.length == 0) {
            return "no records";
        }
        // 任一记录命中私网即拒（防 split-horizon / 混合记录绕过）。
        for (InetAddress a : addrs) {
            if (isBlocked(a)) {
                return "blocked address " + a.getHostAddress();
            }
        }
        return null;
    }

    /** 判定单个地址是否属于禁止访问的私网/特殊段。 */
    public boolean isBlocked(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int a0 = b[0] & 0xff, a1 = b[1] & 0xff;
            if (a0 == 169 && a1 == 254) return true;            // 云元数据（link-local 已覆盖，显式兜底）
            if (a0 == 100 && a1 >= 64 && a1 <= 127) return true; // 100.64/10 CGNAT
            if (a0 == 0) return true;                            // 0.0.0.0/8
        }
        return false;
    }

    private boolean isIpLiteral(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (h.indexOf(':') >= 0) return true;                    // v6 字面量
        return h.matches("\\d{1,3}(\\.\\d{1,3}){3}");            // v4 字面量
    }
}
