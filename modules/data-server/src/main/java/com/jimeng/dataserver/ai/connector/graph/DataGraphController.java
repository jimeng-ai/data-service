package com.jimeng.dataserver.ai.connector.graph;

import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
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
 * 数据星图：给业务人员和产品看的业务对象关系图（设计文档 {@code docs/superpowers/specs/2026-09-30-enterprise-data-graph-design.md}）。
 *
 * <p>按模块授权（§9）：被授予「数据星图」模块的成员和企业超管可用，能看到本企业全部系统。这个功能没有实例授权，
 * 所以模块检查必须在后端做，前端的入口隐藏只是纵深防御。
 */
@Tag(name = "数据星图", description = "各业务系统的业务对象与关联（只读）")
@RestController
@RequestMapping("/data/admin/data-graph")
@RequiredArgsConstructor
public class DataGraphController {

    static final String DENIED = "没有数据星图的访问权限";

    private final PermissionResolver permissionResolver;
    private final DataGraphService dataGraphService;

    @Operation(summary = "系统列表（有结构快照的数据连接）")
    @GetMapping("/systems")
    public List<SystemSummary> systems() {
        requireModule();
        return dataGraphService.systems();
    }

    @Operation(summary = "某个系统的业务对象与关联")
    @GetMapping("/systems/{connectorId}")
    public SystemGraph system(@PathVariable String connectorId) {
        requireModule();
        return dataGraphService.system(connectorId);
    }

    @Operation(summary = "单个对象的详情：说明、关联与技术信息")
    @GetMapping("/systems/{connectorId}/tables")
    public TableDetail table(@PathVariable String connectorId, @RequestParam("name") String name) {
        requireModule();
        return dataGraphService.table(connectorId, name);
    }

    private void requireModule() {
        permissionResolver.assertCurrentModule(PlatformConstant.MODULE_DATA_GRAPH, DENIED);
    }
}
