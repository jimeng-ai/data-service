package com.jimeng.dataserver.controller;

import com.jimeng.dataserver.service.PoiCategoryDictService;
import com.jimeng.persistence.entity.PoiCategoryDict;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @Author Moonlight
 * @Description POI分类字典控制器
 * @Date 2026/3/28 13:18
 */
@Tag(name = "POI分类字典管理", description = "POI分类字典相关接口")
@RestController
@RequestMapping("/data/poi-category-dict")
@RequiredArgsConstructor
public class PoiCategoryDictController {

    private final PoiCategoryDictService poiCategoryDictService;

    @Operation(summary = "根据ID查询", description = "根据ID查询POI分类字典")
    @GetMapping("/get-by-id")
    public PoiCategoryDict getById(@Parameter(description = "主键ID") @RequestParam Long id) {
        return poiCategoryDictService.getById(id);
    }

    @Operation(summary = "查询列表", description = "按条件查询POI分类字典列表")
    @GetMapping("/list")
    public List<PoiCategoryDict> list(@Parameter(description = "POI分类字典查询条件") PoiCategoryDict condition) {
        return poiCategoryDictService.list(condition);
    }

    @Operation(summary = "根据ID更新", description = "根据ID更新POI分类字典")
    @PostMapping("/update-by-id")
    public void updateById(@Parameter(description = "POI分类字典") @Validated @RequestBody PoiCategoryDict poiCategoryDict) {
        poiCategoryDictService.updateById(poiCategoryDict);
    }
}
