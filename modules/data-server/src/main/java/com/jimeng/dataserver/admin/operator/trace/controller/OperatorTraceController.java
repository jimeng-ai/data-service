package com.jimeng.dataserver.admin.operator.trace.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jimeng.dataserver.admin.operator.common.OperatorGuard;
import com.jimeng.dataserver.admin.operator.trace.service.OperatorTraceService;
import com.jimeng.dataserver.ai.trace.controller.TraceController;
import com.jimeng.dataserver.ai.trace.dto.TraceOverview;
import com.jimeng.persistence.entity.AiTrace;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;

/**
 * 运营门户 —— 跨租户调用日志 Trace。先经 {@link OperatorGuard} 校验运营身份，
 * 再用 {@link OperatorTraceService} 跨租户聚合（多一个企业/租户筛选维度与列）。
 */
@Tag(name = "运营-调用日志 Trace", description = "跨租户检索全平台调用链路")
@RestController
@RequestMapping("/data/admin/operator/traces")
@RequiredArgsConstructor
public class OperatorTraceController {

    private final OperatorTraceService operatorTraceService;
    private final OperatorGuard operatorGuard;

    @Operation(summary = "跨租户 trace 分页列表")
    @GetMapping
    public Page<AiTrace> list(@RequestParam(name = "page", defaultValue = "1") int page,
                              @RequestParam(name = "size", defaultValue = "20") int size,
                              @RequestParam(name = "start", required = false) Long start,
                              @RequestParam(name = "end", required = false) Long end,
                              @RequestParam(name = "status", required = false) String status,
                              @RequestParam(name = "keyword", required = false) String keyword,
                              @RequestParam(name = "tenantId", required = false) String tenantId) {
        operatorGuard.requireOperatorId();
        return operatorTraceService.page(page, size, TraceController.toDate(start), TraceController.toDate(end),
                status, keyword, tenantId);
    }

    @Operation(summary = "跨租户概览统计")
    @GetMapping("/overview")
    public TraceOverview overview(@RequestParam(name = "start", required = false) Long start,
                                  @RequestParam(name = "end", required = false) Long end,
                                  @RequestParam(name = "tenantId", required = false) String tenantId) {
        operatorGuard.requireOperatorId();
        return operatorTraceService.overview(TraceController.toDate(start), TraceController.toDate(end), tenantId);
    }

    @Operation(summary = "跨租户 trace 详情")
    @GetMapping("/{traceId}")
    public AiTrace detail(@PathVariable("traceId") String traceId) {
        operatorGuard.requireOperatorId();
        return operatorTraceService.detail(traceId);
    }

    @Operation(summary = "按当前筛选导出 CSV（含企业列）")
    @GetMapping("/export")
    public void export(@RequestParam(name = "start", required = false) Long start,
                       @RequestParam(name = "end", required = false) Long end,
                       @RequestParam(name = "status", required = false) String status,
                       @RequestParam(name = "keyword", required = false) String keyword,
                       @RequestParam(name = "tenantId", required = false) String tenantId,
                       HttpServletResponse response) throws Exception {
        operatorGuard.requireOperatorId();
        TraceController.writeCsvResponse(response, "operator-traces",
                w -> operatorTraceService.exportCsv(TraceController.toDate(start), TraceController.toDate(end),
                        status, keyword, tenantId, w));
    }
}
