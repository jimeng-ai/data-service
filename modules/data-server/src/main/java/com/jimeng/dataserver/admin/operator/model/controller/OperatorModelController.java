package com.jimeng.dataserver.admin.operator.model.controller;

import com.jimeng.dataserver.admin.operator.common.OperatorGuard;
import com.jimeng.dataserver.admin.operator.model.dto.ModelUpsertRequest;
import com.jimeng.dataserver.admin.operator.model.dto.ProviderOption;
import com.jimeng.dataserver.admin.operator.model.service.OperatorModelService;
import com.jimeng.persistence.entity.AiModel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 运营门户：平台级模型目录维护（目录 + 计费单价 + 上游路由一处定义）。先经 OperatorGuard 校验运营身份。 */
@Tag(name = "运营-模型管理", description = "可选模型目录维护：展示/计费/上游路由")
@RestController
@RequestMapping("/data/admin/operator/models")
@RequiredArgsConstructor
public class OperatorModelController {

    private final OperatorModelService operatorModelService;
    private final OperatorGuard operatorGuard;

    @Operation(summary = "模型列表（含禁用，按 sort）")
    @GetMapping
    public List<AiModel> list() {
        operatorGuard.requireOperatorId();
        return operatorModelService.list();
    }

    @Operation(summary = "可选连接（provider）下拉，含各自协议")
    @GetMapping("/providers")
    public List<ProviderOption> providers() {
        operatorGuard.requireOperatorId();
        return operatorModelService.providers();
    }

    @Operation(summary = "新增模型")
    @PostMapping
    public AiModel create(@RequestBody ModelUpsertRequest req) {
        Long operatorId = operatorGuard.requireOperatorId();
        return operatorModelService.create(req, operatorId);
    }

    @Operation(summary = "编辑模型")
    @PutMapping("/{id}")
    public AiModel update(@PathVariable Long id, @RequestBody ModelUpsertRequest req) {
        Long operatorId = operatorGuard.requireOperatorId();
        return operatorModelService.update(id, req, operatorId);
    }

    @Operation(summary = "启用/下线开关")
    @PatchMapping("/{id}/enabled")
    public void setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        Long operatorId = operatorGuard.requireOperatorId();
        operatorModelService.setEnabled(id, enabled, operatorId);
    }

    @Operation(summary = "删除模型（软删）")
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        operatorGuard.requireOperatorId();
        operatorModelService.delete(id);
    }
}
