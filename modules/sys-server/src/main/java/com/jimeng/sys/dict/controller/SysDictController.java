package com.jimeng.sys.dict.controller;

import com.jimeng.persistence.entity.SysDict;
import com.jimeng.sys.dict.service.SysDictService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @Author Moonlight
 * @Description 系统字典控制器
 * @Date 2024/8/4 22:38
 */

@Tag(name = "系统字典管理", description = "系统字典相关接口")
@RestController
@RequestMapping({"/admin/sys/dict", "/api/v1/sys/dict"})
@RequiredArgsConstructor
public class SysDictController {

    private final SysDictService sysDictService;

    @Operation(summary = "保存字典", description = "保存单个系统字典")
    @PostMapping("/save")
    public void save(@Parameter(description = "字典信息") @Validated @RequestBody SysDict sysDict) {
        sysDictService.save(sysDict);
    }

    @Operation(summary = "批量保存字典", description = "批量保存系统字典")
    @PostMapping("/save-batch")
    public void saveDictBatch(@Parameter(description = "字典列表") @Validated @RequestBody List<SysDict> sysDictList) {
        sysDictService.saveBatch(sysDictList);
    }

}
