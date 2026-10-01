package com.jimeng.dataserver.ai.connector.graph;

import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.TableDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据星图：一个库的业务对象关系图（设计文档 {@code docs/superpowers/specs/2026-09-30-enterprise-data-graph-design.md}）。
 *
 * <p>入口是「数据连接」里每个库卡片上的「查看星图」，所以和 {@code ConnectorAdminController} 一样只给企业超管（§9）：
 * 表结构本身就是敏感元数据。
 */
@Tag(name = "数据星图", description = "一个库的业务对象与关联（只读）")
@RestController
@RequestMapping("/data/admin/data-graph")
@RequiredArgsConstructor
public class DataGraphController {

    private final SuperAdminGuard superAdminGuard;
    private final DataGraphService dataGraphService;

    @Operation(summary = "某个库的业务对象与关联")
    @GetMapping("/systems/{connectorId}")
    public SystemGraph system(@PathVariable String connectorId) {
        superAdminGuard.requireSuperAdmin();
        return dataGraphService.system(connectorId);
    }

    @Operation(summary = "单个对象的详情：说明、关联与技术信息")
    @GetMapping("/systems/{connectorId}/tables")
    public TableDetail table(@PathVariable String connectorId, @RequestParam("name") String name) {
        superAdminGuard.requireSuperAdmin();
        return dataGraphService.table(connectorId, name);
    }
}
