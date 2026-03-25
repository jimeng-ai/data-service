package com.jimeng.dataserver.controller;

import com.jimeng.dataserver.entity.GaoDeDTO;
import com.jimeng.dataserver.service.GaoDeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "高德地图数据管理", description = "高德地图相关接口")
@RestController
@RequestMapping("/data/gaode")
@RequiredArgsConstructor
public class GaoDeController {

    private final GaoDeService gaoDeService;

    @Operation(summary = "获取关键词poi", description = "关键词poi")
    @PostMapping("/get-poi-by-keyword")
    public GaoDeDTO.KeywordPOIResp getKeyworkPOI(@RequestBody GaoDeDTO.KeyworkPOI poi){
        return gaoDeService.getPOIByKeyword(poi);
    }

    @Operation(summary = "获取周边poi", description = "周边poi")
    @PostMapping("/get-poi-by-around")
    public GaoDeDTO.AroundPOIResp getAroundPOI(@RequestBody GaoDeDTO.AroundPOI poi){
        return gaoDeService.getPOIByAround(poi);
    }

}
