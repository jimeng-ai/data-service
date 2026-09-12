package com.jimeng.dataserver.ai.connection;

import com.jimeng.dataserver.admin.rbac.common.SuperAdminGuard;
import com.jimeng.persistence.entity.Connection;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 外部连接管理。
 *
 * <p><b>全部限企业超管。</b>一条连接 = 一份可用凭据 + 一条出网许可，谁能建连接谁就能让
 * 任意被授权的 Agent 以该身份调外部系统。在连接进入 RBAC 资源体系之前，
 * 权限上的默认值取最严的那个——收紧容易，放开难。
 */
@Tag(name = "外部连接", description = "技能调用外部系统的连接注册表")
@RestController
@RequestMapping("/data/admin/connections")
@RequiredArgsConstructor
public class ConnectionAdminController {

    private final ConnectionService connectionService;
    private final SuperAdminGuard superAdminGuard;

    @Operation(summary = "连接列表（不含凭据）")
    @GetMapping
    public List<Connection> list() {
        superAdminGuard.requireSuperAdmin();
        return connectionService.list();
    }

    @Operation(summary = "连接详情（不含凭据）")
    @GetMapping("/{id}")
    public Connection get(@PathVariable Long id) {
        superAdminGuard.requireSuperAdmin();
        return connectionService.get(id);
    }

    @Operation(summary = "新建连接")
    @PostMapping
    public Connection create(@RequestBody ConnectionUpsert req) {
        superAdminGuard.requireSuperAdmin();
        return connectionService.create(req);
    }

    @Operation(summary = "编辑连接（credential 留空则不改动凭据）")
    @PutMapping("/{id}")
    public Connection update(@PathVariable Long id, @RequestBody ConnectionUpsert req) {
        superAdminGuard.requireSuperAdmin();
        return connectionService.update(id, req);
    }

    @Operation(summary = "启用/停用连接")
    @PostMapping("/{id}/status")
    public Map<String, Object> setStatus(@PathVariable Long id, @RequestParam String status) {
        superAdminGuard.requireSuperAdmin();
        connectionService.setStatus(id, status);
        return Map.of("status", status);
    }

    @Operation(summary = "删除连接（同时摘除所有 Agent 授权）")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        superAdminGuard.requireSuperAdmin();
        connectionService.delete(id);
        return Map.of("deleted", true);
    }
}
