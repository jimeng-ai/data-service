package com.jimeng.dataserver.ai.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * 网关审计（设计文档 §4.3 第 8 步）：每次调用一行结构化日志，经 LogstashEncoder 进 ES（data-server-* 索引），
 * 字段都带 {@code hgw_} 前缀，免得和 MDC 里的 traceId / userId / path 撞名。专用 logger 名 {@code jm.http-gateway.audit}，便于单独检索。
 *
 * <p>网址记的是调用方给的占位符形式，并再过一遍 scrubber：万一调用方自己写了 key 的原文，日志里也只剩 {{名字}}。
 */
final class HttpGatewayAudit {

    private static final Logger AUDIT = LoggerFactory.getLogger("jm.http-gateway.audit");
    private static final int URL_MAX = 2000;

    private HttpGatewayAudit() {}

    static void record(HttpGatewayContext ctx, HttpGatewayRequest req, HttpGatewayResult r, List<String> skills, int hops,
                       long startedNanos, KeyScrubber scrubber) {
        String url = req.url() == null ? "" : scrubber.scrub(req.url());
        if (url.length() > URL_MAX) url = url.substring(0, URL_MAX) + "…";
        url = scrubber.scrub(url); // 截断与省略号拼接也可能重建 key
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("hgw_tenant", scrubber.scrub(ctx.tenantId()));
        fields.put("hgw_agent", ctx.agentId() == null ? null : String.valueOf(ctx.agentId()));
        fields.put("hgw_run", scrubber.scrub(ctx.runId()));
        fields.put("hgw_plane", ctx.plane().name());
        fields.put("hgw_skills", scrubber.scrub(String.join(",", skills)));
        fields.put("hgw_method", scrubber.scrub(req.method()));
        fields.put("hgw_url", url);
        fields.put("hgw_status", r.status());
        fields.put("hgw_outcome", r.gatewayError() == null ? "ok" : r.gatewayError());
        fields.put("hgw_reason", r.message() == null ? null : scrubber.scrub(r.message()));
        fields.put("hgw_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        fields.put("hgw_bytes", r.body().length);
        fields.put("hgw_hops", hops);
        fields.put("hgw_truncated", r.truncated());
        String template = "http-gateway {} {} {} {} {} {} {} {} {} {} {} {} {} {}";
        Object[] args = fields.entrySet().stream().map(f -> kv(f.getKey(), f.getValue())).toArray();
        // Logstash 同时编码 formattedMessage 和结构化字段，二者都检查，字段名与值的拼接也包含在内。
        if (!scrubber.safeJson(fields) || !scrubber.safeJson(org.slf4j.helpers.MessageFormatter.arrayFormat(template, args).getMessage())) return;
        AUDIT.info(template, args);
    }
}
