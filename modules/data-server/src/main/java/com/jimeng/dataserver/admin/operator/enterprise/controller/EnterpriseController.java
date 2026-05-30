package com.jimeng.dataserver.admin.operator.enterprise.controller;

import com.jimeng.dataserver.admin.operator.common.OperatorGuard;
import com.jimeng.dataserver.admin.operator.enterprise.dto.CreateEnterpriseRequest;
import com.jimeng.dataserver.admin.operator.enterprise.dto.EnterpriseView;
import com.jimeng.dataserver.admin.operator.enterprise.dto.ResetPasswordRequest;
import com.jimeng.dataserver.admin.operator.enterprise.service.EnterpriseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 运营门户 —— 企业（租户）管理。所有方法先经 {@link OperatorGuard} 校验运营身份。
 */
@Tag(name = "运营-企业管理", description = "创建企业（含超管）/ 列表 / 启停 / 重置超管密码")
@RestController
@RequestMapping("/data/admin/operator/enterprises")
@RequiredArgsConstructor
public class EnterpriseController {

    private final EnterpriseService enterpriseService;
    private final OperatorGuard operatorGuard;

    @Operation(summary = "创建企业（同时创建超级管理员）")
    @PostMapping
    public EnterpriseView create(@RequestBody CreateEnterpriseRequest req) {
        operatorGuard.requireOperatorId();
        return enterpriseService.create(req);
    }

    @Operation(summary = "企业列表")
    @GetMapping
    public List<EnterpriseView> list() {
        operatorGuard.requireOperatorId();
        return enterpriseService.list();
    }

    @Operation(summary = "企业详情")
    @GetMapping("/{id}")
    public EnterpriseView get(@PathVariable Long id) {
        operatorGuard.requireOperatorId();
        return enterpriseService.getView(id);
    }

    @Operation(summary = "启用企业")
    @PostMapping("/{id}/enable")
    public Map<String, Object> enable(@PathVariable Long id) {
        operatorGuard.requireOperatorId();
        enterpriseService.setStatus(id, true);
        return Map.of("status", 1);
    }

    @Operation(summary = "停用企业（封禁该租户全员登录）")
    @PostMapping("/{id}/disable")
    public Map<String, Object> disable(@PathVariable Long id) {
        operatorGuard.requireOperatorId();
        enterpriseService.setStatus(id, false);
        return Map.of("status", 0);
    }

    @Operation(summary = "重置该企业超级管理员密码")
    @PostMapping("/{id}/super-admin/reset-password")
    public Map<String, Object> resetSuperAdminPassword(@PathVariable Long id,
                                                       @RequestBody ResetPasswordRequest req) {
        operatorGuard.requireOperatorId();
        enterpriseService.resetSuperAdminPassword(id, req.getNewPassword());
        return Map.of("reset", true);
    }
}
