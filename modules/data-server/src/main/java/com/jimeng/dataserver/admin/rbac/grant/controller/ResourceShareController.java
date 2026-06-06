package com.jimeng.dataserver.admin.rbac.grant.controller;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.dataserver.admin.rbac.grant.service.ResourceShareService;
import com.jimeng.dataserver.admin.rbac.permission.PermissionResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 企业门户 —— 资源分享：把某个 Agent / 插件 / 知识库分享给其他部门(角色)或设为全公司可见。
 *
 * <p>鉴权用 {@link PermissionResolver#assertCurrentAccess}——【能访问该资源的成员】即可管理它的分享
 * （资源所属部门成员、超管）；与「角色授权」(超管专属) 不同。租户隔离由实例 id 的租户过滤保证。
 *
 * <p>{@code type} 取 {@code AGENT / PLUGIN / KNOWLEDGE_BASE}（大小写不敏感）。
 */
@Tag(name = "企业-资源分享", description = "把资源分享给部门(角色)或全公司")
@RestController
@RequestMapping("/data/admin/rbac/resource/{type}/{id}/shares")
@RequiredArgsConstructor
public class ResourceShareController {

    private final ResourceShareService shareService;
    private final PermissionResolver permissionResolver;

    @Operation(summary = "读取资源分享设置")
    @GetMapping
    public ResourceShareService.ShareView get(@PathVariable String type, @PathVariable Long id) {
        ResourceType rt = parseType(type);
        permissionResolver.assertCurrentAccess(rt, id);
        return shareService.getShares(AdminRequestContext.requireTenantId(), rt, id);
    }

    @Operation(summary = "设置资源分享（整体覆盖：部门 + 全公司开关）")
    @PutMapping
    public Map<String, Object> set(@PathVariable String type, @PathVariable Long id,
                                   @RequestBody ShareRequest req) {
        ResourceType rt = parseType(type);
        permissionResolver.assertCurrentAccess(rt, id);
        shareService.setShares(AdminRequestContext.requireTenantId(), rt, id,
                req == null ? null : req.getRoleIds(),
                req != null && req.isTenantWide());
        return Map.of("updated", true);
    }

    private ResourceType parseType(String type) {
        try {
            ResourceType rt = ResourceType.valueOf(type.trim().toUpperCase());
            if (rt == ResourceType.MENU) {
                throw new IllegalArgumentException();
            }
            return rt;
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "未知资源类型: " + type);
        }
    }

    @Data
    public static class ShareRequest {
        /** 分享到的角色(部门) id；不含全公司哨兵 0，由 {@link #tenantWide} 控制。 */
        private List<Long> roleIds;
        /** 是否全公司可见。 */
        private boolean tenantWide;
    }
}
