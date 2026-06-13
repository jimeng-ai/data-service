package com.jimeng.dataserver.ai.search.controller;

import com.jimeng.dataserver.ai.search.dto.GlobalSearchResult;
import com.jimeng.dataserver.ai.search.service.GlobalSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局搜索（顶栏 ⌘K 命令面板）：跨 Agent / 文档 / Trace 的快速跳转搜索。
 */
@Tag(name = "全局搜索", description = "⌘K 命令面板：Agent / 文档 / Trace 快速搜索")
@RestController
@RequestMapping("/data/admin/search")
@RequiredArgsConstructor
public class GlobalSearchController {

    private final GlobalSearchService globalSearchService;

    @Operation(summary = "全局搜索（每类返回少量命中，用于快速跳转）")
    @GetMapping
    public GlobalSearchResult search(@RequestParam(name = "q", required = false) String q,
                                     @RequestParam(name = "limit", defaultValue = "5") int limit) {
        return globalSearchService.search(q, limit);
    }
}
