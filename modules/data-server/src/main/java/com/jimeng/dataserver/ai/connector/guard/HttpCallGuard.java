package com.jimeng.dataserver.ai.connector.guard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.web.WebSsrfGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * HTTP 连接器的调用闸门：方法白名单 + 路径 glob 白名单 + 路径穿越 + SSRF + 绝对 URL 覆盖。
 *
 * <h3>这个类为什么必须存在</h3>
 * {@code allow_methods} / {@code allow_paths} 这两列今天<b>只有沙箱的 egress 代理在执行</b>
 * （{@code jm-agent-sandbox/docker/egress-proxy.mjs}），data-server 进程内<b>零校验</b>——
 * 连接的执行全在容器侧，平台只负责把凭据发给边车。
 *
 * <p>技术架构 §2 要求把业务调用收回平台侧，于是平台侧多出一条执行路径（与沙箱那条并存，不拆旧路）。
 * 这条新路<b>绕过了 egress 代理</b>，所以那层白名单在新路上凭空消失——除非在这里原样重新实现一遍。
 * 这就是本类的全部理由。它的判定口径刻意对齐 egress 代理，<b>同一条连接在两条路上行为必须一致</b>，
 * 否则「在沙箱里调得通、在对话里调不通」会变成一类没人说得清的怪问题。
 *
 * <h3>已知残留风险，如实记在这里</h3>
 * SSRF 复用 {@link WebSsrfGuard}，而它自陈有一个窄的 <b>DNS-rebinding 窗口</b>：校验时解析一次域名，
 * 真正发请求时由 HTTP 客户端<b>再解析一次</b>，两次之间 DNS 记录可以被换成内网地址。
 * 沙箱那边用 IP-pin 关掉了这个窗口（解析后钉死 IP 再连），平台侧没有——OkHttp 对 HTTPS 做 IP-pin
 * 会破坏 SNI 与证书校验。所以：<b>这一层挡的是配错和粗暴的内网探测，挡不住蓄意的 rebinding。</b>
 * 别假装它挡得住。
 *
 * <h3>检查顺序是固定的，且有意义</h3>
 * 方法 → 绝对 URL 覆盖 → 路径穿越 → 路径 glob → SSRF。
 * 先本地后网络（SSRF 要做 DNS，是唯一有 I/O 的一步，放最后）；方法是写操作闸门，越早拒越好。
 * 这是一个按固定顺序执行的框架方法，<b>不要拆成责任链</b>——顺序本身是语义的一部分，
 * 拆开之后「谁在谁前面」就变成了配置问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpCallGuard {

    /** Spring 的 AntPathMatcher：{@code **} 跨段、{@code *} 段内，与 egress 代理的 globToRegExp 同语义。匹配本身线程安全。 */
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** 缺省只读。写方法必须在 allow_methods 里显式声明——手册可以教模型调什么，能不能调由这里决定。 */
    private static final Set<String> DEFAULT_METHODS = Set.of("GET");

    private static final List<String> DEFAULT_PATHS = List.of("/**");

    private final WebSsrfGuard ssrfGuard;

    /**
     * 违规抛 {@link ConnectorException}；通过则返回拼好的最终 URL。
     *
     * @param path 模型给的路径，可带 query（{@code /v1/orders?status=paid}）
     */
    public String check(String baseUrl, String method, String path,
                        String allowMethodsCsv, String allowPathsJson) {
        URI base = parseBase(baseUrl);

        // ① 方法白名单。
        String m = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (m.isEmpty()) {
            throw new ConnectorException(ConnectorErrorCode.CONFIG_ERROR,
                    "没有指定 HTTP 方法，operation 的形状应当是「GET /v1/orders」");
        }
        Set<String> allowedMethods = parseMethods(allowMethodsCsv);
        if (!allowedMethods.contains(m)) {
            throw new ConnectorException(ConnectorErrorCode.FORBIDDEN,
                    "这条连接不允许 " + m + " 方法，当前允许的是 " + String.join(" / ", allowedMethods));
        }

        // ② 绝对 URL 覆盖：模型给一个完整的 http(s)://… 就等于自己换了目标主机，
        //    baseUrl 白名单与 SSRF 校验一起形同虚设。协议相对（//host/x）同理。
        String raw = path == null || path.isBlank() ? "/" : path.trim();
        if (isAbsolute(raw)) {
            throw new ConnectorException(ConnectorErrorCode.FORBIDDEN,
                    "path 只能是以 / 开头的相对路径，不能是完整 URL——目标主机由连接配置决定，不由调用方决定");
        }
        if (!raw.startsWith("/")) {
            throw new ConnectorException(ConnectorErrorCode.CONFIG_ERROR,
                    "path 必须以 / 开头，例如 /v1/orders");
        }

        // ③ 路径穿越。只判路径部分，query 不判——query 里出现 %2F 是合法的
        //    （例如 ?url=https%3A%2F%2Fx），一起判会把正常调用误杀。egress 代理也是这么切的。
        int q = raw.indexOf('?');
        String rawPath = q >= 0 ? raw.substring(0, q) : raw;
        String query = q >= 0 ? raw.substring(q) : "";
        assertNoTraversal(rawPath);

        // ④ 路径 glob 白名单。匹配用未解码的 rawPath：③ 已经把编码过的分隔符全拒了，
        //    此时 raw 与 decoded 的分段结构必然一致，而 rawPath 才是真正发上线的那个字符串。
        List<String> allowedPaths = parsePaths(allowPathsJson);
        boolean hit = false;
        for (String p : allowedPaths) {
            if (MATCHER.match(p, rawPath)) {
                hit = true;
                break;
            }
        }
        if (!hit) {
            throw new ConnectorException(ConnectorErrorCode.FORBIDDEN,
                    "路径 " + rawPath + " 不在这条连接的允许范围内，允许的是 " + String.join(" / ", allowedPaths));
        }

        // ⑤ SSRF。只校 baseUrl：③ 已经保证目标主机完全由 baseUrl 决定，
        //    而把模型给的 path 拼进去再交给 URI.create，一个没编码的字符就会让它抛解析异常，
        //    那会被误报成「SSRF 拦截」——错误分类比漏判更难排查。
        String reason = ssrfGuard.validate(base.toString());
        if (reason != null) {
            // 原始原因（含解析出来的 IP）只准进日志：safeDetail 会进模型上下文与审计。
            log.warn("连接器 HTTP 调用被 SSRF 闸拦下: baseUrl={} reason={}", baseUrl, reason);
            throw new ConnectorException(ConnectorErrorCode.CONFIG_ERROR,
                    "baseUrl 指向的地址不允许访问（内网 / 环回 / 云元数据地址），请检查连接配置");
        }

        // ⑥ 拼最终 URL。baseUrl 的 path 前缀要保留（https://x/v1 + /orders = https://x/v1/orders）——
        //    剥掉它会把路径悄悄改错而且不报错，沙箱那边也踩过同一个坑（egress.ts 的注释）。
        String prefix = base.toString();
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + rawPath + query;
    }

    /** 逗号分隔 → 大写集合。空则只读（GET）。公开是为了 {@code verifyReadOnly} 用同一份定义，避免两处漂移。 */
    public static Set<String> parseMethods(String csv) {
        if (csv == null || csv.isBlank()) {
            return DEFAULT_METHODS;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : csv.split(",")) {
            String t = s.trim().toUpperCase(Locale.ROOT);
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out.isEmpty() ? DEFAULT_METHODS : out;
    }

    /**
     * JSON 数组 → glob 列表。<b>解析失败一律抛</b>。
     *
     * <p>这一条是刻意跟 {@code ConnectionResolver.parsePaths} 反着来的：那边解析失败返回 null，
     * 让边车回落到默认 {@code ["/**"]}——那是<b>放宽</b>方向的回落，一个手抖写坏的 JSON
     * 会把「只许调 /v1/orders」静默变成「什么都能调」，而且只有一条 warn 日志。
     * 护栏往放宽方向回落，等于没有护栏。这里宁可这条连接用不了，也不要它悄悄敞开。
     */
    static List<String> parsePaths(String json) {
        if (json == null || json.isBlank()) {
            return DEFAULT_PATHS;
        }
        List<String> parsed;
        try {
            parsed = CommonUtil.getObjectMapper().readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("连接的 allow_paths 不是合法 JSON 数组，拒绝本次调用: {}", e.getMessage());
            throw new ConnectorException(ConnectorErrorCode.CONFIG_ERROR,
                    "allow_paths 不是合法的 JSON 数组（例如 [\"/v1/**\"]），请修正连接配置后重试");
        }
        if (parsed == null || parsed.isEmpty()) {
            // 空数组既不是「不限制」也不是「全禁止」，语义不明确。egress 代理把它当 ["/**"]（放宽），
            // 这里不跟：与其两边都靠猜，不如让它当场报错、由人写清楚。这是刻意的口径分歧，记在这里。
            throw new ConnectorException(ConnectorErrorCode.CONFIG_ERROR,
                    "allow_paths 是空数组，语义不明确：不限制请删除该字段，限制请写明 glob（如 [\"/v1/**\"]）");
        }
        for (String p : parsed) {
            if (p == null || !p.startsWith("/")) {
                throw new ConnectorException(ConnectorErrorCode.CONFIG_ERROR,
                        "allow_paths 的每一项都必须以 / 开头，发现非法项: " + p);
            }
        }
        return parsed;
    }

    private URI parseBase(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ConnectorException(ConnectorErrorCode.CONFIG_ERROR, "连接没有配置 baseUrl");
        }
        URI u;
        try {
            u = URI.create(baseUrl.trim());
        } catch (Exception e) {
            throw new ConnectorException(ConnectorErrorCode.CONFIG_ERROR, "baseUrl 不是合法的 URL");
        }
        String scheme = u.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new ConnectorException(ConnectorErrorCode.CONFIG_ERROR, "baseUrl 只支持 http / https");
        }
        if (u.getHost() == null || u.getHost().isBlank()) {
            throw new ConnectorException(ConnectorErrorCode.CONFIG_ERROR, "baseUrl 缺少主机名");
        }
        if (u.getQuery() != null || u.getFragment() != null) {
            // 拼接是字符串拼接，baseUrl 带 ?/# 会把 path 拼到 query 里去，结果完全不是配置者想要的。
            throw new ConnectorException(ConnectorErrorCode.CONFIG_ERROR, "baseUrl 不能带查询串或锚点");
        }
        return u;
    }

    /** {@code http://…} / {@code https://…} / 任意 {@code scheme:} / 协议相对 {@code //host}。 */
    private boolean isAbsolute(String raw) {
        if (raw.startsWith("//")) {
            return true;
        }
        int colon = raw.indexOf(':');
        int slash = raw.indexOf('/');
        // scheme 必须出现在第一个 / 之前才算绝对 URL；/a:b 这种路径里的冒号不算。
        return colon > 0 && (slash < 0 || colon < slash);
    }

    /**
     * 路径穿越。<b>先拒编码、再解码复判</b>，两道都在。
     *
     * <p>【沙箱侧实测的绕过，别删这段】只做锚定匹配是不够的：{@code allow_paths=["/v1/**"]} 时，
     * {@code /v1/%2e%2e/admin/secrets} 与 {@code /v1/..%2fadmin} 都能<b>匹配通过</b>
     * （匹配器看到的是未解码的字面量，确实以 /v1/ 开头），然后被原样转发给上游。
     * 逃不逃得出去完全取决于上游规范化不规范化——等于把闸门交给别人实现。
     * 实测 api.anthropic.com 不规范化所以回 404，换个会规范化的上游就是真穿越。
     *
     * <p>所以不在匹配上做文章：正常的 API 路径不需要编码过的 {@code . / \}，而任何穿越变体都绕不开它们。
     * {@code %25} 也要拒——它解码成 {@code %}，是给二次编码（{@code %252e} → {@code %2e} → {@code .}）铺路的。
     */
    private void assertNoTraversal(String rawPath) {
        String lower = rawPath.toLowerCase(Locale.ROOT);
        for (int i = 0; i < rawPath.length(); i++) {
            char c = rawPath.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\\') {
                throw new ConnectorException(ConnectorErrorCode.FORBIDDEN, "path 含控制字符或反斜杠");
            }
        }
        if (lower.contains("%25")) {
            throw new ConnectorException(ConnectorErrorCode.FORBIDDEN, "path 含二次编码（%25），已拒绝");
        }
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")) {
            throw new ConnectorException(ConnectorErrorCode.FORBIDDEN,
                    "path 含编码过的路径分隔符或点（%2e / %2f / %5c），已拒绝");
        }
        // 解码一次再复判：上面的字面量拒绝已经覆盖了已知变体，这一道是兜我们没想到的编码形式。
        String decoded;
        try {
            decoded = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ConnectorException(ConnectorErrorCode.FORBIDDEN, "path 含非法的百分号编码");
        }
        for (String candidate : new String[]{rawPath, decoded}) {
            if (candidate.contains("//")) {
                throw new ConnectorException(ConnectorErrorCode.FORBIDDEN,
                        "path 含连续斜杠，不同服务端对它的归一方式不一致，一律拒绝");
            }
            if (candidate.indexOf('\\') >= 0) {
                throw new ConnectorException(ConnectorErrorCode.FORBIDDEN, "path 含反斜杠");
            }
            for (String seg : candidate.split("/", -1)) {
                if (seg.equals("..") || seg.equals(".")) {
                    throw new ConnectorException(ConnectorErrorCode.FORBIDDEN,
                            "path 含相对段（. 或 ..），已拒绝");
                }
            }
        }
    }
}
