package com.jimeng.dataserver.ai.plugingen.controller;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.plugingen.PluginAiService;
import com.jimeng.dataserver.ai.plugingen.dto.GenerateRequest;
import com.jimeng.dataserver.ai.plugingen.dto.PluginDraft;
import com.jimeng.dataserver.ai.plugingen.dto.RefineRequest;
import com.jimeng.dataserver.ai.plugingen.dto.RefineResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * 插件 AI 生成 / 微调接口。
 *
 * <p>这些端点只「读 + 调 LLM + 转换」，不直接落库：返回的 {@link PluginDraft} 由前端供人复核/勾选，
 * 再走现有受权限保护的 createPlugin / createTool 端点保存。因此这里不做实例级鉴权（网关 JWT 即可）。
 */
@Tag(name = "插件 AI 生成", description = "API 文档 → 插件草稿 + 对话式微调")
@RestController
@RequestMapping("/data/admin/plugin/ai")
@RequiredArgsConstructor
public class PluginAiController {

    private final PluginAiService pluginAiService;

    @Operation(summary = "由文本 / 截图 / 文档链接生成插件草稿")
    @PostMapping("/generate")
    public PluginDraft generate(@RequestBody GenerateRequest req) {
        return pluginAiService.generate(req, UUID.randomUUID().toString());
    }

    @Operation(summary = "列出文档索引(llms.txt)里的全部接口链接，供前端「自动跑完整份文档」分批")
    @PostMapping("/list-endpoints")
    public Map<String, Object> listEndpoints(@RequestBody GenerateRequest req) {
        if (req == null || req.getDocUrl() == null || req.getDocUrl().isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "docUrl 不能为空");
        }
        return Map.of("links", pluginAiService.listEndpointLinks(req.getDocUrl()));
    }

    @Operation(summary = "上传 API 文档文件（PDF/Word/Markdown）生成插件草稿")
    @PostMapping(value = "/generate/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PluginDraft generateUpload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "文件为空");
        }
        try {
            return pluginAiService.generateFromFile(file.getInputStream(),
                    file.getContentType(), file.getOriginalFilename(), UUID.randomUUID().toString());
        } catch (IOException e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "读取文件失败：" + e.getMessage());
        }
    }

    @Operation(summary = "对话式微调插件草稿（返回完整更新后草稿）")
    @PostMapping("/refine")
    public RefineResponse refine(@RequestBody RefineRequest req) {
        return pluginAiService.refine(req, UUID.randomUUID().toString());
    }
}
