package com.jimeng.dataserver.ai.stats.controller;

import com.jimeng.dataserver.ai.stats.dto.DashboardOverview;
import com.jimeng.dataserver.ai.stats.service.DashboardStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仪表盘统计接口：数据全部来自 {@code ai_model_call_log} 的真实聚合。
 */
@Tag(name = "仪表盘统计", description = "ToB Agent 平台 - 用量 / 调用 / 模型统计")
@RestController
@RequestMapping("/data/admin/stats")
@RequiredArgsConstructor
public class StatsController {

    private final DashboardStatsService dashboardStatsService;

    @Operation(summary = "仪表盘总览", description = "返回当前租户在指定天数窗口内的真实用量统计（含环比、趋势、模型用量、最近调用）")
    @GetMapping("/overview")
    public DashboardOverview overview(@RequestParam(name = "days", defaultValue = "30") int days) {
        return dashboardStatsService.overview(days);
    }
}
