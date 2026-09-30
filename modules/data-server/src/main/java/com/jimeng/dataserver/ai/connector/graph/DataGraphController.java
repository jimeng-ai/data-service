package com.jimeng.dataserver.ai.connector.graph;

import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemSummary;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.TableDetail;
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
 * 数据星图：语义层表关系的只读客户视图（设计文档 {@code docs/superpowers/specs/2026-09-30-enterprise-data-graph-design.md}）。
 *
 * <p>全部限企业超管，与 {@code ConnectorAdminController} 一致：表结构本身就是敏感元数据。
 */
@Tag(name = "数据星图", description = "各业务系统的表与表关系（只读）")
@RestController
@RequestMapping("/data/admin/data-graph")
@RequiredArgsConstructor
public class DataGraphController {

    private final SuperAdminGuard superAdminGuard;
    private final DataGraphService dataGraphService;

    @Operation(summary = "系统列表（有结构快照的数据连接）")
    @GetMapping("/systems")
    public List<SystemSummary> systems() {
        superAdminGuard.requireSuperAdmin();
        return dataGraphService.systems();
    }

    @Operation(summary = "某个系统的表卡片与关系")
    @GetMapping("/systems/{connectorId}")
    public SystemGraph system(@PathVariable String connectorId) {
        superAdminGuard.requireSuperAdmin();
        return dataGraphService.system(connectorId);
    }

    @Operation(summary = "单表详情：字段与相关关系")
    @GetMapping("/systems/{connectorId}/tables")
    public TableDetail table(@PathVariable String connectorId, @RequestParam("name") String name) {
        superAdminGuard.requireSuperAdmin();
        return dataGraphService.table(connectorId, name);
    }
}
