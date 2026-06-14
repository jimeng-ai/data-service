package com.jimeng.dataserver.ai.model;

import com.jimeng.dataserver.ai.model.dto.ModelView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 可选模型目录（只读）。返回 GlobalResponseHandler 包装的 envelope。 */
@Tag(name = "模型目录", description = "可选模型单一真相源")
@RestController
@RequestMapping("/data/admin")
@RequiredArgsConstructor
public class ModelCatalogController {

    private final ModelCatalogService modelCatalogService;

    @Operation(summary = "列出可选模型", description = "返回启用中的模型，供调试台/构建器选型")
    @GetMapping("/models")
    public List<ModelView> list() {
        return modelCatalogService.listEnabled();
    }
}
