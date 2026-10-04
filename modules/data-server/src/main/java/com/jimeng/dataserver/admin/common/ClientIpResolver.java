package com.jimeng.dataserver.admin.common;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * 登录限流用的来源 IP。
 *
 * <p>链路是 Cloudflare 隧道 → 前端 nginx → 网关 → data-server，{@code getRemoteAddr()} 拿到的是网关容器的地址，
 * 所以按以下顺序取：{@code CF-Connecting-IP}（Cloudflare 边缘写入的真实客户端）→ {@code X-Forwarded-For} 第一跳
 * → {@code X-Real-IP} → {@code getRemoteAddr()}。
 *
 * <p><b>只用于限流，不用于鉴权。</b>局域网里直连的人可以伪造这些头来换 IP，所以限流另外按账号计一份总数
 * （见 {@link LoginAttemptGuard}）。值只接受 2–45 位、由十六进制数字、冒号、点组成的串（IPv4/IPv6），
 * 其余一律当作没有：它要拼进 Redis key，不能让任意字符串进去。
 */
public final class ClientIpResolver {

    private static final Pattern IP_LIKE = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String cf = clean(request.getHeader("CF-Connecting-IP"));
        if (cf != null) {
            return cf;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null) {
            String first = clean(xff.split(",", 2)[0]);
            if (first != null) {
                return first;
            }
        }
        String real = clean(request.getHeader("X-Real-IP"));
        if (real != null) {
            return real;
        }
        String remote = clean(request.getRemoteAddr());
        return remote != null ? remote : "unknown";
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return IP_LIKE.matcher(v).matches() ? v : null;
    }
}
