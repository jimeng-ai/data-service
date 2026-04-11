package com.jimeng.dataserver.gaode.controller;

import com.jimeng.dataserver.gaode.service.AdcodeCitycodeDictService;
import com.jimeng.persistence.entity.AdcodeCitycodeDict;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @Author Moonlight
 * @Description 行政区编码字典控制器
 * @Date 2026/3/29 13:30
 */
@Tag(name = "行政区编码字典管理", description = "adcode-citycode字典相关接口")
@RestController
@RequestMapping("/data/adcode-citycode-dict")
@RequiredArgsConstructor
public class AdcodeCitycodeDictController {

    private final AdcodeCitycodeDictService adcodeCitycodeDictService;

    @Operation(summary = "根据ID查询", description = "根据ID查询行政区编码字典")
    @GetMapping("/get-by-id")
    public AdcodeCitycodeDict getById(@Parameter(description = "主键ID") @RequestParam Long id) {
        return adcodeCitycodeDictService.getById(id);
    }

    @Operation(summary = "查询列表", description = "按条件查询行政区编码字典列表")
    @GetMapping("/list")
    public List<AdcodeCitycodeDict> list(@Parameter(description = "行政区编码字典查询条件") AdcodeCitycodeDict condition) {
        return adcodeCitycodeDictService.list(condition);
    }

    @Operation(summary = "根据ID更新", description = "根据ID更新行政区编码字典")
    @PostMapping("/update-by-id")
    public void updateById(@Parameter(description = "行政区编码字典") @Validated @RequestBody AdcodeCitycodeDict dict) {
        adcodeCitycodeDictService.updateById(dict);
    }
}
