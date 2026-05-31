package com.jimeng.dataserver.admin.operator.stats.controller;

import com.jimeng.dataserver.admin.operator.common.OperatorGuard;
import com.jimeng.dataserver.admin.operator.stats.dto.OperatorOverviewStats;
import com.jimeng.dataserver.admin.operator.stats.dto.TenantUsageRow;
import com.jimeng.dataserver.admin.operator.stats.service.OperatorStatsService;
import com.jimeng.dataserver.ai.stats.dto.DashboardOverview;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运营门户 —— 跨租户用量统计（花费 / token / 耗时）。
 * 所有方法先经 {@link OperatorGuard} 校验运营身份，防止企业账号越权读取全平台数据。
 */
@Tag(name = "运营-用量统计", description = "跨租户 AI 用量 / 成本 / 模型统计")
@RestController
@RequestMapping("/data/admin/operator/stats")
@RequiredArgsConstructor
public class OperatorStatsController {

    private final OperatorStatsService operatorStatsService;
    private final OperatorGuard operatorGuard;

    @Operation(summary = "全平台用量总览")
    @GetMapping("/overview")
    public OperatorOverviewStats overview(@RequestParam(name = "days", defaultValue = "30") int days) {
        operatorGuard.requireOperatorId();
        return operatorStatsService.overview(days);
    }

    @Operation(summary = "各租户用量列表")
    @GetMapping("/by-tenant")
    public List<TenantUsageRow> byTenant(@RequestParam(name = "days", defaultValue = "30") int days) {
        operatorGuard.requireOperatorId();
        return operatorStatsService.byTenant(days);
    }

    @Operation(summary = "单租户用量明细（趋势 / 模型 / 最近调用）")
    @GetMapping("/tenant/{tenantId}")
    public DashboardOverview tenantDetail(@PathVariable("tenantId") String tenantId,
                                          @RequestParam(name = "days", defaultValue = "30") int days) {
        operatorGuard.requireOperatorId();
        return operatorStatsService.tenantDetail(tenantId, days);
    }
}
