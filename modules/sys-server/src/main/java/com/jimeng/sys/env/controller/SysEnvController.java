package com.jimeng.sys.env.controller;

import com.jimeng.persistence.entity.SysEnv;
import com.jimeng.sys.env.service.SysEnvService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * @Author Moonlight
 * @Description 系统环境变量控制器
 * @Date 2024/8/4 22:38
 */

@Tag(name = "系统环境变量管理", description = "系统环境变量相关接口")
@RestController
@RequestMapping({"/admin/sys/env", "/api/v1/sys/env"})
@RequiredArgsConstructor
@Slf4j
public class SysEnvController {

    private final SysEnvService sysEnvService;

    @Operation(summary = "保存环境变量", description = "保存单个系统环境变量")
    @PostMapping("/save")
    public void save(@Parameter(description = "环境变量信息") @Validated @RequestBody SysEnv sysEnv) {
        sysEnvService.save(sysEnv);
    }

    @Operation(summary = "批量保存环境变量", description = "批量保存系统环境变量")
    @PostMapping("/save-batch")
    public void saveEnvBatch(@Parameter(description = "环境变量列表") @Validated @RequestBody List<SysEnv> sysEnvList) {
        sysEnvService.saveBatch(sysEnvList);
    }

    @Operation(summary = "获取环境变量Map", description = "获取所有环境变量的Map结构")
    @GetMapping("/get-env-map")
    public Map<String, Map<String, SysEnv>> getEnvMap() {
        return sysEnvService.getEnvMap();
    }

}
