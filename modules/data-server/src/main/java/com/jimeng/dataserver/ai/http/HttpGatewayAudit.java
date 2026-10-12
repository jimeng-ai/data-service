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
        AUDIT.info("http-gateway {} {} {} {} {} {} {} {} {} {} {} {} {} {}",
                kv("hgw_tenant", ctx.tenantId()),
                kv("hgw_agent", ctx.agentId() == null ? null : String.valueOf(ctx.agentId())),
                kv("hgw_run", ctx.runId()),
                kv("hgw_plane", ctx.plane().name()),
                kv("hgw_skills", scrubber.scrub(String.join(",", skills))),
                kv("hgw_method", scrubber.scrub(req.method())),
                kv("hgw_url", url),
                kv("hgw_status", r.status()),
                kv("hgw_outcome", r.gatewayError() == null ? "ok" : r.gatewayError()),
                kv("hgw_reason", r.message() == null ? null : scrubber.scrub(r.message())),
                kv("hgw_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)),
                kv("hgw_bytes", r.body().length),
                kv("hgw_hops", hops),
                kv("hgw_truncated", r.truncated()));
    }
}
