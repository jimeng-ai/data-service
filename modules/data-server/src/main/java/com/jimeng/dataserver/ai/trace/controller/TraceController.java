package com.jimeng.dataserver.ai.trace.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jimeng.dataserver.ai.trace.dto.TraceOverview;
import com.jimeng.dataserver.ai.trace.dto.TraceReplay;
import com.jimeng.dataserver.ai.trace.service.TraceQueryService;
import com.jimeng.dataserver.ai.trace.service.TraceReplayService;
import com.jimeng.persistence.entity.AiTrace;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 调用日志 · Trace（租户侧）：列表 / 详情 / 概览 / CSV 导出。
 * 租户隔离由 MyBatis-Plus 租户拦截器在 {@code ai_trace} 上自动完成。
 */
@Tag(name = "调用日志 Trace", description = "按 trace 检索调用链路，展开步骤时间线")
@RestController
@RequestMapping("/data/admin/trace")
@RequiredArgsConstructor
@Slf4j
public class TraceController {

    private final TraceQueryService traceQueryService;
    private final TraceReplayService traceReplayService;

    @Operation(summary = "trace 分页列表")
    @GetMapping
    public Page<AiTrace> list(@RequestParam(name = "page", defaultValue = "1") int page,
                              @RequestParam(name = "size", defaultValue = "20") int size,
                              @RequestParam(name = "start", required = false) Long start,
                              @RequestParam(name = "end", required = false) Long end,
                              @RequestParam(name = "status", required = false) String status,
                              @RequestParam(name = "keyword", required = false) String keyword,
                              @RequestParam(name = "sceneCode", required = false) String sceneCode) {
        return traceQueryService.page(page, size, toDate(start), toDate(end), status, keyword, sceneCode);
    }

    @Operation(summary = "概览统计（trace 数 / 平均耗时 / 错误率）")
    @GetMapping("/overview")
    public TraceOverview overview(@RequestParam(name = "start", required = false) Long start,
                                  @RequestParam(name = "end", required = false) Long end) {
        return traceQueryService.overview(toDate(start), toDate(end));
    }

    @Operation(summary = "trace 详情（含有序步骤时间线）")
    @GetMapping("/{traceId}")
    public AiTrace detail(@PathVariable("traceId") String traceId) {
        return traceQueryService.detail(traceId);
    }

    @Operation(summary = "trace 可视化回放（只读重现，含每步真实输入/输出）")
    @GetMapping("/{traceId}/replay")
    public TraceReplay replay(@PathVariable("traceId") String traceId) {
        return traceReplayService.replay(traceId);
    }

    @Operation(summary = "按当前筛选导出 CSV")
    @GetMapping("/export")
    public void export(@RequestParam(name = "start", required = false) Long start,
                       @RequestParam(name = "end", required = false) Long end,
                       @RequestParam(name = "status", required = false) String status,
                       @RequestParam(name = "keyword", required = false) String keyword,
                       @RequestParam(name = "sceneCode", required = false) String sceneCode,
                       HttpServletResponse response) throws Exception {
        // 二进制/文件下载必须直接写出 OutputStream（void 返回），否则被 GlobalResponseHandler 包成 JSON。
        writeCsvResponse(response, "traces",
                w -> traceQueryService.exportCsv(toDate(start), toDate(end), status, keyword, sceneCode, w));
    }

    // ------------------------------------------------------------------ helpers

    public interface CsvBody {
        void write(Writer w) throws Exception;
    }

    public static void writeCsvResponse(HttpServletResponse response, String filePrefix, CsvBody body) throws Exception {
        response.setContentType("text/csv;charset=UTF-8");
        String filename = URLEncoder.encode(filePrefix + ".csv", StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filePrefix + ".csv\"; filename*=UTF-8''" + filename);
        try (Writer w = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8)) {
            body.write(w);
            w.flush();
        }
    }

    public static Date toDate(Long epochMs) {
        return epochMs == null ? null : new Date(epochMs);
    }
}
